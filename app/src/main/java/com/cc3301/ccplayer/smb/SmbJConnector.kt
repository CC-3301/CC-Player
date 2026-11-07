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
import com.hierynomus.mssmb2.SMB2Packet
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.messages.SMB2Echo
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
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** SMBJ 连接器：只协商 SMB2/3，随机读用按位置读的请求。 */
class SmbJConnector(
    config: SmbConfig = smb2OnlyConfig(),
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    clientFactory: (SmbConfig) -> SMBClient = ::newSmbClient,
) : SmbConnector {

    private val client = clientFactory(config)

    /** 协商结果只记一次：方言与读上限不随重连变化，重复记只是刷日志。 */
    private val negotiateLogged = AtomicBoolean(false)

    /** 建连、认证与打开共享分三段：失败时日志能指出卡在哪一环，会话建立预算也在段之间生效。 */
    override suspend fun connect(
        host: String,
        port: Int,
        share: String,
        username: String,
        password: String?,
    ): SmbConnection {
        val connection = try {
            withContext(Dispatchers.IO) { client.connect(host, port) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw failure.asSourceFailure("建连/协商", "连不上 SMB 服务器 $host:$port")
        }
        return try {
            logNegotiated(connection)
            val session = try {
                withContext(Dispatchers.IO) { connection.authenticate(authenticationOf(username, password)) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                throw failure.asSourceFailure("认证", "SMB 认证失败：$host")
            }
            val diskShare = try {
                withContext(Dispatchers.IO) { session.connectShare(share) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                throw failure.asSourceFailure("打开共享", "打开 SMB 共享失败：$host/$share")
            }
            if (diskShare !is DiskShare) {
                throw MediaSourceException(MediaFailure.NotFound, "共享不是目录共享：$share")
            }
            SmbJConnection(connection, diskShare)
        } catch (failure: Throwable) {
            runCatching { connection.close() }
            throw failure
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
            var connection: Connection? = null
            try {
                connection = client.connect(host, port)
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
                throw failure.asSourceFailure("列举共享", "列不出 SMB 服务器 $host 的共享")
            } finally {
                runCatching { connection?.close() }
            }
        }

    private fun Throwable.asSourceFailure(stage: String, summary: String): MediaSourceException {
        if (this is MediaSourceException) return this
        diagnostics.log {
            "SMB 失败于$stage: ${this::class.java.simpleName}: ${message}"
        }
        return MediaSourceException(smbFailureOf(this), summary, this)
    }

    companion object {
        private const val IPC_SHARE = "IPC$"
        private const val SRVSVC_PIPE = "srvsvc"

        /** TCP 建连上限。 */
        private const val CONNECT_TIMEOUT_SECONDS = 3L

        /** socket 空闲上限：等不到数据即失败，须长于回环层的 3 秒停滞判据。 */
        private const val SOCKET_IDLE_TIMEOUT_SECONDS = 5L

        /** 单次读请求上限：防挂死兜底，不是性能预期。 */
        private const val READ_TIMEOUT_SECONDS = 8L

        /** 只放 SMB2/3 方言并关掉 SMB1 多协议协商；分档超时见上。 */
        fun smb2OnlyConfig(): SmbConfig = SmbConfig.builder()
            .withDialects(
                SMB2Dialect.SMB_2_0_2,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_1_1,
            )
            .withMultiProtocolNegotiate(false)
            .withTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SOCKET_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withReadTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

private class SmbJConnection(
    private val connection: Connection,
    private val share: DiskShare,
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

    override suspend fun ping(timeoutMillis: Long) {
        withContext(Dispatchers.IO) {
            val echo: Future<SMB2Packet> = connection.send(SMB2Echo())
            echo.get(timeoutMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun negotiatedMaxReadSize(): Int =
        runCatching { connection.negotiatedProtocol.maxReadSize }.getOrDefault(0)

    override fun close() {
        runCatching { share.close() }
        runCatching { connection.close() }
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

private fun Throwable.asSourceFailure(summary: String): MediaSourceException =
    if (this is MediaSourceException) this else MediaSourceException(smbFailureOf(this), summary, this)

/** 新建客户端：默认工厂，测试可换成记录关闭的实例。 */
private fun newSmbClient(config: SmbConfig): SMBClient = SMBClient(config)

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
