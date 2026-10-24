package com.cc3301.ccplayer.webdav

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 收到的一次请求。 */
class FakeRequest(val method: String, val path: String, private val headers: Map<String, String>) {

    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * 假 WebDAV 服务器：在回环端口上应答 PROPFIND 与带 Range 的 GET。
 *
 * [ignoreRange] 置真时忽略 Range 头，模拟不支持区间请求的服务器；
 * [withoutLength] 置真时不给内容长度，模拟长度未知的响应；
 * [getStatus] 非 0 时 GET 一律回该状态码且不带内容；
 * [stallBody] 置真时 GET 只发响应头，正文等到服务器关闭才发，用来模拟阻塞读。
 */
class FakeWebDavServer(
    private val listing: String = "",
    private val bytes: ByteArray = ByteArray(0),
    private val propfindStatus: Int = 207,
    private val getStatus: Int = 0,
    private val ignoreRange: Boolean = false,
    private val withoutLength: Boolean = false,
    private val stallBody: Boolean = false,
) : AutoCloseable {

    private val receivedRequests = CopyOnWriteArrayList<FakeRequest>()
    private val releaseStalledBody = CountDownLatch(1)
    private val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))

    init {
        Thread({ acceptLoop() }, "fake-webdav-accept").apply { isDaemon = true }.start()
    }

    /** 源的根地址，含本服务器实际监听的端口。 */
    val root: String get() = "http://$LOOPBACK_ADDRESS:${serverSocket.localPort}/dav"

    /** 收到的请求，按到达顺序。 */
    fun received(): List<FakeRequest> = receivedRequests.toList()

    override fun close() {
        releaseStalledBody.countDown()
        runCatching { serverSocket.close() }
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val connection = try {
                serverSocket.accept()
            } catch (closed: IOException) {
                return
            }
            Thread({ handle(connection) }, "fake-webdav-connection").apply { isDaemon = true }.start()
        }
    }

    private fun handle(connection: Socket) {
        connection.use { socket ->
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val parts = input.readLine()?.split(' ') ?: return
            if (parts.size < 2) return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (bodyLength > 0) input.read(CharArray(bodyLength), 0, bodyLength)

            val method = parts[0].uppercase()
            receivedRequests += FakeRequest(method = method, path = parts[1], headers = headers)
            val output = socket.getOutputStream()
            when {
                method == METHOD_PROPFIND -> write(output, propfindStatus, XML_CONTENT_TYPE, listing.toByteArray(), extraHeaders = emptyMap())
                getStatus != 0 -> write(output, getStatus, OCTET_STREAM_CONTENT_TYPE, ByteArray(0), extraHeaders = emptyMap())
                else -> writeRange(output, headers["range"])
            }
            output.flush()
        }
    }

    private fun writeRange(output: java.io.OutputStream, rangeHeader: String?) {
        if (withoutLength) {
            write(
                output = output,
                status = STATUS_OK,
                contentType = OCTET_STREAM_CONTENT_TYPE,
                body = bytes,
                extraHeaders = mapOf("Connection" to "close"),
                includeLength = false,
                stall = stallBody,
            )
            return
        }
        val spec = rangeHeader?.removePrefix(BYTES_PREFIX)?.substringBefore(',')?.trim()
        if (ignoreRange || spec == null) {
            write(output, STATUS_OK, OCTET_STREAM_CONTENT_TYPE, bytes, extraHeaders = emptyMap(), stall = stallBody)
            return
        }
        val start = spec.substringBefore('-').toLongOrNull() ?: 0
        val end = spec.substringAfter('-').toLongOrNull() ?: (bytes.size - 1).toLong()
        val slice = if (start >= bytes.size) ByteArray(0) else bytes.copyOfRange(start.toInt(), minOf(end, bytes.size - 1L).toInt() + 1)
        write(
            output = output,
            status = STATUS_PARTIAL,
            contentType = OCTET_STREAM_CONTENT_TYPE,
            body = slice,
            extraHeaders = mapOf("Content-Range" to "bytes $start-${start + slice.size - 1}/${bytes.size}"),
            stall = stallBody,
        )
    }

    private fun write(
        output: java.io.OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String>,
        includeLength: Boolean = true,
        stall: Boolean = false,
    ) {
        val headers = linkedMapOf("Content-Type" to contentType)
        if (includeLength) headers["Content-Length"] = body.size.toString()
        headers.putAll(extraHeaders)
        val head = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.flush()
        if (stall) {
            releaseStalledBody.await(STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return
        }
        runCatching { output.write(body) }
    }

    private fun statusText(status: Int): String = when (status) {
        STATUS_OK -> "OK"
        STATUS_PARTIAL -> "Partial Content"
        STATUS_UNAUTHORIZED -> "Unauthorized"
        STATUS_NOT_FOUND -> "Not Found"
        else -> "Status"
    }

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val STATUS_UNAUTHORIZED = 401
        const val STATUS_NOT_FOUND = 404
        const val BACKLOG = 16
        const val METHOD_PROPFIND = "PROPFIND"
        const val BYTES_PREFIX = "bytes="
        const val STATUS_OK = 200
        const val STATUS_PARTIAL = 206
        const val XML_CONTENT_TYPE = "application/xml"
        const val OCTET_STREAM_CONTENT_TYPE = "application/octet-stream"
        const val STALL_TIMEOUT_SECONDS = 10L
    }
}
