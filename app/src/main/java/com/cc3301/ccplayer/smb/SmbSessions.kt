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
 * 会话之下按（服务器、端口）复用一条共享传输，多条会话共用；传输的关闭只由引用计数决定，
 * 关会话不等于关传输。取用复用的会话前做一次会话级存活确认，失败按作废范围处理：
 * 会话失效只重建会话，本地传输失效换掉故障服务器＋端口的传输，
 * 认证、路径与业务失败不作废也不重试。播放读打开时持有读取租约，持有期间列表、封面与探测
 * 只作废自己的会话，不换也不关这条传输；没有播放读取租约时浏览操作才换传输。
 * 会话闲置且没有引用时关闭，下次取用时懒重建。
 *
 * 并发建连：两个消费者同时发现会话已死会各自建立，先登记进表的留下、后到的 adopt 它并关掉自己刚建的会话，
 * 因此建连期间同一个键上允许短暂存在两条会话，最终收敛到一条；不为此加运行期检查。
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

    /** 共享传输：按服务器＋端口复用，会话在它上面建立。 */
    private val transports = SmbTransports(connector, diagnostics)

    /** 按共享复用的会话：主机、端口、共享名与凭据相同即同一条。 */
    private val sessions = HashMap<SessionKey, Session>()

    /** 列出服务器共享名：共享枚举走独立的一次性连接，不占用会话表。 */
    suspend fun shares(host: String, port: Int, username: String, password: String?): List<String> =
        connector.shares(host, port, username, password)

    /** 起播前探测：取到会话即交回，播放随后复用同一条。 */
    suspend fun probe(target: SmbTarget) {
        acquire(target, playbackFlow = false).release()
    }

    /**
     * 借用会话完成一次请求；失败按作废范围重建一次：会话失效只重建会话，本地传输失效连传输一起换掉。
     * 播放持有读取租约时浏览操作不换传输，失败原样抛出。会话建立失败不重发：建立预算只算一次。
     */
    suspend fun <T> use(target: SmbTarget, request: suspend (SmbConnection) -> T): T {
        val first = acquire(target, playbackFlow = false)
        try {
            return request(first.connection)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            when (smbFailureScope(failure)) {
                SmbFailureScope.None -> throw failure
                SmbFailureScope.Session -> {
                    diagnostics.log { "SMB 请求失败，作废会话后重发: ${target.share} (${failure.message})" }
                    first.discardSession()
                }
                SmbFailureScope.Transport -> {
                    if (!first.mayReplaceTransport(playbackFlow = false)) {
                        diagnostics.log { "SMB 请求失败，播放持有读取租约，只作废本次会话: ${target.share}" }
                        first.discardSession()
                        throw failure
                    }
                    diagnostics.log { "SMB 请求失败，换传输后重发: ${target.share} (${failure.message})" }
                    first.discardTransport()
                }
            }
        } finally {
            first.release()
        }
        return resendUse(target, request)
    }

    /**
     * 打开一个随机读句柄；会话占用保持到句柄关闭，句柄关闭是唯一的减引用点。
     * 封面与预览的读不持有播放读取租约。
     */
    suspend fun openRead(target: SmbTarget): ByteSource = open(target, playbackLease = false)

    /**
     * 播放打开的随机读句柄：持有读取租约。
     * 持有期间列表、封面与探测只能处理自己的会话，不得换掉或关闭这条共享传输。
     */
    suspend fun openPlaybackRead(target: SmbTarget): ByteSource = open(target, playbackLease = true)

    /**
     * 打开读句柄；已拿到会话后打开句柄失败，按失败范围重建一次。
     * 建立会话不在重发范围：建立失败直接抛出。
     */
    private suspend fun open(target: SmbTarget, playbackLease: Boolean): ByteSource {
        val session = acquire(target, playbackFlow = playbackLease)
        val opened = try {
            ReadLease(session, session.connection.openRead(target.path), playbackLease)
        } catch (cancellation: CancellationException) {
            session.release()
            throw cancellation
        } catch (failure: Throwable) {
            when (smbFailureScope(failure)) {
                SmbFailureScope.None -> {
                    session.release()
                    throw failure
                }
                SmbFailureScope.Session -> {
                    diagnostics.log { "SMB 打开失败，作废会话后重连重发: ${target.share} (${failure.message})" }
                    session.discardSession()
                    session.release()
                }
                SmbFailureScope.Transport -> {
                    if (!session.mayReplaceTransport(playbackLease)) {
                        diagnostics.log { "SMB 打开失败，播放持有读取租约，只作废本次会话: ${target.share}" }
                        session.discardSession()
                        session.release()
                        throw failure
                    }
                    diagnostics.log { "SMB 打开失败，换传输后重连重发: ${target.share} (${failure.message})" }
                    session.discardTransport()
                    session.release()
                }
            }
            reopenRead(target, playbackLease)
        }
        return SmbStreamSource(opened)
    }

    /** 重发一次；第二次仍是本地传输失效就淘汰这条传输，失败原样抛出。 */
    private suspend fun <T> resendUse(target: SmbTarget, request: suspend (SmbConnection) -> T): T {
        val retry = acquire(target, playbackFlow = false)
        try {
            return request(retry.connection)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            discardAfterFailure(retry, failure, playbackFlow = false)
            throw failure
        } finally {
            retry.release()
        }
    }

    /** 重连一次后打开句柄；这次建立失败同样不重试，失败原样抛出。 */
    private suspend fun reopenRead(target: SmbTarget, playbackLease: Boolean): ReadLease {
        val lease = acquire(target, playbackFlow = playbackLease)
        return try {
            ReadLease(lease, lease.connection.openRead(target.path), playbackLease)
        } catch (cancellation: CancellationException) {
            lease.release()
            throw cancellation
        } catch (failure: Throwable) {
            discardAfterFailure(lease, failure, playbackLease)
            lease.release()
            throw failure
        }
    }

    /** 复用的会话先占一个引用再确认存活；失败按作废范围重建。 */
    private suspend fun acquire(target: SmbTarget, playbackFlow: Boolean): SessionLease {
        val key = SessionKey.of(target)
        val reused = reuse(key)
        if (reused != null) {
            try {
                reused.connection.ping(ALIVE_CHECK_MILLIS)
            } catch (cancellation: CancellationException) {
                release(reused)
                throw cancellation
            } catch (failure: Throwable) {
                return rebuildAfterAliveCheck(key, target, reused, failure, playbackFlow)
            }
            diagnostics.log { "SMB 会话取用: $key (在用 ${reused.inUse})" }
            return SessionLease(reused)
        }
        return SessionLease(establish(key, target))
    }

    /**
     * 存活确认失败之后的处理，返回重建出来的会话。
     *
     * 存活确认失败本身就是会话不能复用的证据。会话失效只重建会话：先占住这条传输，
     * 旧会话交还引用时它不会掉到零，重建的会话继续建在它上面。本地传输失效换掉这条传输；
     * 播放持有读取租约时浏览操作只能处理自己的会话，重建会继续复用旧传输，所以失败原样抛出。
     * 认证、路径与业务失败不作废也不重建。
     */
    private suspend fun rebuildAfterAliveCheck(
        key: SessionKey,
        target: SmbTarget,
        reused: Session,
        failure: Throwable,
        playbackFlow: Boolean,
    ): SessionLease = when (smbFailureScope(failure)) {
        SmbFailureScope.None -> {
            release(reused)
            throw failure
        }

        SmbFailureScope.Session -> {
            val transport = transports.acquire(target.host, target.port)
            invalidate(reused)
            release(reused)
            diagnostics.log { "SMB 会话存活确认失败，作废会话重建: $key" }
            SessionLease(establish(key, target, transport))
        }

        SmbFailureScope.Transport -> {
            if (!mayReplaceTransport(reused, playbackFlow)) {
                diagnostics.log { "SMB 会话存活确认失败，播放持有读取租约，只作废本次会话: $key" }
                invalidate(reused)
                release(reused)
                throw failure
            }
            reused.transport.discard()
            invalidate(reused)
            release(reused)
            diagnostics.log { "SMB 会话存活确认失败，换传输重建: $key" }
            SessionLease(establish(key, target))
        }
    }

    /** 本地传输失效时能否换掉这条传输：播放自己的故障流程可以，浏览操作只在没有播放读取租约时可以。 */
    private fun mayReplaceTransport(session: Session, playbackFlow: Boolean): Boolean =
        playbackFlow || !session.transport.playbackLeaseHeld

    /**
     * 失败之后作废到哪一层：本地传输失效且关闭权允许时连这条传输一起淘汰，否则只作废会话；
     * 认证、路径与业务失败不动。
     *
     * 一次本地传输失效已经说明这条传输不可用；同一个 host:port 上再次确认传输失效时不淘汰它，
     * 就等于把已失效的传输留在共享表里等下一次取用。
     */
    private fun discardAfterFailure(lease: SessionLease, failure: Throwable, playbackFlow: Boolean) {
        when (smbFailureScope(failure)) {
            SmbFailureScope.None -> Unit
            SmbFailureScope.Session -> lease.discardSession()
            SmbFailureScope.Transport ->
                if (lease.mayReplaceTransport(playbackFlow)) lease.discardTransport() else lease.discardSession()
        }
    }

    private fun reuse(key: SessionKey): Session? = synchronized(lock) {
        val session = sessions[key] ?: return null
        session.idleJob?.cancel()
        session.idleJob = null
        session.inUse++
        session.lastUsedAt = clock()
        session
    }

    private suspend fun establish(
        key: SessionKey,
        target: SmbTarget,
        transport: SmbTransports.TransportLease? = null,
    ): Session {
        val started = clock()
        val established = establishWithinBudget(target, transport)
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
                val fresh = Session(key, established.connection, established.transport)
                fresh.inUse = 1
                fresh.lastUsedAt = clock()
                sessions[key] = fresh
                fresh
            }
            Registration(adopted, evictBeyondLimitLocked(exclude = key))
        }
        if (registration.session.connection !== established.connection) established.discard()
        registration.superseded.forEach(::closeSession)
        diagnostics.log { "SMB 会话建立: $key (${clock() - started}ms)" }
        return registration.session
    }

    /**
     * 建传输、认证与打开共享走这条：预算到点就放弃等待。
     * 阻塞中的建连不会被打断，它建成的会话由收尾任务关掉，不留在无人认领的状态。
     */
    private suspend fun establishWithinBudget(
        target: SmbTarget,
        transport: SmbTransports.TransportLease?,
    ): Established {
        val connecting = scope.async(start = CoroutineStart.UNDISPATCHED) { openSession(target, transport) }
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

    /**
     * 传输与会话一起建；[borrowed] 是会话失效重建时已经占住的那条传输，没有就新建一条。
     * 会话建不起来时交还传输引用，新建的传输不会留在共享表里。
     */
    private suspend fun openSession(target: SmbTarget, borrowed: SmbTransports.TransportLease?): Established {
        val transport = borrowed ?: transports.acquire(target.host, target.port)
        return try {
            Established(transport, transport.connectShare(target.share, target.username, target.password))
        } catch (failure: Throwable) {
            transport.release()
            throw failure
        }
    }

    /** 预算到点时建连可能还在跑：建成后由这里关掉，晚到的会话不会没人关。 */
    private fun closeWhenFinished(connecting: Deferred<Established>) {
        scope.launch {
            val established = try {
                connecting.await()
            } catch (failure: Throwable) {
                return@launch
            }
            established.discard()
        }
    }

    /** 句柄关闭或请求结束：计数归零时安排闲置关闭；已被摘掉的会话直接关闭并交还传输。 */
    private fun release(session: Session) {
        val closing = synchronized(lock) {
            session.inUse -= 1
            if (session.inUse != 0) return
            if (sessions[session.key] !== session) {
                takeSessionLocked(session)
            } else {
                session.lastUsedAt = clock()
                session.idleJob?.cancel()
                session.idleJob = scheduleIdleClose(session)
                false
            }
        }
        if (closing) closeSession(session)
        diagnostics.log { "SMB 会话归零: ${session.key}" }
    }

    private fun scheduleIdleClose(session: Session): Job = scope.launch {
        delay(idleMillis)
        val closing = synchronized(lock) {
            if (session.inUse != 0 || sessions[session.key] !== session) return@launch
            sessions.remove(session.key)
            takeSessionLocked(session)
        }
        if (closing) closeSession(session)
        diagnostics.log { "SMB 会话闲置关闭: ${session.key}" }
    }

    /** 作废会话：从表里摘掉，不再发给新的取用方；引用归零时关闭会话并交还传输。 */
    private fun invalidate(session: Session) {
        synchronized(lock) {
            if (sessions[session.key] === session) sessions.remove(session.key)
            session.idleJob?.cancel()
            session.idleJob = null
        }
    }

    /**
     * 交出一条会话用于关闭；同一条会话只交一次。
     * 释放、淘汰与整体作废都走这里，避免同一条会话被重复关闭。
     */
    private fun takeSessionLocked(session: Session): Boolean {
        if (session.taken) return false
        session.taken = true
        return true
    }

    /** 关闭一条会话并交还传输引用；传输在最后一个引用交还时关闭。 */
    private fun closeSession(session: Session) {
        runCatching { session.connection.close() }
        session.transport.release()
    }

    /**
     * 会话表超过上限时摘出最久未用且没有引用的会话，当前这条永不入选，由调用方在锁外关闭。
     * 全都在用时宁可暂时超出上限，也不关正在用的会话。
     */
    private fun evictBeyondLimitLocked(exclude: SessionKey): List<Session> {
        val evicted = ArrayList<Session>()
        while (sessions.size > maxSessions) {
            val oldest = sessions.entries
                .filter { entry -> entry.key != exclude && entry.value.inUse == 0 }
                .minByOrNull { entry -> entry.value.lastUsedAt }
                ?: return evicted
            sessions.remove(oldest.key)
            if (takeSessionLocked(oldest.value)) evicted += oldest.value
        }
        return evicted
    }

    /** 建立结果：共享传输的占用与这条传输上打开的会话。 */
    private class Established(val transport: SmbTransports.TransportLease, val connection: SmbConnection) {

        /** 这条建立结果用不上：关会话并交还传输引用。 */
        fun discard() {
            runCatching { connection.close() }
            transport.release()
        }
    }

    /** 会话建立后登记的结果：真正采用的那条与会超出上限而淘汰的旧会话。 */
    private class Registration(val session: Session, val superseded: List<Session>)

    /** 会话的登记：会话连接、它占用的传输、最近一次取用的时刻与在用引用数。 */
    private class Session(
        val key: SessionKey,
        val connection: SmbConnection,
        val transport: SmbTransports.TransportLease,
    ) {

        /** 正在使用的引用数；不为 0 时不得关闭。 */
        var inUse = 0

        /** 最近一次取用或归零的时刻。 */
        var lastUsedAt = 0L

        /** 等待中的闲置关闭任务；重新取用时取消。 */
        var idleJob: Job? = null

        /** 会话已交出去关闭；重复丢弃不再关第二次。 */
        var taken = false
    }

    /** 一次取用：占用会话与它下面的传输直到释放。 */
    private inner class SessionLease(val session: Session) {

        val connection: SmbConnection get() = session.connection

        private val released = AtomicBoolean(false)

        /** 会话失效：从表里摘掉，不再发给新的取用方。 */
        fun discardSession() = invalidate(session)

        /** 本地传输失效：会话与这条传输一起作废，下一次取用建新传输。 */
        fun discardTransport() {
            invalidate(session)
            session.transport.discard()
        }

        /** 本地传输失效时能否换掉这条传输。 */
        fun mayReplaceTransport(playbackFlow: Boolean): Boolean =
            this@SmbSessions.mayReplaceTransport(session, playbackFlow)

        fun release() {
            if (!released.compareAndSet(false, true)) return
            this@SmbSessions.release(session)
        }
    }

    /** 交给读源的一次打开：句柄、会话占用与（播放读的）读取租约。 */
    private inner class ReadLease(
        private val lease: SessionLease,
        override val handle: SmbReadHandle,
        private val playbackLease: Boolean,
    ) : SmbReadLease {

        private val closed = AtomicBoolean(false)

        init {
            // 播放读从打开到关闭一直持有读取租约，期间这条传输不由浏览操作换掉或关闭。
            if (playbackLease) lease.session.transport.beginPlaybackRead()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            if (playbackLease) lease.session.transport.endPlaybackRead()
            runCatching { handle.close() }
            lease.release()
        }

        /**
         * 与 close 共用同一个开关：关过之后不再作废会话，否则会话被摘出表后没人关连接。
         * 没有失败（陈旧源丢弃）只作废会话；读失败按失败范围处置，
         * 本地传输失效时播放自己的故障流程与没有播放读取租约的浏览读都淘汰这条传输。
         */
        override fun discard(failure: Throwable?) {
            if (!closed.compareAndSet(false, true)) return
            if (playbackLease) lease.session.transport.endPlaybackRead()
            if (failure == null) lease.discardSession() else discardAfterFailure(lease, failure, playbackLease)
            runCatching { handle.close() }
            lease.release()
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
