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
 * 关会话不等于关传输。取用复用的会话前做一次会话级存活确认，失败只作废本次取用的会话并在同一
 * 共享传输上重建，不判共享传输失效；传输失效只由本次取用在这条传输上的实际读写失败判定。
 * 认证、路径与业务失败不作废也不重试。播放读取租约自播放取用开始登记，覆盖存活确认、会话重建、
 * 握手与打开读句柄全程；持有期间列表、封面与探测只处理自己的会话，不换也不关这条传输，
 * 也不把播放正在读的会话从会话表摘除。没有播放读取租约时浏览操作才重建传输。
 * 会话闲置且没有引用时关闭，下次取用时懒重建。
 *
 * 并发建连：两个消费者同时发现会话已死会各自建立，先登记进表的留下、后到的 adopt 它并关掉自己刚建的会话，
 * 因此建连期间同一个键上允许短暂存在两条会话，最终收敛到一条；不为此加运行期检查。
 * 播放读着一条、浏览侧又重建一条时同样并存：浏览侧既不取用播放那条，也不会把它摘掉。
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
    private val transports = SmbTransports(connector, diagnostics, scope)

    /**
     * 按共享复用的会话：主机、端口、共享名与凭据相同即同一个键。
     * 一个键上可以同时有多条：播放读着的那条不交给浏览侧，浏览侧重建自己那条。
     */
    private val sessions = HashMap<SessionKey, MutableList<Session>>()

    /** 播放这一路读的最近一次失败：告诉播放控制层该换传输恢复还是按网络／服务器终态收尾。 */
    private val playbackFailures = SmbPlaybackFailures()

    /** 列出服务器共享名：共享枚举走独立的一次性连接，不占用会话表。 */
    suspend fun shares(host: String, port: Int, username: String, password: String?): List<String> =
        connector.shares(host, port, username, password)

    /** 起播前探测：取到会话即交回，播放随后复用同一条。 */
    suspend fun probe(target: SmbTarget) {
        acquire(target, playbackFlow = false).release()
    }

    /**
     * 借用会话完成一次请求；失败按作废范围重建一次：会话失效只重建会话，本地传输失效连传输一起换掉。
     * 播放持有读取租约时不换也不关共享传输，只作废自己的会话后重发一次。
     * 重发只有一次：第二次失败原样抛出。会话建立失败不重发：建立预算只算一次。
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
                        // 播放持有读取租约：不能换也不能关这条传输，只作废自己的会话后重发一次。
                        diagnostics.log { "SMB 请求失败，播放持有读取租约，只作废本次会话后重发: ${target.share}" }
                        first.discardSession()
                    } else {
                        diagnostics.log { "SMB 请求失败，换传输后重发: ${target.share} (${failure.message})" }
                        first.discardTransport()
                    }
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
     *
     * 播放读取租约在这里就登记，覆盖随后的存活确认、会话重建、握手与打开读句柄；
     * 不是读句柄打开成功后才登记，否则这段窗口里浏览操作会把播放刚建好的传输换掉。
     */
    private suspend fun open(target: SmbTarget, playbackLease: Boolean): ByteSource {
        if (playbackLease) transports.beginPlaybackRead(target.host, target.port)
        return try {
            openSource(target, playbackLease).also {
                // 新的播放读打开成功：上一次失败到此为止，不再参与传输恢复判定。
                if (playbackLease) playbackFailures.clear(target.url)
            }
        } catch (cancellation: CancellationException) {
            if (playbackLease) transports.endPlaybackRead(target.host, target.port)
            throw cancellation
        } catch (failure: Throwable) {
            if (playbackLease) {
                transports.endPlaybackRead(target.host, target.port)
                notePlaybackOpenFailure(target, failure)
            }
            throw failure
        }
    }

    /** 打开读句柄；播放读的读源带上失败分类出口。 */
    private suspend fun openSource(target: SmbTarget, playbackLease: Boolean): ByteSource {
        val session = acquire(target, playbackLease)
        val opened = try {
            ReadLease(session, session.connection.openRead(target.path), playbackLease, target)
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
        return SmbStreamSource(opened, playbackFailureRecorder(target, playbackLease))
    }

    /** 播放读的失败分类出口；浏览与封面的读不记。 */
    private fun playbackFailureRecorder(
        target: SmbTarget,
        playbackLease: Boolean,
    ): ((SmbPlaybackFailure) -> Unit)? =
        if (!playbackLease) {
            null
        } else {
            { failure -> playbackFailures.note(target.url, failure) }
        }

    /**
     * 播放读打开失败的分类：全新传输连不上按网络／服务器故障，其余传输异常按本地 SMB 异常。
     * 认证、路径与业务失败换传输也解决不了，不记。
     */
    private fun notePlaybackOpenFailure(target: SmbTarget, failure: Throwable) {
        val classification = when {
            smbFailureOf(failure) == MediaFailure.Unreachable -> SmbPlaybackFailure.Unreachable
            smbFailureScope(failure) == SmbFailureScope.Transport -> SmbPlaybackFailure.Transport
            else -> return
        }
        playbackFailures.note(target.url, classification)
    }

    /** 播放这一路读最近一次失败的分类；没有未恢复的失败时为空。 */
    fun playbackFailure(location: String): SmbPlaybackFailure? = playbackFailures.classify(location)

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
        val lease = acquire(target, playbackLease)
        return try {
            ReadLease(lease, lease.connection.openRead(target.path), playbackLease, target)
        } catch (cancellation: CancellationException) {
            lease.release()
            throw cancellation
        } catch (failure: Throwable) {
            discardAfterFailure(lease, failure, playbackLease)
            lease.release()
            throw failure
        }
    }

    /** 复用的会话先占一个引用再确认存活；确认失败只作废本次会话并在同一传输上重建。 */
    private suspend fun acquire(target: SmbTarget, playbackFlow: Boolean): SessionLease {
        val key = SessionKey.of(target)
        val reused = reuse(key, playbackFlow)
        if (reused != null) {
            try {
                reused.connection.ping(ALIVE_CHECK_MILLIS)
            } catch (cancellation: CancellationException) {
                endPlaybackRead(reused, playbackFlow)
                release(reused)
                throw cancellation
            } catch (failure: Throwable) {
                return rebuildAfterAliveCheck(key, target, reused, failure, playbackFlow)
            }
            diagnostics.log { "SMB 会话取用: $key (在用 ${reused.inUse})" }
            return SessionLease(reused, playbackFlow)
        }
        return SessionLease(establish(key, target, playbackFlow = playbackFlow), playbackFlow)
    }

    /**
     * 存活确认失败之后的处理，返回重建出来的会话。
     *
     * 存活确认失败本身就是会话不能复用的证据，两种作废范围处理相同：只作废本次取用的会话，
     * 并在同一共享传输上重建会话。先占住这条传输，旧会话交还引用时它不会掉到零，重建的会话继续建在它上面。
     * 确认报文打在共享传输与共享会话上，无法把失败归因到发起方，因此不判共享传输失效、不换传输；
     * 传输失效只由本次取用在这条传输上的实际读写失败判定。认证、路径与业务失败不作废也不重建。
     */
    private suspend fun rebuildAfterAliveCheck(
        key: SessionKey,
        target: SmbTarget,
        reused: Session,
        failure: Throwable,
        playbackFlow: Boolean,
    ): SessionLease = when (smbFailureScope(failure)) {
        SmbFailureScope.None -> {
            endPlaybackRead(reused, playbackFlow)
            release(reused)
            throw failure
        }

        SmbFailureScope.Session, SmbFailureScope.Transport -> {
            val transport = transports.acquire(target.host, target.port)
            // 这次取用自己标在会话上的播放读取先交还：作废的是本次取用。
            endPlaybackRead(reused, playbackFlow)
            invalidate(reused, keepForPlayback = !playbackFlow)
            release(reused)
            diagnostics.log { "SMB 会话存活确认失败，作废会话重建: $key" }
            SessionLease(establish(key, target, transport, playbackFlow), playbackFlow)
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

    /**
     * 从表里挑一条可用的会话并占一个引用。
     * 浏览侧不拿播放正在读的那条：拿它做存活确认，失败就会把它当成自己的作废掉。
     */
    private fun reuse(key: SessionKey, playbackFlow: Boolean): Session? = synchronized(lock) {
        val session = sessions[key]?.lastOrNull { it.usableBy(playbackFlow) } ?: return null
        session.idleJob?.cancel()
        session.idleJob = null
        session.inUse++
        session.lastUsedAt = clock()
        if (playbackFlow) session.beginPlaybackRead()
        session
    }

    private suspend fun establish(
        key: SessionKey,
        target: SmbTarget,
        transport: SmbTransports.TransportLease? = null,
        playbackFlow: Boolean = false,
    ): Session {
        val started = clock()
        val established = establishWithinBudget(target, transport)
        val registration = synchronized(lock) {
            val current = sessions[key]?.lastOrNull { it.usableBy(playbackFlow) }
            val adopted = if (current != null) {
                // 并发建连：留先登记的那条，自己这条没用上。
                current.idleJob?.cancel()
                current.idleJob = null
                current.inUse++
                current.lastUsedAt = clock()
                if (playbackFlow) current.beginPlaybackRead()
                current
            } else {
                val fresh = Session(key, established.connection, established.transport)
                fresh.inUse = 1
                fresh.lastUsedAt = clock()
                if (playbackFlow) fresh.beginPlaybackRead()
                sessions.getOrPut(key) { mutableListOf() } += fresh
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

    /** 句柄关闭或请求结束：计数归零时安排闲置关闭；已作废或已摘掉的会话直接关闭并交还传输。 */
    private fun release(session: Session) {
        val closing = synchronized(lock) {
            session.inUse -= 1
            if (session.inUse != 0) return
            if (session.invalidated || !isListedLocked(session)) {
                removeLocked(session)
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
            if (session.inUse != 0 || session.invalidated || !isListedLocked(session)) return@launch
            removeLocked(session)
            takeSessionLocked(session)
        }
        if (closing) closeSession(session)
        diagnostics.log { "SMB 会话闲置关闭: ${session.key}" }
    }

    /**
     * 作废会话：不再发给新的取用方；引用归零时关闭会话并交还传输。
     * 播放正在读这条会话时，浏览侧的作废不从会话表摘除它，等播放读完再关。
     */
    private fun invalidate(session: Session, keepForPlayback: Boolean) {
        synchronized(lock) {
            session.invalidated = true
            session.idleJob?.cancel()
            session.idleJob = null
            if (!keepForPlayback || session.playbackReads == 0) removeLocked(session)
        }
    }

    /** 会话是否还挂在会话表上。 */
    private fun isListedLocked(session: Session): Boolean =
        sessions[session.key]?.any { it === session } == true

    /** 从会话表摘掉一条会话；这个键上最后一条摘掉时连键一起删。 */
    private fun removeLocked(session: Session) {
        val list = sessions[session.key] ?: return
        list.removeAll { it === session }
        if (list.isEmpty()) sessions.remove(session.key)
    }

    /** 交还这条取用自己标上的播放读取；不是播放读的取用不动计数。 */
    private fun endPlaybackRead(session: Session, playbackFlow: Boolean) {
        if (!playbackFlow) return
        synchronized(lock) { session.endPlaybackRead() }
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
        while (sessions.values.sumOf { it.size } > maxSessions) {
            val oldest = sessions.entries
                .filter { entry -> entry.key != exclude }
                .flatMap { entry -> entry.value }
                .filter { session -> session.inUse == 0 }
                .minByOrNull { session -> session.lastUsedAt }
                ?: return evicted
            removeLocked(oldest)
            if (takeSessionLocked(oldest)) evicted += oldest
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

        /** 播放读取引用数；不为 0 时浏览侧不取用这条会话，也不把它从会话表摘除。 */
        var playbackReads = 0

        /** 最近一次取用或归零的时刻。 */
        var lastUsedAt = 0L

        /** 等待中的闲置关闭任务；重新取用时取消。 */
        var idleJob: Job? = null

        /** 会话已交出去关闭；重复丢弃不再关第二次。 */
        var taken = false

        /** 已作废：不再发给新的取用方，引用归零就关。 */
        var invalidated = false

        /** 这条会话能否交给这次取用：已作废的不行，浏览侧还要避开播放正在读的那条。 */
        fun usableBy(playbackFlow: Boolean): Boolean = !invalidated && (playbackFlow || playbackReads == 0)

        /** 播放读取登记；只在会话层的锁内调用。 */
        fun beginPlaybackRead() {
            playbackReads += 1
        }

        /** 播放读取交还；只在会话层的锁内调用。 */
        fun endPlaybackRead() {
            playbackReads -= 1
        }
    }

    /** 一次取用：占用会话与它下面的传输直到释放。 */
    private inner class SessionLease(val session: Session, private val playbackFlow: Boolean) {

        val connection: SmbConnection get() = session.connection

        private val released = AtomicBoolean(false)

        private val playbackEnded = AtomicBoolean(false)

        /**
         * 本次取用的播放读取标记只交还一次：作废路径先交还，随后的释放不再重复减。
         * 标记交还之后，会话才可能被当作"没有播放在读"作废或关闭。
         */
        private fun endPlaybackRead() {
            if (!playbackEnded.compareAndSet(false, true)) return
            this@SmbSessions.endPlaybackRead(session, playbackFlow)
        }

        /** 会话失效：不再发给新的取用方；播放正在读的那条由 invalidate 决定留不留在表里。 */
        fun discardSession() {
            endPlaybackRead()
            invalidate(session, keepForPlayback = !playbackFlow)
        }

        /** 本地传输失效：会话与这条传输一起作废，下一次取用建新传输。 */
        fun discardTransport() {
            endPlaybackRead()
            invalidate(session, keepForPlayback = !playbackFlow)
            session.transport.discard()
        }

        /** 本地传输失效时能否换掉这条传输。 */
        fun mayReplaceTransport(playbackFlow: Boolean): Boolean =
            this@SmbSessions.mayReplaceTransport(session, playbackFlow)

        fun release() {
            if (!released.compareAndSet(false, true)) return
            endPlaybackRead()
            this@SmbSessions.release(session)
        }
    }

    /**
     * 交给读源的一次打开：句柄、会话占用与（播放读的）读取租约。
     * 播放读取租约在取用开始时已经登记，这里只在关闭或作废时交还。
     */
    private inner class ReadLease(
        private val lease: SessionLease,
        override val handle: SmbReadHandle,
        private val playbackLease: Boolean,
        private val target: SmbTarget,
    ) : SmbReadLease {

        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            if (playbackLease) transports.endPlaybackRead(target.host, target.port)
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
            if (playbackLease) transports.endPlaybackRead(target.host, target.port)
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
