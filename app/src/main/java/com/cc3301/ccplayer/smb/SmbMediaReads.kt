package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.streaming.ByteSource
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** 同时保留的 SMB 会话上限；超出时关掉最久未用的，避免长期占用服务器会话。 */
private const val MAX_SESSIONS = 8

/** 会话闲置上限：超过该时长未再取用就关闭，下次读取重新建连。 */
private const val SESSION_IDLE_MILLIS = 10 * 60 * 1000L

/**
 * SMB 预览读取：按书签连上共享后打开随机读句柄，不经过回环代理。
 *
 * 同一共享的会话复用：抽帧只为条目打开文件句柄，句柄关闭不关会话，下一条目沿用同一会话。
 * 打开或读取失败时丢弃该会话；会话闲置超过上限或表满时也会被关掉，下次读取重新建连。
 */
class SmbMediaReads(
    private val connector: SmbConnector,
    private val locator: SmbLocator,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSessions: Int = MAX_SESSIONS,
    private val idleMillis: Long = SESSION_IDLE_MILLIS,
) : NetworkMediaReads {

    private val lock = Any()

    /** 按共享复用的会话，记录最近取用时间用于淘汰；主机、端口、共享名与凭据相同即同一会话。 */
    private val sessions = HashMap<SessionKey, Session>()

    override suspend fun openRead(location: String, size: Long): ByteSource {
        val target = locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
        val key = SessionKey.of(target)
        val connection = sessionFor(key, target)
        return try {
            SmbSessionSource(handle = connection.openRead(target.path)) { drop(key, connection) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            drop(key, connection)
            throw failure
        }
    }

    /** 取该共享可用的会话；闲置超时或没有时新建，并发建连只留第一条。 */
    private suspend fun sessionFor(key: SessionKey, target: SmbTarget): SmbConnection {
        val now = clock()
        val expired = synchronized(lock) {
            val existing = sessions[key] ?: return@synchronized null
            if (now - existing.lastUsedAt > idleMillis) {
                sessions.remove(key)
                existing
            } else {
                existing.lastUsedAt = now
                return existing.connection
            }
        }
        expired?.let { session -> close(session.connection) }
        val created = connector.connect(
            host = target.host,
            port = target.port,
            share = target.share,
            username = target.username,
            password = target.password,
        )
        val evicted = ArrayList<SmbConnection>()
        val existing = synchronized(lock) {
            val current = sessions[key]
            if (current != null) {
                current.lastUsedAt = now
                current.connection
            } else {
                sessions[key] = Session(created, now)
                evicted += evictBeyondLimitLocked()
                created
            }
        }
        if (existing !== created) close(created)
        evicted.forEach(::close)
        return existing
    }

    /** 丢弃会话；当前登记的还是这一条时才关闭，避免关掉并发建好的新会话。 */
    private fun drop(key: SessionKey, connection: SmbConnection) {
        val removed = synchronized(lock) {
            if (sessions[key]?.connection === connection) sessions.remove(key)?.connection else null
        }
        removed?.let(::close)
    }

    /** 会话表超过上限时摘出最久未用的一条，由调用方在锁外关闭。 */
    private fun evictBeyondLimitLocked(): List<SmbConnection> {
        if (sessions.size <= maxSessions) return emptyList()
        val oldest = sessions.entries.minByOrNull { entry -> entry.value.lastUsedAt } ?: return emptyList()
        sessions.remove(oldest.key)
        return listOf(oldest.value.connection)
    }

    private fun close(connection: SmbConnection) {
        runCatching { connection.close() }
    }

    /** 会话的登记：连接与最近一次取用的时刻。 */
    private class Session(val connection: SmbConnection, var lastUsedAt: Long)

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

/** 复用会话的随机读句柄：关闭只关文件句柄，会话留给下一条目；读取失败时由调用方丢弃会话。 */
private class SmbSessionSource(
    private val handle: SmbReadHandle,
    private val onFailure: () -> Unit,
) : ByteSource {

    override val size: Long get() = handle.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = try {
        handle.readAt(position, buffer, offset, length)
    } catch (failure: IOException) {
        onFailure()
        throw failure
    }

    override fun close() {
        runCatching { handle.close() }
    }
}
