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
private const val SESSION_IDLE_MILLIS = 10 * 60 * 1000L

/**
 * SMB 预览读取：按书签连上共享后打开随机读句柄，不经过回环代理。
 *
 * 同一共享的会话复用：抽帧只为条目打开文件句柄，句柄关闭不关会话，下一条目沿用同一会话。
 * 打开或读取失败时丢弃该会话；闲置超时或超出上限时才会关闭，且只在没有句柄在用时关闭。
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

    override suspend fun openRead(location: String, size: Long): ByteSource {
        val target = locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
        val key = SessionKey.of(target)
        val session = sessionFor(key, target)
        return try {
            SmbSessionSource(
                handle = session.connection.openRead(target.path),
                onClose = { release(session) },
                onFailure = { drop(key, session) },
            )
        } catch (cancellation: CancellationException) {
            release(session)
            throw cancellation
        } catch (failure: Exception) {
            drop(key, session)
            release(session)
            throw failure
        }
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
                    stale += existing.connection
                } else {
                    existing.lastUsedAt = now
                    existing.inUse++
                    return existing
                }
            }
        }
        stale.forEach(::close)
        val created = connector.connect(
            host = target.host,
            port = target.port,
            share = target.share,
            username = target.username,
            password = target.password,
        )
        val unused = ArrayList<SmbConnection>()
        val session = synchronized(lock) {
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
        unused.forEach(::close)
        return session
    }

    /** 句柄关闭：计数归零时，已被摘掉的会话才真正关闭连接。 */
    private fun release(session: Session) {
        val closeable = synchronized(lock) {
            session.inUse = (session.inUse - 1).coerceAtLeast(0)
            if (session.inUse == 0 && session.detached) {
                session.detached = false
                session.connection
            } else {
                null
            }
        }
        closeable?.let(::close)
    }

    /** 摘掉会话；还有句柄在用时留给最后一个句柄关闭时收尾，不在这里关。 */
    private fun drop(key: SessionKey, session: Session) {
        val closeable = synchronized(lock) {
            if (sessions[key] === session) sessions.remove(key)
            if (session.inUse == 0) {
                session.detached = false
                session.connection
            } else {
                session.detached = true
                null
            }
        }
        closeable?.let(::close)
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
            evicted += oldest.value.connection
        }
        return evicted
    }

    private fun close(connection: SmbConnection) {
        runCatching { connection.close() }
    }

    /** 会话的登记：连接、最近一次取用的时刻、在用句柄数。 */
    private class Session(val connection: SmbConnection, var lastUsedAt: Long) {

        /** 还在用的读句柄数；不为 0 时不得关闭。 */
        var inUse = 0

        /** 已从表里摘掉但还有句柄在用，等最后一个句柄关闭时再关连接。 */
        var detached = false
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
    } catch (failure: IOException) {
        onFailure()
        throw failure
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { handle.close() }
        onClose()
    }
}
