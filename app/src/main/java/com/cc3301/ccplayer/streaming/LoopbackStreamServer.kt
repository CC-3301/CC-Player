package com.cc3301.ccplayer.streaming

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 回环 HTTP 代理：只监听 127.0.0.1，把登记的 [ByteSource] 按 Range 响应给本机播放器。
 *
 * 播放核心不直接接触网络协议，拿到的是本机地址。[close] 会关闭全部已登记的字节源。
 */
class LoopbackStreamServer(port: Int = 0) : AutoCloseable {

    private val sources = ConcurrentHashMap<String, ByteSource>()
    private val nextId = AtomicLong(1)
    private val serverSocket = ServerSocket(port, BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
    private val acceptor = Thread({ acceptLoop() }, "loopback-stream-accept").apply {
        isDaemon = true
        start()
    }

    /** 实际监听的端口；构造时传 0 则由系统分配。 */
    val port: Int get() = serverSocket.localPort

    /** 登记字节源，返回播放器可用的本机地址。 */
    fun serve(source: ByteSource): String {
        val id = nextId.getAndIncrement().toString()
        sources[id] = source
        return "http://$LOOPBACK_ADDRESS:$port/$id"
    }

    override fun close() {
        runCatching { serverSocket.close() }
        sources.values.forEach { runCatching { it.close() } }
        sources.clear()
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val connection = try {
                serverSocket.accept()
            } catch (closed: IOException) {
                return
            }
            Thread({ handle(connection) }, "loopback-stream-connection").apply { isDaemon = true }.start()
        }
    }

    private fun handle(connection: Socket) {
        connection.use { socket ->
            val request = try {
                readRequest(socket.getInputStream())
            } catch (unreadable: IOException) {
                null
            } ?: return
            val id = request.path.trimStart('/').substringBefore('/')
            val source = sources[id]
            try {
                socket.getOutputStream().buffered().use { output ->
                    if (source == null) {
                        writeHead(output, STATUS_NOT_FOUND, "Not Found", emptyMap())
                    } else {
                        respond(output, request, source)
                    }
                }
            } catch (disconnected: IOException) {
                // 播放器提前断开时只结束本次连接。
            }
        }
    }

    private fun respond(output: OutputStream, request: Request, source: ByteSource) {
        val range = byteRangeResponse(request.rangeHeader, source.size)
        val headers = linkedMapOf(
            "Content-Type" to CONTENT_TYPE,
            "Content-Length" to range.contentLength.toString(),
            "Accept-Ranges" to "bytes",
            "Connection" to "close",
        )
        range.contentRange?.let { headers["Content-Range"] = it }
        writeHead(output, range.statusCode, statusText(range.statusCode), headers)
        if (request.method != METHOD_HEAD && range.contentLength > 0) {
            copyRange(output, source, range.start, range.contentLength)
        }
        output.flush()
    }

    private fun copyRange(output: OutputStream, source: ByteSource, start: Long, length: Long) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var position = start
        var remaining = length
        while (remaining > 0) {
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            val read = source.readAt(position, buffer, 0, wanted)
            if (read <= 0) return
            output.write(buffer, 0, read)
            position += read
            remaining -= read
        }
    }

    private fun writeHead(output: OutputStream, statusCode: Int, statusText: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun readRequest(input: InputStream): Request? {
        val reader = input.bufferedReader(Charsets.ISO_8859_1)
        val parts = reader.readLine()?.split(' ') ?: return null
        if (parts.size < 2) return null
        var rangeHeader: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (line.substring(0, colon).trim().equals(RANGE_HEADER, ignoreCase = true)) {
                rangeHeader = line.substring(colon + 1).trim()
            }
        }
        return Request(method = parts[0].uppercase(), path = parts[1], rangeHeader = rangeHeader)
    }

    private fun statusText(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        206 -> "Partial Content"
        416 -> "Range Not Satisfiable"
        else -> "OK"
    }

    private data class Request(val method: String, val path: String, val rangeHeader: String?)

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val CONTENT_TYPE = "application/octet-stream"
        const val RANGE_HEADER = "Range"
        const val METHOD_HEAD = "HEAD"
        const val STATUS_NOT_FOUND = 404
        const val BACKLOG = 16
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
