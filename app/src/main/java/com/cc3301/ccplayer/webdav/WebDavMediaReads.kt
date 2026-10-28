package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.streaming.ByteSource
import java.util.concurrent.ConcurrentHashMap

/**
 * WebDAV 预览读取：按书签取到访问参数后打开随机读句柄，不经过回环代理。
 *
 * 文件大小取自目录列表，打开句柄时不发请求；句柄按需发带 Range 的 GET，关闭句柄即关闭响应。
 * 服务器忽略区间请求时按网络源（书签地址加账号）记下结论，该源的后续读取直接失败不再发请求；
 * 同一主机上的其它书签不受影响。
 */
class WebDavMediaReads(
    private val transport: WebDavTransport,
    private val locator: WebDavLocator,
) : NetworkMediaReads {

    /** 已确认忽略区间请求的网络源；键为书签地址加账号。 */
    private val rangeUnsupported = ConcurrentHashMap.newKeySet<String>()

    override suspend fun openRead(location: String, size: Long): ByteSource {
        val target = locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
        val sourceKey = sourceKeyOf(target)
        if (size > 0 && sourceKey in rangeUnsupported) {
            throw WebDavRangeUnsupportedException("服务器忽略了区间请求：$location")
        }
        val handle = transport.openRead(target.url, target.username, target.password, size)
        return RangeCheckedSource(handle) { rangeUnsupported += sourceKey }
    }

    /** 网络源的身份：书签地址加账号；不同书签各记各的结论。 */
    private fun sourceKeyOf(target: WebDavTarget): String = "${target.root}|${target.username}"
}

/** 记录区间请求被忽略的读句柄：结论只在该网络源上生效。 */
private class RangeCheckedSource(
    private val origin: ByteSource,
    private val onRangeIgnored: () -> Unit,
) : ByteSource {

    override val size: Long get() = origin.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = try {
        origin.readAt(position, buffer, offset, length)
    } catch (failure: WebDavRangeUnsupportedException) {
        onRangeIgnored()
        throw failure
    }

    override fun close() {
        origin.close()
    }
}
