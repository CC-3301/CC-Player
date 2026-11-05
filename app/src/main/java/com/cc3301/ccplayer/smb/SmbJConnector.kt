package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.share.PipeShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** SMBJ 连接器：只协商 SMB2/3，随机读用按位置读的请求。 */
class SmbJConnector(
    config: SmbConfig = smb2OnlyConfig(),
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    clientFactory: (SmbConfig) -> SMBClient = ::newSmbClient,
    clock: () -> Long = System::currentTimeMillis,
    condemnedTimeoutMillis: Long = CONDEMNED_TIMEOUT_MILLIS,
) : SmbConnector {

    private val clients = SmbClientPool(clientFactory, config, clock, condemnedTimeoutMillis)

    /** 协商结果只记一次：同一条连接的方言与读上限不随重连变化，重复记只是刷日志。 */
    private val negotiateLogged = AtomicBoolean(false)

    override val generation: Long get() = clients.generation

    override fun reset() {
        // 先取代数再进锁比对：并发作废时只有真正换掉这条客户端的一次才动手，不误关别人刚换上的新实例。
        if (clients.condemn(clients.generation) == null) return
        clients.sweep()
        diagnostics.log { "SMB 客户端作废: 换成新实例" }
    }

    override suspend fun connect(
        host: String,
        port: Int,
        share: String,
        username: String,
        password: String?,
    ): SmbConnection = withContext(Dispatchers.IO) {
        // 先占一个连接名额：客户端在用到一半时不会被作废流程关掉。
        val slot = clients.acquire()
        var connection: Connection? = null
        try {
            val opened = slot.client.connect(host, port)
            connection = opened
            logNegotiated(opened)
            val session = opened.authenticate(authenticationOf(username, password))
            val diskShare = session.connectShare(share)
            if (diskShare !is DiskShare) {
                throw MediaSourceException(MediaFailure.NotFound, "共享不是目录共享：$share")
            }
            // 所有权交给调用方，关闭时归还名额。
            SmbJConnection(connection, diskShare) { clients.release(slot) }
        } catch (cancellation: CancellationException) {
            runCatching { connection?.close() }
            clients.release(slot)
            throw cancellation
        } catch (failure: Exception) {
            runCatching { connection?.close() }
            clients.release(slot)
            throw failure.asSourceFailure("连不上 SMB 共享 $host/$share")
        }
    }

    /** 协商事实：方言决定最大读尺寸，SMB2.0.2 只有 64 KiB，块会退化为 64 KiB。 */
    private fun logNegotiated(connection: Connection) {
        if (!negotiateLogged.compareAndSet(false, true)) return
        runCatching { connection.negotiatedProtocol }.onSuccess { negotiated ->
            diagnostics.log {
                "SMB 建连: 方言 ${negotiated.dialect} maxReadSize ${negotiated.maxReadSize}"
            }
        }
    }

    private fun authenticationOf(username: String, password: String?): AuthenticationContext =
        if (username.isEmpty()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(username, password.orEmpty().toCharArray(), null)
        }

    /** 共享枚举走 IPC$ 上的 srvsvc 命名管道；SMBJ 没有现成接口，报文由 [SrvsvcShareEnum] 编解码。 */
    override suspend fun shares(host: String, port: Int, username: String, password: String?): List<String> =
        withContext(Dispatchers.IO) {
            val slot = clients.acquire()
            var connection: Connection? = null
            try {
                connection = slot.client.connect(host, port)
                val session = connection.authenticate(authenticationOf(username, password))
                val ipc = session.connectShare(IPC_SHARE)
                if (ipc !is PipeShare) {
                    throw MediaSourceException(MediaFailure.Unknown, "服务器没有提供 $IPC_SHARE")
                }
                ipc.use { share ->
                    share.open(
                        SRVSVC_PIPE,
                        SMB2ImpersonationLevel.Impersonation,
                        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    ).use { pipe ->
                        SrvsvcShareEnum.ensureBound(pipe.transact(SrvsvcShareEnum.bindRequest()))
                        SrvsvcShareEnum.parseShares(pipe.transact(SrvsvcShareEnum.shareEnumRequest()))
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                throw failure.asSourceFailure("列不出 SMB 服务器 $host 的共享")
            } finally {
                runCatching { connection?.close() }
                clients.release(slot)
            }
        }

    companion object {
        private const val IPC_SHARE = "IPC$"
        private const val SRVSVC_PIPE = "srvsvc"

        /** 只放 SMB2/3 方言并关掉 SMB1 多协议协商；超时避免连不上时长时间挂起。 */
        fun smb2OnlyConfig(): SmbConfig = SmbConfig.builder()
            .withDialects(
                SMB2Dialect.SMB_2_0_2,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_1_1,
            )
            .withMultiProtocolNegotiate(false)
            .withSoTimeout(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withTimeout(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        private const val OPERATION_TIMEOUT_SECONDS = 30L
    }
}

private class SmbJConnection(
    private val connection: Connection,
    private val share: DiskShare,
    private val onClosed: () -> Unit,
) : SmbConnection {

    override suspend fun list(path: String): List<SmbEntry> = withContext(Dispatchers.IO) {
        try {
            share.list(path).mapNotNull { directoryEntry -> directoryEntry.toSmbEntry() }
        } catch (failure: Exception) {
            throw failure.asSourceFailure("读不了共享目录：$path")
        }
    }

    override suspend fun openRead(path: String): SmbReadHandle = withContext(Dispatchers.IO) {
        val file = try {
            share.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        } catch (failure: Exception) {
            throw failure.asSourceFailure("打不开共享内文件：$path")
        }
        try {
            // 读尺寸按协商上限给出：底层不会按协商上限自动切分，超限的单次读会被服务端拒绝。
            SmbJReadHandle(file, maxReadSize = negotiatedMaxReadSize())
        } catch (failure: Exception) {
            runCatching { file.close() }
            throw failure.asSourceFailure("打不开共享内文件：$path")
        }
    }

    private fun negotiatedMaxReadSize(): Int =
        runCatching { connection.negotiatedProtocol.maxReadSize }.getOrDefault(0)

    override fun close() {
        runCatching { share.close() }
        runCatching { connection.close() }
        onClosed()
    }
}

private class SmbJReadHandle(
    private val file: File,
    override val maxReadSize: Int = 0,
) : SmbReadHandle {

    private val lock = Any()

    override val size: Long = file.length

    /** 同一句柄可能被多个连接并发读，读请求串行发出。 */
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        val read = file.read(buffer, position, offset, length)
        if (read < 0) 0 else read
    }

    override fun close() {
        runCatching { file.close() }
    }
}

/** 新建客户端：默认工厂，测试可换成记录关闭的实例。 */
private fun newSmbClient(config: SmbConfig): SMBClient = SMBClient(config)

/** 作废的客户端兜底关闭时长：没人归还名额时也不长期留着连接。 */
private const val CONDEMNED_TIMEOUT_MILLIS = 60 * 1000L

/**
 * SMB 客户端登记：当前客户端供新连接使用，作废的客户端等在用连接归零或兜底超时再关闭。
 * 立刻关闭会掐断仍在读的句柄，所以作废只换实例、不马上关旧的。
 */
internal class SmbClientPool(
    private val factory: (SmbConfig) -> SMBClient,
    private val config: SmbConfig,
    private val clock: () -> Long,
    private val condemnedTimeoutMillis: Long,
) {

    private val lock = Any()

    /** 当前客户端，新连接都走它。 */
    private var current = SmbClientSlot(factory(config))

    /** 已作废、等在用连接归零或兜底超时的客户端。 */
    private val condemned = ArrayList<SmbClientSlot>()

    @Volatile
    private var currentGeneration = 0L

    /** 当前客户端代数：每次作废加一。 */
    val generation: Long get() = currentGeneration

    /** 取当前客户端并占一个连接名额；归还时释放。 */
    fun acquire(): SmbClientSlot = synchronized(lock) {
        current.liveConnections++
        current
    }

    /**
     * 作废代数仍为 [observed] 的当前客户端并换新实例；代数已变说明别的调用先作废过，
     * 不再换新，避免把刚换上的客户端又作废掉。返回被作废的客户端，没作废时返回 null。
     */
    fun condemn(observed: Long): SmbClientSlot? {
        val stale = synchronized(lock) {
            if (currentGeneration != observed) return null
            val previous = current
            previous.condemned = true
            previous.condemnedAt = clock()
            condemned += previous
            current = SmbClientSlot(factory(config))
            currentGeneration = observed + 1
            previous
        }
        closeIfUnused(stale)
        return stale
    }

    /** 连接关闭：名额归还；作废且归零时关闭这条客户端。 */
    fun release(slot: SmbClientSlot) {
        synchronized(lock) { slot.liveConnections = (slot.liveConnections - 1).coerceAtLeast(0) }
        closeIfUnused(slot)
    }

    /** 兜底：作废后长时间没人归还名额的客户端强制关闭，避免连接泄漏。 */
    fun sweep() {
        val now = clock()
        val expired = synchronized(lock) {
            val stale = condemned.filter { now - it.condemnedAt > condemnedTimeoutMillis }
            condemned.removeAll(stale)
            stale
        }
        expired.forEach(::closeClient)
    }

    private fun closeIfUnused(slot: SmbClientSlot) {
        val closeable = synchronized(lock) {
            if (!slot.condemned || slot.liveConnections > 0) return
            condemned.remove(slot)
            slot
        }
        closeClient(closeable)
    }

    private fun closeClient(slot: SmbClientSlot) {
        runCatching { slot.client.close() }
    }
}

/** 一条客户端的登记：客户端实例、在用连接数与作废时刻。 */
internal class SmbClientSlot(val client: SMBClient) {

    /** 还在用的连接数；不为 0 时不得关闭客户端。 */
    var liveConnections = 0

    /** 已作废、等名额归零或兜底超时后关闭。 */
    var condemned = false

    /** 作废时刻，用于兜底超时。 */
    var condemnedAt = 0L
}

private fun Throwable.asSourceFailure(message: String): MediaSourceException =
    if (this is MediaSourceException) this else MediaSourceException(smbFailureOf(this), message, this)

private fun FileIdBothDirectoryInformation.toSmbEntry(): SmbEntry? {
    val name = fileName
    if (name.isNullOrEmpty() || name == "." || name == "..") return null
    return SmbEntry(
        name = name,
        isDirectory = fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
        size = endOfFile,
        lastModified = lastWriteTime.toEpochMillis(),
    )
}
