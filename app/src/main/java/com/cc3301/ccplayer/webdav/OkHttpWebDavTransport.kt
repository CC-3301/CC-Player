package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit

/**
 * OkHttp 实现：PROPFIND 列目录，带 Range 的 GET 随机读。
 *
 * 读文件时位置连续就沿用同一个响应流，跳读才按新起点重发 GET。
 */
class OkHttpWebDavTransport(private val client: OkHttpClient = defaultClient()) : WebDavTransport {

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

    override suspend fun openRead(url: String, username: String, password: String?): WebDavReadHandle = withContext(Dispatchers.IO) {
        val first = rangedResponse(url, username, password, position = 0)
        try {
            val size = totalSizeOf(first.response)
                ?: throw MediaSourceException(MediaFailure.Unknown, "服务器没给出文件长度：$url")
            OkHttpWebDavReadHandle(
                size = size,
                first = first,
                openFrom = { position -> rangedResponse(url, username, password, position) },
            )
        } catch (failure: Exception) {
            first.close()
            throw failure
        }
    }

    /** 从 [position] 起开一次带 Range 的 GET。 */
    private fun rangedResponse(url: String, username: String, password: String?, position: Long): RangedResponse {
        val request = requestOf(url, username, password).header(RANGE_HEADER, "$BYTES_PREFIX$position-").build()
        val response = execute(request)
        if (!response.isSuccessful) {
            val statusCode = response.code
            response.close()
            throw statusFailure(statusCode, url)
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
 * 关闭不等待读取锁：先置关闭标志并关掉当前响应，让阻塞读退出。
 * 关闭后任何读取立即失败，也不能再发起新的 GET。
 */
private class OkHttpWebDavReadHandle(
    override val size: Long,
    first: RangedResponse,
    private val openFrom: (Long) -> RangedResponse,
) : WebDavReadHandle {

    @Volatile
    private var current: RangedResponse? = first

    @Volatile
    private var closed = false

    /** 已打开的流读到的位置：流从 [RangedResponse.start] 起，读完 [已读数] 后落在这里。 */
    private var position = first.start

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

    /** 服务器忽略 Range 时响应从头给流，跳到请求位置。 */
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
        const val SKIP_BUFFER_BYTES = 64 * 1024
    }
}
