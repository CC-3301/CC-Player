package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.streaming.ByteSource
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * SMB 预览读取：按书签连上共享后打开随机读句柄，不经过回环代理。
 *
 * 同一共享的会话复用：抽帧只为条目打开文件句柄，句柄关闭不关会话，下一条目沿用同一会话。
 * 打开或读取失败时丢弃该会话，下次读取重新建连。
 */
class SmbMediaReads(
    private val connector: SmbConnector,
    private val locator: SmbLocator,
) : NetworkMediaReads {

    private val lock = Any()

    /** 按共享复用的会话；主机、端口、共享名与凭据相同即同一会话。 */
    private val sessions = HashMap<SessionKey, SmbConnection>()

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

    /** 取该共享已有的会话；没有就建一条，并发建连只留第一条。 */
    private suspend fun sessionFor(key: SessionKey, target: SmbTarget): SmbConnection {
        synchronized(lock) { sessions[key] }?.let { return it }
        val created = connector.connect(
            host = target.host,
            port = target.port,
            share = target.share,
            username = target.username,
            password = target.password,
        )
        val existing = synchronized(lock) {
            sessions[key] ?: created.also { sessions[key] = it }
        }
        if (existing !== created) {
            runCatching { created.close() }
        }
        return existing
    }

    /** 丢弃会话；当前登记的还是这一条时才关闭，避免关掉并发建好的新会话。 */
    private fun drop(key: SessionKey, connection: SmbConnection) {
        val removed = synchronized(lock) {
            if (sessions[key] === connection) sessions.remove(key) else null
        }
        if (removed != null) runCatching { removed.close() }
    }

    private data class SessionKey(
        val host: String,
        val port: Int,
        val share: String,
        val username: String,
        val password: String?,
    ) {
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
