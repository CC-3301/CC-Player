package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.streaming.ByteSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 会话建立总预算：等待到点即放弃，阻塞中的建连不在预算内被打断。 */
private const val ESTABLISH_BUDGET_MILLIS = 5_000L

/** 复用会话前的存活确认上限。 */
private const val ALIVE_CHECK_MILLIS = 700L

/** 闲置且没有引用时的关闭阈值。 */
private const val SESSION_IDLE_MILLIS = 2 * 60 * 1000L

/** 同时保留的会话上限；超出时关掉最久未用且没有引用的会话。 */
private const val MAX_SESSIONS = 8

/**
 * 应用级 SMB 会话层：按（服务器、端口、共享、凭据）复用一条会话，目录浏览、封面与播放共用同一条。
 *
 * 取用复用的会话前做一次会话级存活确认，失败即作废并重建一次；传输类失败作废会话后重连并重发一次，
 * 认证失败与路径不存在不作废。会话闲置且没有引用时关闭，下次取用时懒重建。
 */
class SmbSessions(
    private val connector: SmbConnector,
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val maxSessions: Int = MAX_SESSIONS,
    private val idleMillis: Long = SESSION_IDLE_MILLIS,
) {

    private val lock = Any()

    /** 按共享复用的会话：主机、端口、共享名与凭据相同即同一条。 */
    private val sessions = HashMap<SessionKey, Session>()

    /** 列出服务器共享名：共享枚举走独立的一次性连接，不占用会话表。 */
    suspend fun shares(host: String, port: Int, username: String, password: String?): List<String> =
        connector.shares(host, port, username, password)

    /** 起播前探测：取到会话即交回，播放随后复用同一条。 */
    suspend fun probe(target: SmbTarget) {
        acquire(target).release()
    }

    /** 借用会话完成一次请求；传输类失败作废会话后重发一次，第二次失败同样作废。 */
    suspend fun <T> use(target: SmbTarget, request: suspend (SmbConnection) -> T): T =
        resend(target) { borrow(target, request) }

    /** 打开一个随机读句柄；会话占用保持到句柄关闭。 */
    suspend fun openRead(target: SmbTarget): ByteSource = SmbStreamSource(resend(target) { open(target) })

    /** 一次操作；传输类失败作废会话后重发一次，第二次失败原样抛出（会话已作废）。 */
    private suspend fun <T> resend(target: SmbTarget, attempt: suspend () -> T): T = try {
        attempt()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        // 停滞类同样只作废不重发：重连必然超过回环层的停滞判据，列表与打开把失败交给界面。
        if (!smbConnectionLost(failure) || smbStalled(failure)) throw failure
        diagnostics.log { "SMB 请求失败，作废会话后重发: ${target.share} (${failure.message})" }
        attempt()
    }

    /** 打开一次：会话占用交给读源；失败时先按传输类作废会话再交还引用。 */
    private suspend fun open(target: SmbTarget): ReadLease {
        val lease = acquire(target)
        return try {
            ReadLease(target, lease, lease.connection.openRead(target.path))
        } catch (cancellation: CancellationException) {
            lease.release()
            throw cancellation
        } catch (failure: Throwable) {
            if (smbConnectionLost(failure)) lease.discard()
            lease.release()
            throw failure
        }
    }

    /** 借用一次：失败时先按传输类作废会话再交还引用，成功也交还。 */
    private suspend fun <T> borrow(target: SmbTarget, request: suspend (SmbConnection) -> T): T {
        val lease = acquire(target)
        try {
            return request(lease.connection)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            if (smbConnectionLost(failure)) lease.discard()
            throw failure
        } finally {
            lease.release()
        }
    }

    /** 复用的会话先占一个引用再确认存活；存活确认失败时作废并重建一次。 */
    private suspend fun acquire(target: SmbTarget): SessionLease {
        val key = SessionKey.of(target)
        val reused = reuse(key)
        if (reused != null) {
            val alive = try {
                reused.connection.ping(ALIVE_CHECK_MILLIS)
                true
            } catch (cancellation: CancellationException) {
                release(reused)
                throw cancellation
            } catch (failure: Throwable) {
                false
            }
            if (alive) {
                diagnostics.log { "SMB 会话取用: $key (在用 ${reused.inUse})" }
                return SessionLease(reused)
            }
            diagnostics.log { "SMB 会话存活确认失败，作废重建: $key" }
            invalidate(reused)
            release(reused)
        }
        return SessionLease(establish(key, target))
    }

    private fun reuse(key: SessionKey): Session? = synchronized(lock) {
        val session = sessions[key] ?: return null
        session.idleJob?.cancel()
        session.idleJob = null
        session.inUse++
        session.lastUsedAt = clock()
        session
    }

    private suspend fun establish(key: SessionKey, target: SmbTarget): Session {
        val started = clock()
        val connection = connectWithinBudget(target)
        val registration = synchronized(lock) {
            val current = sessions[key]
            val adopted = if (current != null) {
                // 并发建连：留先登记的那条，自己这条没用上。
                current.idleJob?.cancel()
                current.idleJob = null
                current.inUse++
                current.lastUsedAt = clock()
                current
            } else {
                val fresh = Session(key, connection)
                fresh.inUse = 1
                fresh.lastUsedAt = clock()
                sessions[key] = fresh
                fresh
            }
            Registration(adopted, evictBeyondLimitLocked(exclude = key))
        }
        if (registration.session.connection !== connection) closeQuietly(connection)
        registration.superseded.forEach(::closeQuietly)
        diagnostics.log { "SMB 会话建立: $key (${clock() - started}ms)" }
        return registration.session
    }

    /**
     * 建连、认证与打开共享走这条：预算到点就放弃等待。
     * 阻塞中的建连不会被打断，它建成的连接由收尾任务关掉，不留在无人认领的状态。
     */
    private suspend fun connectWithinBudget(target: SmbTarget): SmbConnection {
        val connecting = scope.async(start = CoroutineStart.UNDISPATCHED) {
            connector.connect(
                host = target.host,
                port = target.port,
                share = target.share,
                username = target.username,
                password = target.password,
            )
        }
        return try {
            withTimeout(ESTABLISH_BUDGET_MILLIS) { connecting.await() }
        } catch (timeout: TimeoutCancellationException) {
            closeWhenFinished(connecting)
            diagnostics.log { "SMB 会话建立超时: ${target.host}/${target.share}" }
            throw MediaSourceException(MediaFailure.Unreachable, "SMB 会话建立超时：${target.host}", timeout)
        } catch (cancellation: CancellationException) {
            closeWhenFinished(connecting)
            throw cancellation
        }
    }

    /** 预算到点时建连可能还在跑：建成后由这里关掉，晚到的连接不会没人关。 */
    private fun closeWhenFinished(connecting: Deferred<SmbConnection>) {
        scope.launch {
            val connection = try {
                connecting.await()
            } catch (failure: Throwable) {
                return@launch
            }
            closeQuietly(connection)
        }
    }

    /** 句柄关闭或请求结束：计数归零时安排闲置关闭；已被摘掉的会话直接关闭连接。 */
    private fun release(session: Session) {
        val closeable = synchronized(lock) {
            session.inUse = (session.inUse - 1).coerceAtLeast(0)
            if (session.inUse != 0) return
            if (sessions[session.key] !== session) {
                takeConnectionLocked(session)
            } else {
                session.lastUsedAt = clock()
                session.idleJob?.cancel()
                session.idleJob = scheduleIdleClose(session)
                null
            }
        }
        closeable?.let(::closeQuietly)
        diagnostics.log { "SMB 会话归零: ${session.key}" }
    }

    private fun scheduleIdleClose(session: Session): Job = scope.launch {
        delay(idleMillis)
        val closeable = synchronized(lock) {
            if (session.inUse != 0 || sessions[session.key] !== session) return@launch
            sessions.remove(session.key)
            takeConnectionLocked(session)
        }
        diagnostics.log { "SMB 会话闲置关闭: ${session.key}" }
        closeable?.let(::closeQuietly)
    }

    /** 作废会话：从表里摘掉，不再发给新的取用方；引用归零时关闭连接。 */
    private fun invalidate(session: Session) {
        synchronized(lock) {
            if (sessions[session.key] === session) sessions.remove(session.key)
            session.idleJob?.cancel()
            session.idleJob = null
        }
    }

    /**
     * 交出发连接用于关闭；同一条会话只交一次。
     * 释放、淘汰与整体作废都走这里，避免同一条连接被重复关闭。
     */
    private fun takeConnectionLocked(session: Session): SmbConnection? {
        if (session.taken) return null
        session.taken = true
        return session.connection
    }

    /**
     * 会话表超过上限时摘出最久未用且没有引用的会话，当前这条永不入选，由调用方在锁外关闭。
     * 全都在用时宁可暂时超出上限，也不关正在用的会话。
     */
    private fun evictBeyondLimitLocked(exclude: SessionKey): List<SmbConnection> {
        val evicted = ArrayList<SmbConnection>()
        while (sessions.size > maxSessions) {
            val oldest = sessions.entries
                .filter { entry -> entry.key != exclude && entry.value.inUse == 0 }
                .minByOrNull { entry -> entry.value.lastUsedAt }
                ?: return evicted
            sessions.remove(oldest.key)
            takeConnectionLocked(oldest.value)?.let { evicted += it }
        }
        return evicted
    }

    private fun closeQuietly(connection: SmbConnection) {
        runCatching { connection.close() }
    }

    /** 会话建立后登记的结果：真正采用的那条与会超出上限而淘汰的旧连接。 */
    private class Registration(val session: Session, val superseded: List<SmbConnection>)

    /** 会话的登记：连接、最近一次取用的时刻与在用引用数。 */
    private class Session(val key: SessionKey, val connection: SmbConnection) {

        /** 正在使用的引用数；不为 0 时不得关闭。 */
        var inUse = 0

        /** 最近一次取用或归零的时刻。 */
        var lastUsedAt = 0L

        /** 等待中的闲置关闭任务；重新取用时取消。 */
        var idleJob: Job? = null

        /** 连接已交出去关闭；重复丢弃不再关第二次。 */
        var taken = false
    }

    /** 一次取用：占用会话直到释放。 */
    private inner class SessionLease(val session: Session) {

        val key: SessionKey get() = session.key

        val connection: SmbConnection get() = session.connection

        private val released = AtomicBoolean(false)

        fun discard() = invalidate(session)

        fun release() {
            if (!released.compareAndSet(false, true)) return
            this@SmbSessions.release(session)
        }
    }

    /** 交给读源的一次打开：句柄与会话占用；同一条租约只关一次，换会话时先关旧句柄再开新。 */
    private inner class ReadLease(
        private val target: SmbTarget,
        private val lease: SessionLease,
        override val handle: SmbReadHandle,
    ) : SmbReadLease {

        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { handle.close() }
            lease.release()
        }

        override fun discard() {
            lease.discard()
            close()
        }

        override fun replace(): SmbReadLease = runBlocking {
            lease.discard()
            close()
            open(target)
        }
    }

    private data class SessionKey(
        val host: String,
        val port: Int,
        val share: String,
        val username: String,
        val password: String?,
    ) {
        /** 诊断日志可能打到会话身份，凭据不进日志。 */
        override fun toString(): String = "SessionKey(host=$host, port=$port, share=$share, username=$username)"

        companion object {
            fun of(target: SmbTarget) = SessionKey(
                host = target.host,
                port = target.port,
                share = target.share,
                username = target.username,
                password = target.password,
            )
        }
    }
}
