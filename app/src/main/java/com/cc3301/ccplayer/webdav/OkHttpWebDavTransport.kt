package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OkHttp 实现：PROPFIND 列目录，带 Range 的 GET 随机读。
 *
 * 读文件时位置连续就沿用同一个响应流，跳读才按新起点重发 GET。
 * 已知文件大小（抽帧读取）时打开句柄不发请求，并要求服务器支持区间读；
 * 服务器忽略 Range 时按源记录结论，同一源的后续读取直接失败，不再发请求。
 */
class OkHttpWebDavTransport(private val client: OkHttpClient = defaultClient()) : WebDavTransport {

    /** 已确认忽略区间请求的源，按 scheme://host:port 记录；这些源不再发抽取请求。 */
    private val rangeUnsupported = ConcurrentHashMap.newKeySet<String>()

    override suspend fun list(url: String, username: String, password: String?): List<WebDavEntry> = withContext(Dispatchers.IO) {
        // PROPFIND 的目录地址统一以 `/` 结尾（ADR-0002），不依赖重定向处理。
        val directoryUrl = if (url.endsWith('/')) url else "$url/"
        val request = requestOf(directoryUrl, username, password)
            .method(METHOD_PROPFIND, PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE.toMediaType()))
            .header(DEPTH_HEADER, DEPTH_CHILDREN)
            .build()
        val body = execute(request).use { response ->
            if (!response.isSuccessful) throw statusFailure(response.code, directoryUrl)
            response.body?.string().orEmpty()
        }
        parsePropfind(body, directoryUrl)
    }

    override suspend fun openRead(url: String, username: String, password: String?, size: Long): WebDavReadHandle = withContext(Dispatchers.IO) {
        val calls = ReadCallTracker()
        try {
            coroutineScope {
                val opening = async { openWithCalls(calls, url, username, password, size) }
                try {
                    opening.await()
                } catch (cancellation: CancellationException) {
                    calls.cancel()
                    throw cancellation
                }
            }
        } catch (failure: Exception) {
            calls.cancel()
            throw failure
        }
    }

    private suspend fun openWithCalls(
        calls: ReadCallTracker,
        url: String,
        username: String,
        password: String?,
        size: Long,
    ): WebDavReadHandle {
        if (size > 0) {
            // 目录列表已给出大小：打开时不发请求，首个读取才按起点发带 Range 的 GET。
            requireRangeSupported(url)
            return OkHttpWebDavReadHandle(
                size = size,
                first = null,
                calls = calls,
                openFrom = { position ->
                    rangedResponse(calls, url, username, password, position, requireRanges = true)
                },
            )
        }
        // 大小未知（播放读取）：用首个 GET 探测总长度。
        val first = rangedResponse(calls, url, username, password, position = 0, requireRanges = false)
        try {
            val totalSize = totalSizeOf(first.response)
                ?: throw MediaSourceException(MediaFailure.Unknown, "服务器没给出文件长度：$url")
            return OkHttpWebDavReadHandle(
                size = totalSize,
                first = first,
                calls = calls,
                openFrom = { position ->
                    rangedResponse(calls, url, username, password, position, requireRanges = false)
                },
            )
        } catch (failure: Exception) {
            calls.cancel()
            first.close()
            throw failure
        }
    }

    /**
     * 从 [position] 起开一次带 Range 的 GET。
     * [requireRanges] 为真时服务器回 200（忽略区间）即按源记下结论并失败，不从文件开头丢弃字节。
     */
    private fun rangedResponse(
        calls: ReadCallTracker,
        url: String,
        username: String,
        password: String?,
        position: Long,
        requireRanges: Boolean,
    ): RangedResponse {
        val request = requestOf(url, username, password).header(RANGE_HEADER, "$BYTES_PREFIX$position-").build()
        val response = execute(calls, request)
        if (!response.isSuccessful) {
            val statusCode = response.code
            response.close()
            throw statusFailure(statusCode, url)
        }
        if (requireRanges && response.code != STATUS_PARTIAL) {
            response.close()
            rangeUnsupported += originOf(url)
            throw MediaSourceException(MediaFailure.Unknown, "服务器忽略了区间请求：$url")
        }
        val body = response.body
        if (body == null) {
            response.close()
            throw MediaSourceException(MediaFailure.Unknown, "响应没有内容：$url")
        }
        return RangedResponse(
            start = if (response.code == STATUS_PARTIAL) position else 0,
            stream = body.byteStream(),
            response = response,
        )
    }

    /** 该源已确认忽略区间请求时直接失败，不再发请求。 */
    private fun requireRangeSupported(url: String) {
        if (originOf(url) in rangeUnsupported) {
            throw MediaSourceException(MediaFailure.Unknown, "服务器忽略了区间请求：$url")
        }
    }

    /** 源的身份：协议、主机与端口相同即视为同一台服务器。 */
    private fun originOf(url: String): String {
        val parsed = requestUrl(url)
        return "${parsed.scheme}://${parsed.host}:${parsed.port}"
    }

    private fun requestOf(url: String, username: String, password: String?): Request.Builder {
        val builder = Request.Builder().url(requestUrl(url))
        if (username.isNotEmpty()) builder.header(AUTHORIZATION_HEADER, Credentials.basic(username, password.orEmpty()))
        return builder
    }

    private fun execute(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (failure: IOException) {
        throw MediaSourceException(webdavFailureOf(failure), "请求发不出去：${request.url}", failure)
    }

    private fun execute(calls: ReadCallTracker, request: Request): Response = try {
        calls.execute(client, request)
    } catch (failure: IOException) {
        throw MediaSourceException(webdavFailureOf(failure), "请求发不出去：${request.url}", failure)
    }

    private fun statusFailure(statusCode: Int, url: String): MediaSourceException =
        MediaSourceException(webdavStatusFailure(statusCode), "服务器回了 $statusCode：$url")

    /** 总长度：优先取 Content-Range 的分母，否则取 Content-Length。 */
    private fun totalSizeOf(response: Response): Long? {
        val fromRange = response.header(CONTENT_RANGE_HEADER)?.substringAfter('/', "")?.trim()?.toLongOrNull()
        return fromRange ?: response.body?.contentLength()?.takeIf { it >= 0 }
    }

    private fun requestUrl(url: String): HttpUrl =
        encodeWebDavUrl(url).toHttpUrlOrNull()
            ?: throw MediaSourceException(MediaFailure.NotFound, "网络源地址不合法：$url")

    companion object {
        /** 超时避免连不上时长时间挂起；不设整段调用时限，顺序读长文件不受影响。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 读位置按字节偏移算，关掉透明压缩，避免压缩后长度与实际字节对不上。
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header(ACCEPT_ENCODING_HEADER, IDENTITY).build()) }
            .build()

        private const val OPERATION_TIMEOUT_SECONDS = 30L
        private const val METHOD_PROPFIND = "PROPFIND"
        private const val DEPTH_HEADER = "Depth"
        private const val DEPTH_CHILDREN = "1"
        private const val RANGE_HEADER = "Range"
        private const val BYTES_PREFIX = "bytes="
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val ACCEPT_ENCODING_HEADER = "Accept-Encoding"
        private const val CONTENT_RANGE_HEADER = "Content-Range"
        private const val IDENTITY = "identity"
        private const val STATUS_PARTIAL = 206
        private const val XML_MEDIA_TYPE = "application/xml; charset=utf-8"
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
                "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/>" +
                "</D:prop></D:propfind>"
    }
}

/**
 * 正在执行的 GET 调用：关闭时先 `cancel()`，让等响应头、跳读与正文读取都能退出。
 * 关闭后才决定发起的调用直接取消，不再建立连接。
 */
private class ReadCallTracker {

    private val lock = Any()
    private var closed = false
    private var active: Call? = null

    fun execute(client: OkHttpClient, request: Request): Response {
        val call = client.newCall(request)
        synchronized(lock) {
            if (closed) {
                call.cancel()
                throw IOException("读取已取消")
            }
            active = call
        }
        return try {
            call.execute()
        } finally {
            synchronized(lock) { if (active === call) active = null }
        }
    }

    fun cancel() {
        val call = synchronized(lock) {
            closed = true
            active.also { active = null }
        }
        call?.cancel()
    }
}

/** 一次带 Range 的 GET 的内容：内容从 [start] 开始。 */
private class RangedResponse(val start: Long, val stream: InputStream, val response: Response) : Closeable {

    /** 先关响应再关流：响应关闭会取消请求，让阻塞中的读取尽快退出。 */
    override fun close() {
        runCatching { response.close() }
        runCatching { stream.close() }
    }
}

/**
 * 带 Range 读的句柄：位置连续就沿用已打开的响应流，跳读按新起点重发 GET。
 *
 * [first] 为 null 表示打开时还没有响应流（大小已知），首个读取才发起 GET。
 * 关闭不等待读取锁：先取消当前 GET 并关掉当前响应，让阻塞读退出。
 * 关闭后任何读取立即失败，也不能再发起新的 GET。
 */
private class OkHttpWebDavReadHandle(
    override val size: Long,
    first: RangedResponse?,
    private val calls: ReadCallTracker,
    private val openFrom: (Long) -> RangedResponse,
) : WebDavReadHandle {

    @Volatile
    private var current: RangedResponse? = first

    @Volatile
    private var closed = false

    /** 已打开的流读到的位置：流从 [RangedResponse.start] 起，读完 [已读数] 后落在这里。 */
    private var position = first?.start ?: NO_POSITION

    /** 解码器与关闭可能来自不同线程，读与关闭串行进行。 */
    private val lock = Any()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("读取已取消")
        synchronized(lock) {
            if (closed) throw IOException("读取已取消")
            if (position < 0 || position >= size) return 0
            val response = responseAt(position)
            val read = response.stream.read(buffer, offset, length)
            if (closed) throw IOException("读取已取消")
            if (read <= 0) return 0
            this.position += read
            return read
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // 先取消 GET：正在等响应头或读正文时，只关已拿到的响应不足以让调用退出。
        calls.cancel()
        val response = current
        current = null
        response?.close()
    }

    private fun responseAt(wanted: Long): RangedResponse {
        if (closed) throw IOException("读取已取消")
        current?.let { if (position == wanted) return it }
        current?.close()
        current = null
        if (closed) throw IOException("读取已取消")
        val opened = openFrom(wanted)
        // 先挂到 current：关闭可能发生在跳读阻塞期间，必须能关到这一次响应。
        current = opened
        if (closed) {
            if (current === opened) current = null
            opened.close()
            throw IOException("读取已取消")
        }
        try {
            skipTo(opened, wanted)
        } catch (failure: Exception) {
            if (current === opened) current = null
            opened.close()
            throw failure
        }
        if (closed) {
            if (current === opened) current = null
            opened.close()
            throw IOException("读取已取消")
        }
        position = wanted
        return opened
    }

    /** 大小未知的读取里服务器忽略了 Range，响应从头给流：按顺序丢弃到请求位置。 */
    private fun skipTo(response: RangedResponse, wanted: Long) {
        var remaining = wanted - response.start
        val buffer = ByteArray(SKIP_BUFFER_BYTES)
        while (remaining > 0) {
            if (closed) throw IOException("读取已取消")
            val read = response.stream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read <= 0) throw MediaSourceException(MediaFailure.Unknown, "服务器提前结束了响应")
            remaining -= read
        }
    }

    private companion object {
        /** 还没有打开的响应流。 */
        const val NO_POSITION = -1L

        const val SKIP_BUFFER_BYTES = 64 * 1024
    }
}
