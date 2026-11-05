package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.streaming.ByteSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** 同时保留的 SMB 会话上限；超出时关掉最久未用且没有句柄在用的会话。 */
private const val MAX_SESSIONS = 8

/** 会话闲置上限：超过该时长没有句柄在用时关闭，下次读取重新建连。 */
private const val SESSION_IDLE_MILLIS = 2 * 60 * 1000L

/** 连续打开失败达到该次数时重建客户端以清掉可能已失效的连接。 */
private const val MAX_OPEN_FAILURES = 3

/** 失败计数的时间窗：距上一次失败超过该时长，计数从头开始。 */
private const val OPEN_FAILURE_WINDOW_MILLIS = 60 * 1000L

/** 建连期间客户端被作废时，用新客户端重试的次数。 */
private const val CONNECT_ATTEMPTS = 2

/**
 * SMB 预览读取：按书签连上共享后打开随机读句柄，不经过回环代理。
 *
 * 同一共享的会话复用：抽帧只为条目打开文件句柄，句柄关闭不关会话，下一条目沿用同一会话。
 * 打开失败一律丢弃该会话；读取遇连接类失败、取消或超时打断在途传输读时丢弃，连接在最后一个句柄关闭时关闭；
 * 闲置超时或超出上限时关闭，且只在没有句柄在用时关闭。
 * 同一会话键连续打开失败到上限时作废全部会话并重建客户端，业务类失败不计入。
 */
class SmbMediaReads(
    private val connector: SmbConnector,
    private val locator: SmbLocator,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSessions: Int = MAX_SESSIONS,
    private val idleMillis: Long = SESSION_IDLE_MILLIS,
) : NetworkMediaReads {

    private val lock = Any()

    /** 按共享复用的会话，记录最近取用时间与在用句柄数；主机、端口、共享名与凭据相同即同一会话。 */
    private val sessions = HashMap<SessionKey, Session>()

    /** 按会话键记的连续打开失败次数与最近一次失败时刻；成功一次或超出时间窗即清零。 */
    private val openFailures = HashMap<SessionKey, FailureCount>()

    override suspend fun openRead(location: String, size: Long): ByteSource {
        val target = locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
        val key = SessionKey.of(target)
        val session = try {
            sessionFor(key, target)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            recordOpenFailure(key, failure)
            throw failure
        }
        return try {
            val handle = session.connection.openRead(target.path)
            // 成功一次即清零：计数只看连续失败。
            forgetOpenFailures(key)
            SmbSessionSource(
                handle = handle,
                onClose = { release(session) },
                onFailure = { drop(key, session) },
            )
        } catch (cancellation: CancellationException) {
            // 打开被放弃（超时或取消）：会话一并作废，下一次打开必须新建，不能复用。
            drop(key, session)
            release(session)
            throw cancellation
        } catch (failure: Exception) {
            // 打开失败一律丢弃会话：重试必须换新会话，不复用刚失败的会话。
            drop(key, session)
            release(session)
            recordOpenFailure(key, failure)
            throw failure
        }
    }

    /**
     * 记一次连接类失败；同一会话键连续失败到上限时作废全部会话并重建客户端。
     * 业务类失败（文件不存在、权限不足等）不计入：否则删几个条目就会重建客户端、连带关掉正常连接。
     * 作废会话与重建都在锁外收尾：关闭旧客户端可能阻塞在网络操作上。
     */
    private fun recordOpenFailure(key: SessionKey, failure: Throwable) {
        if (!smbConnectionLost(failure)) return
        val now = clock()
        val reached = synchronized(lock) {
            // 清掉过期的计数：偶尔失败一次的键不会长期留在表里。
            openFailures.entries.removeAll { (_, count) -> now - count.lastAt > OPEN_FAILURE_WINDOW_MILLIS }
            val count = (openFailures[key]?.count ?: 0) + 1
            if (count < MAX_OPEN_FAILURES) {
                openFailures[key] = FailureCount(count, now)
                false
            } else {
                // 重建后旧计数一律作废：新客户端不会立刻被旧计数再触发一次重建。
                openFailures.clear()
                true
            }
        }
        if (!reached) return
        // 旧客户端上的会话随客户端一起作废：先摘表再重建，下一次打开不会复用已失效的连接。
        val stale = detachAllSessions()
        connector.reset()
        stale.forEach(::close)
    }

    private fun forgetOpenFailures(key: SessionKey) {
        synchronized(lock) { openFailures.remove(key) }
    }

    /** 作废全部会话，交出没有句柄在用的连接；还有句柄在用的留给最后一个句柄关闭时收尾。 */
    private fun detachAllSessions(): List<SmbConnection> {
        val closeable = ArrayList<SmbConnection>()
        synchronized(lock) {
            sessions.values.forEach { session ->
                if (session.inUse == 0) {
                    takeConnectionLocked(session)?.let { closeable += it }
                } else {
                    session.detached = true
                }
            }
            sessions.clear()
        }
        return closeable
    }

    /** 取该共享可用的会话并占一个句柄；闲置超时且没有句柄在用、或没有会话时新建。 */
    private suspend fun sessionFor(key: SessionKey, target: SmbTarget): Session {
        val now = clock()
        val stale = ArrayList<SmbConnection>()
        synchronized(lock) {
            val existing = sessions[key]
            if (existing != null) {
                if (now - existing.lastUsedAt > idleMillis && existing.inUse == 0) {
                    sessions.remove(key)
                    takeConnectionLocked(existing)?.let { stale += it }
                } else {
                    existing.lastUsedAt = now
                    existing.inUse++
                    return existing
                }
            }
        }
        stale.forEach(::close)
        repeat(CONNECT_ATTEMPTS) {
            val generation = connector.generation
            val created = connector.connect(
                host = target.host,
                port = target.port,
                share = target.share,
                username = target.username,
                password = target.password,
            )
            val unused = ArrayList<SmbConnection>()
            val session = synchronized(lock) {
                // 建连期间客户端被作废：这条连接属于旧客户端，不登记，改用新客户端重来。
                if (connector.generation != generation) {
                    null
                } else {
                    val current = sessions[key]
                    if (current != null) {
                        // 并发建连：留先登记的那条，自己这条没用上。
                        current.lastUsedAt = now
                        current.inUse++
                        unused += created
                        current
                    } else {
                        val fresh = Session(created, now)
                        fresh.inUse = 1
                        sessions[key] = fresh
                        unused += evictBeyondLimitLocked(exclude = key)
                        fresh
                    }
                }
            }
            if (session == null) {
                close(created)
                return@repeat
            }
            unused.forEach(::close)
            return session
        }
        throw MediaSourceException(MediaFailure.Unreachable, "SMB 客户端连续作废，建连未完成")
    }

    /** 句柄关闭：计数归零时，已被摘掉的会话才真正关闭连接。 */
    private fun release(session: Session) {
        val closeable = synchronized(lock) {
            session.inUse = (session.inUse - 1).coerceAtLeast(0)
            if (session.inUse == 0 && session.detached) takeConnectionLocked(session) else null
        }
        closeable?.let(::close)
    }

    /** 摘掉会话；还有句柄在用时留给最后一个句柄关闭时收尾，不在这里关。 */
    private fun drop(key: SessionKey, session: Session) {
        val closeable = synchronized(lock) {
            if (sessions[key] === session) sessions.remove(key)
            if (session.inUse == 0) {
                takeConnectionLocked(session)
            } else {
                session.detached = true
                null
            }
        }
        closeable?.let(::close)
    }

    /**
     * 交出发连接用于关闭；同一条会话只交一次。
     * 释放、丢弃、淘汰与整体作废都走这里，避免同一条连接被重复关闭。
     */
    private fun takeConnectionLocked(session: Session): SmbConnection? {
        if (session.dropped) return null
        session.dropped = true
        session.detached = false
        return session.connection
    }

    /**
     * 会话表超过上限时摘出最久未用且没有句柄在用的会话，当前这条永不入选，由调用方在锁外关闭。
     * 全都在用时宁可暂时超出上限，也不关正在读的会话。
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

    private fun close(connection: SmbConnection) {
        runCatching { connection.close() }
    }

    /** 连续失败计数：次数与最近一次失败时刻。 */
    private data class FailureCount(val count: Int, val lastAt: Long)

    /** 会话的登记：连接、最近一次取用的时刻、在用句柄数。 */
    private class Session(val connection: SmbConnection, var lastUsedAt: Long) {

        /** 还在用的读句柄数；不为 0 时不得关闭。 */
        var inUse = 0

        /** 已从表里摘掉但还有句柄在用，等最后一个句柄关闭时再关连接。 */
        var detached = false

        /** 连接已安排关闭或已关闭；重复丢弃不再关第二次。 */
        var dropped = false
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

/** 复用会话的随机读句柄：关闭只关文件句柄并交还会话占用；读取失败时由调用方丢弃会话。 */
private class SmbSessionSource(
    private val handle: SmbReadHandle,
    private val onClose: () -> Unit,
    private val onFailure: () -> Unit,
) : ByteSource {

    private val closed = AtomicBoolean(false)

    override val size: Long get() = handle.size

    override val maxReadBytes: Int get() = handle.maxReadSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = try {
        handle.readAt(position, buffer, offset, length)
    } catch (failure: Throwable) {
        // 只丢弃连接类失败：业务失败与参数错误不动会话，避免为它们白建一次连。
        if (smbConnectionLost(failure)) onFailure()
        throw failure
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { handle.close() }
        onClose()
    }

    /**
     * 作废这次读取：先丢弃会话，再关句柄。
     *
     * 会话先从表里摘掉，下一次读取不会复用它；句柄还有别的读取在用时，
     * 由最后一个句柄关闭时收尾连接。
     */
    override fun discard() {
        onFailure()
        close()
    }
}
