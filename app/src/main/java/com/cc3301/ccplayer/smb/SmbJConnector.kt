package com.cc3301.ccplayer.smb

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

/** SMBJ 连接器：只协商 SMB2/3，随机读用按位置读的请求。 */
class SmbJConnector(private val config: SmbConfig = smb2OnlyConfig()) : SmbConnector {

    private val client = SMBClient(config)

    override suspend fun connect(
        host: String,
        port: Int,
        share: String,
        username: String,
        password: String?,
    ): SmbConnection = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        try {
            connection = client.connect(host, port)
            val session = connection.authenticate(authenticationOf(username, password))
            val diskShare = session.connectShare(share)
            if (diskShare !is DiskShare) {
                throw MediaSourceException(MediaFailure.NotFound, "共享不是目录共享：$share")
            }
            // 所有权交给调用方，由它关闭。
            SmbJConnection(connection, diskShare)
        } catch (cancellation: CancellationException) {
            runCatching { connection?.close() }
            throw cancellation
        } catch (failure: Exception) {
            runCatching { connection?.close() }
            throw failure.asSourceFailure("连不上 SMB 共享 $host/$share")
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
                throw failure.asSourceFailure("列不出 SMB 服务器 $host 的共享")
            } finally {
                runCatching { connection?.close() }
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
            // 读尺寸按协商上限给出，避免超出后由底层拆成多次 READ。
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
