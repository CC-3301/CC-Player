package com.cc3301.ccplayer.streaming

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 接受循环连续失败达到该次数后关闭监听，避免忙循环。 */
internal const val LOOPBACK_ACCEPT_FAILURE_LIMIT = 8

/**
 * 回环 HTTP 代理：只监听 127.0.0.1，把登记的流按 Range 响应给本机播放器。
 *
 * 同一个键恒得同一个地址；字节源在第一个请求到达时才由 opener 建立并缓存。
 * [close] 关闭全部已建立的字节源。
 */
class LoopbackStreamServer(
    port: Int = 0,
    private val acceptSocket: (ServerSocket) -> Socket = { it.accept() },
) : AutoCloseable {

    private val entries = ConcurrentHashMap<String, Entry>()
    private val idsByKey = ConcurrentHashMap<String, String>()
    private val nextId = AtomicLong(1)
    private val serverSocket = ServerSocket(port, BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
    private val acceptor = Thread({ acceptLoop() }, "loopback-stream-accept").apply {
        isDaemon = true
        start()
    }

    /** 实际监听的端口；构造时传 0 则由系统分配。 */
    val port: Int get() = serverSocket.localPort

    /** 登记一条流并返回播放器可用的本机地址；同一个键重复登记只留第一次的 opener。 */
    fun serve(key: String, open: () -> ByteSource): String {
        val id = idsByKey.computeIfAbsent(key) {
            val created = nextId.getAndIncrement().toString()
            entries[created] = Entry(open)
            created
        }
        return "http://$LOOPBACK_ADDRESS:$port/$id"
    }

    override fun close() {
        runCatching { serverSocket.close() }
        entries.values.forEach { it.close() }
        entries.clear()
        idsByKey.clear()
    }

    private fun acceptLoop() {
        var consecutiveFailures = 0
        while (!serverSocket.isClosed) {
            val connection = try {
                acceptSocket(serverSocket)
            } catch (failure: IOException) {
                if (serverSocket.isClosed) return
                consecutiveFailures++
                if (consecutiveFailures >= LOOPBACK_ACCEPT_FAILURE_LIMIT) {
                    closeAfterAcceptFailure(failure)
                    return
                }
                continue
            } catch (failure: RuntimeException) {
                if (serverSocket.isClosed) return
                consecutiveFailures++
                if (consecutiveFailures >= LOOPBACK_ACCEPT_FAILURE_LIMIT) {
                    closeAfterAcceptFailure(failure)
                    return
                }
                continue
            } catch (failure: Throwable) {
                // Error 与其它异常无法安全恢复，记录后关闭监听。
                closeAfterAcceptFailure(failure)
                return
            }
            consecutiveFailures = 0
            try {
                Thread({ handle(connection) }, "loopback-stream-connection").apply { isDaemon = true }.start()
            } catch (failure: Throwable) {
                runCatching { connection.close() }
            }
        }
    }

    /** 接受线程无法继续时关闭监听并记录，避免线程静默退出后端口看似仍可用。 */
    private fun closeAfterAcceptFailure(failure: Throwable) {
        runCatching { System.err.println("回环流接受线程退出: $failure") }
        runCatching { serverSocket.close() }
    }

    /**
     * 连接处理线程的顶层边界：运行时异常（如 SMB 读超时）按连接失败收尾。
     * 异常不能逃逸到线程顶层，否则会终止整个进程。
     */
    internal fun handle(connection: Socket) {
        try {
            connection.use { socket ->
                val request = try {
                    readRequest(socket.getInputStream())
                } catch (unreadable: IOException) {
                    null
                } ?: return
                val entry = entries[request.path.trimStart('/').substringBefore('/')]
                try {
                    socket.getOutputStream().buffered().use { output ->
                        when {
                            entry == null -> writeHead(output, STATUS_NOT_FOUND, statusText(STATUS_NOT_FOUND), emptyMap())
                            else -> {
                                val source = runCatching { entry.source() }.getOrNull()
                                if (source == null) {
                                    writeHead(output, STATUS_UNAVAILABLE, statusText(STATUS_UNAVAILABLE), emptyMap())
                                } else {
                                    respond(output, request, source)
                                }
                            }
                        }
                    }
                } catch (disconnected: IOException) {
                    // 播放器提前断开时只结束本次连接，不失效字节源。
                }
            }
        } catch (failure: Throwable) {
            // 读超时等异常只结束本次连接，连接线程正常退出。
            runCatching { System.err.println("回环流连接结束: $failure") }
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
        206 -> "Partial Content"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private data class Request(val method: String, val path: String, val rangeHeader: String?)

    /** 一条流：opener 只在第一次用到时跑一次，之后复用同一个字节源。 */
    private class Entry(private val open: () -> ByteSource) {

        private val lock = Any()

        @Volatile
        private var source: ByteSource? = null

        @Volatile
        private var closed = false

        /** 取字节源；建立失败时抛出，下次请求会重试。关闭后不再建立新源。 */
        fun source(): ByteSource {
            if (closed) throw IllegalStateException("流已关闭")
            source?.let { return it }
            synchronized(lock) {
                check(!closed) { "流已关闭" }
                return source ?: open().also { source = it }
            }
        }

        fun close() {
            synchronized(lock) {
                closed = true
                source?.let { runCatching { it.close() } }
                source = null
            }
        }
    }

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val CONTENT_TYPE = "application/octet-stream"
        const val RANGE_HEADER = "Range"
        const val METHOD_HEAD = "HEAD"
        const val STATUS_NOT_FOUND = 404
        const val STATUS_UNAVAILABLE = 503
        const val BACKLOG = 16
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
