package com.cc3301.ccplayer.streaming

import java.io.ByteArrayOutputStream
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

/** 拷贝块的固定大小；回环读写统一按 1 MiB 搬运。 */
internal const val COPY_BUFFER_BYTES = 1024 * 1024

/**
 * 回环 HTTP 代理：只监听 127.0.0.1，把登记的流按 Range 响应给本机播放器。
 *
 * 同一个键恒得同一个地址；字节源在第一个请求到达时才由 opener 建立并缓存。
 * 一条连接按 HTTP 长连接连续处理请求；客户端要求关闭、请求读不出或响应没写完整时结束这条连接，
 * 不按空闲时间主动断开。
 * [close] 关闭全部已建立的字节源。
 */
class LoopbackStreamServer(
    port: Int = 0,
    private val acceptSocket: (ServerSocket) -> Socket = { it.accept() },
) : AutoCloseable {

    private val entries = ConcurrentHashMap<String, Entry>()
    private val idsByKey = ConcurrentHashMap<String, String>()

    /** 已 accept 的连接；[close] 逐个断开，阻塞在读上的处理线程随之退出。 */
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val nextId = AtomicLong(1)
    private val serverSocket = ServerSocket(port, BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
    private val acceptor = Thread({ acceptLoop() }, "loopback-stream-accept").apply {
        isDaemon = true
        start()
    }

    /** 实际监听的端口；构造时传 0 则由系统分配。 */
    val port: Int get() = serverSocket.localPort

    /**
     * 丢弃已建立的字节源：只打陈旧标记，不打断正在进行的响应；
     * 下一次请求先关闭旧源再重建。地址不变。
     */
    fun discardSources() {
        entries.values.forEach { it.markStale() }
    }

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
        // 监听关掉不影响已建立的连接：逐个断开，处理线程不会残留在读上。
        connections.forEach { runCatching { it.close() } }
        connections.clear()
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
            connections.add(connection)
            try {
                Thread({ handle(connection) }, "loopback-stream-connection").apply { isDaemon = true }.start()
            } catch (failure: Throwable) {
                connections.remove(connection)
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
     *
     * 一条连接按长连接连续处理请求；客户端要求关闭、请求读不出或响应没写完整时结束这条连接。
     * 任何失败都只关这条连接：播放核心下次请求重新建连，不向它暴露错误。
     */
    internal fun handle(connection: Socket) {
        try {
            connection.use { socket ->
                val input = socket.getInputStream()
                // 输出不套缓冲：响应头立刻发出，响应体按拷贝块整块写。
                val output = socket.getOutputStream()
                while (true) {
                    val request = try {
                        readRequest(input)
                    } catch (unreadable: IOException) {
                        null
                    } ?: return
                    if (!serve(output, request)) return
                    if (!request.keepAlive) return
                }
            }
        } catch (failure: Throwable) {
            // 连接异常只结束本次连接，连接线程正常退出；空闲连接不按时间主动断开，
            // 只由客户端断开、要求 close、响应没写满或异常结束。
            runCatching { System.err.println("回环流连接结束: $failure") }
        } finally {
            // 线程退出时摘掉登记，[close] 之后不再重复处理这条连接。
            connections.remove(connection)
        }
    }

    /**
     * 响应一条请求；返回这条连接能否继续承载下一条请求。
     * 播放器提前断开时只结束本次连接，不失效字节源。
     */
    private fun serve(output: OutputStream, request: Request): Boolean {
        val entry = entries[request.path.trimStart('/').substringBefore('/')]
        try {
            val source = if (entry == null) null else runCatching { entry.source() }.getOrNull()
            return when {
                entry == null -> respondStatus(output, request, STATUS_NOT_FOUND)
                source == null -> respondStatus(output, request, STATUS_UNAVAILABLE)
                else -> respond(output, request, entry, source)
            }
        } catch (disconnected: IOException) {
            return false
        }
    }

    /** 写一条带 Range 的响应；返回这条连接能否继续承载下一条请求。 */
    private fun respond(output: OutputStream, request: Request, entry: Entry, source: ByteSource): Boolean {
        val range = byteRangeResponse(request.rangeHeader, source.size)
        val headers = linkedMapOf(
            "Content-Type" to CONTENT_TYPE,
            "Content-Length" to range.contentLength.toString(),
            "Accept-Ranges" to "bytes",
            "Connection" to connectionHeader(request.keepAlive),
        )
        range.contentRange?.let { headers["Content-Range"] = it }
        writeHead(output, range.statusCode, statusText(range.statusCode), headers)
        val written = request.method == METHOD_HEAD || range.contentLength <= 0L ||
            copyRange(output, entry, source, range.start, range.contentLength)
        output.flush()
        // 响应体没写满时这条连接的帧边界已坏，不能再承载下一条请求。
        return written
    }

    /** 写一条没有响应体的状态行；返回这条连接能否继续承载下一条请求。 */
    private fun respondStatus(output: OutputStream, request: Request, statusCode: Int): Boolean {
        val headers = linkedMapOf(
            "Content-Length" to "0",
            "Connection" to connectionHeader(request.keepAlive),
        )
        writeHead(output, statusCode, statusText(statusCode), headers)
        output.flush()
        return true
    }

    /** 单次读取的块大小：不超过拷贝块，也不越过字节源声明的单次读上限。 */
    private fun readBlockBytes(source: ByteSource): Int =
        source.maxReadBytes.takeIf { it in 1 until COPY_BUFFER_BYTES } ?: COPY_BUFFER_BYTES

    /**
     * 把 [length] 字节拷进响应体；返回是否读满。读不动时按失败收尾，不补零。
     * 读到 0 或抛 [Exception] 视为这条源已不可用：当场废弃，下一次请求重新建立。
     * [Error] 不当作源失效，直接向上抛，由连接层统一记录。
     */
    private fun copyRange(
        output: OutputStream,
        entry: Entry,
        source: ByteSource,
        start: Long,
        length: Long,
    ): Boolean {
        val buffer = ByteArray(readBlockBytes(source))
        var position = start
        var remaining = length
        while (remaining > 0) {
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            val read = try {
                source.readAt(position, buffer, 0, wanted)
            } catch (failure: Exception) {
                // 读取失败按原样废弃这条源，异常照旧向上抛。
                entry.invalidate(source)
                throw failure
            } catch (failure: Error) {
                // Error 不当作源失效：照旧上抛，由连接层统一记录。
                throw failure
            }
            if (read <= 0) {
                entry.invalidate(source)
                return false
            }
            output.write(buffer, 0, read)
            position += read
            remaining -= read
        }
        return true
    }

    private fun connectionHeader(keepAlive: Boolean): String =
        if (keepAlive) CONNECTION_KEEP_ALIVE else CONNECTION_CLOSE

    private fun writeHead(output: OutputStream, statusCode: Int, statusText: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    /**
     * 读一条请求；返回 null 表示连接已结束或请求不合规。
     * 逐字节读到空行为止，不做预读：同一连接上的下一条请求不能被这条请求的缓冲吃掉。
     * 空行判定见 [readRequestHead]：多余的 CR 让空行重新起算，不是按行解析的容错。
     */
    private fun readRequest(input: InputStream): Request? {
        val head = readRequestHead(input) ?: return null
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null
        var rangeHeader: String? = null
        var connectionValue: String? = null
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when {
                name.equals(RANGE_HEADER, ignoreCase = true) -> rangeHeader = value
                name.equals(CONNECTION_HEADER, ignoreCase = true) -> connectionValue = value
            }
        }
        return Request(
            method = requestLine[0].uppercase(),
            path = requestLine[1],
            rangeHeader = rangeHeader,
            keepAlive = keepAliveRequested(requestLine.getOrNull(2), connectionValue),
        )
    }

    /**
     * 读到请求头的空行为止；请求头过大或连接已结束返回 null。
     * 空行判定是逐字节状态机：遇到多余的 CR（如 `\r\r\n`）会重新起算，
     * 这一条最终按请求头过大或连接结束收尾，属可接受的边界。
     */
    private fun readRequestHead(input: InputStream): String? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            head.write(byte)
            matched = when {
                (matched == 0 || matched == 2) && byte == CR -> matched + 1
                (matched == 1 || matched == 3) && byte == LF -> matched + 1
                else -> 0
            }
            if (matched == HEAD_TERMINATOR_BYTES) return String(head.toByteArray(), Charsets.ISO_8859_1)
            if (head.size() > MAX_REQUEST_HEAD_BYTES) return null
        }
    }

    /** HTTP/1.1 起默认长连接；HTTP/1.0 只在显式 keep-alive 时保持，任何 close 都优先。 */
    private fun keepAliveRequested(protocol: String?, connectionValue: String?): Boolean {
        val tokens = connectionValue?.split(',')?.map { it.trim().lowercase() }.orEmpty()
        if (tokens.contains(CONNECTION_CLOSE)) return false
        if (tokens.contains(CONNECTION_KEEP_ALIVE)) return true
        return protocol != PROTOCOL_HTTP_10
    }

    private fun statusText(statusCode: Int): String = when (statusCode) {
        206 -> "Partial Content"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private data class Request(
        val method: String,
        val path: String,
        val rangeHeader: String?,
        val keepAlive: Boolean,
    )

    /**
     * 一条流：opener 只在第一次用到时跑一次，之后复用同一个字节源。
     * 陈旧标记在退后台时打上，下一次请求关闭重建；失败废弃只认被废弃的那一条源。
     */
    private class Entry(private val open: () -> ByteSource) {

        private val lock = Any()

        @Volatile
        private var source: ByteSource? = null

        /** 已登记陈旧：不打断正在进行的读取，下一次请求先关闭再重建。 */
        @Volatile
        private var stale = false

        @Volatile
        private var closed = false

        /** 取字节源；建立失败时抛出，下次请求会重试。关闭后不再建立新源。 */
        fun source(): ByteSource {
            if (closed) throw IllegalStateException("流已关闭")
            source?.let { current -> if (!stale) return current }
            synchronized(lock) {
                check(!closed) { "流已关闭" }
                // 还没有源时先消费陈旧标记：空标记不该让第一次建源白建一次。
                if (source == null) consumeStale()
                source?.let { current ->
                    if (!consumeStale()) return current
                    // 旧源可能还有别的响应在读，关闭后那一条按读取失败收尾。
                    source = null
                    runCatching { current.close() }
                }
                // 建源可能阻塞较久：建源期间收到的陈旧请求让这一条立即作废并重建一次；
                // 重建期间再来的标记留给下一次请求从同一处消费。
                var created = open()
                if (consumeStale()) {
                    runCatching { created.close() }
                    created = open()
                }
                source = created
                return created
            }
        }

        /** 陈旧标记唯一的读+清除点；invalidate 与 close 会连同源一起清掉它。 */
        private fun consumeStale(): Boolean {
            if (!stale) return false
            stale = false
            return true
        }

        /**
         * 打陈旧标记：不关闭、不取锁，正在进行的响应不受影响。
         * 还没有建立源时也照打：首次建源与退后台并发时，这次请求不能漏。
         */
        fun markStale() {
            stale = true
        }

        /**
         * 废弃指定的字节源：缓存里还是这一条时才关闭并清空。
         * 并发的迟到失败不能关掉后来重建的健康源，也不能截断它上面的响应。
         */
        fun invalidate(expired: ByteSource) {
            synchronized(lock) {
                if (source !== expired) return
                source = null
                stale = false
                runCatching { expired.close() }
            }
        }

        fun close() {
            synchronized(lock) {
                closed = true
                source?.let { runCatching { it.close() } }
                source = null
                stale = false
            }
        }
    }

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val CONTENT_TYPE = "application/octet-stream"
        const val RANGE_HEADER = "Range"
        const val CONNECTION_HEADER = "Connection"
        const val CONNECTION_CLOSE = "close"
        const val CONNECTION_KEEP_ALIVE = "keep-alive"
        const val PROTOCOL_HTTP_10 = "HTTP/1.0"
        const val METHOD_HEAD = "HEAD"
        const val STATUS_NOT_FOUND = 404
        const val STATUS_UNAVAILABLE = 503
        const val BACKLOG = 16
        const val MAX_REQUEST_HEAD_BYTES = 16 * 1024
        const val HEAD_TERMINATOR_BYTES = 4
        const val CR = '\r'.code
        const val LF = '\n'.code
    }
}
