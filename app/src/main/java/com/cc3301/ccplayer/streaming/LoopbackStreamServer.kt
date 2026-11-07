package com.cc3301.ccplayer.streaming

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 接受循环连续失败达到该次数后关闭监听，避免忙循环。 */
internal const val LOOPBACK_ACCEPT_FAILURE_LIMIT = 8

/** 单次读取的块上限：压到 64 KiB，慢链路也能在停滞阈值内返回新字节。 */
internal const val COPY_BUFFER_BYTES = 64 * 1024

/** 重建字节源的等待上限：超时按本次请求失败收尾，不无限等待。 */
internal const val SOURCE_REBUILD_TIMEOUT_MILLIS = 5_000L

/** 传输停滞判据：响应体拷贝期间连续这么久没有读到新字节就主动断开这条连接；固定值，不作设置项。 */
internal const val STALL_TIMEOUT_MILLIS = 3_000L

/** 单次读取超过这么久仍未返回时记一条停滞日志；每次读取最多一条，不逐读记。 */
internal const val SLOW_READ_LOG_MILLIS = 1_000L

/** 看门狗的检查周期：只影响断开时刻的精度，不产生日志。 */
private const val STALL_CHECK_INTERVAL_MILLIS = 100L

/**
 * 回环 HTTP 代理：只监听 127.0.0.1，把登记的流按 Range 响应给本机播放器。
 *
 * 同一个键恒得同一个地址；字节源在第一个请求到达时才由 opener 建立并缓存。
 * 一条连接按 HTTP 长连接连续处理请求；客户端要求关闭、请求读不出或响应没写完整时结束这条连接。
 * 响应体拷贝期间源侧读在途超过 [STALL_TIMEOUT_MILLIS] 未返回就判停滞：先给这条连接对应的
 * 字节源打陈旧标记（下一次请求重建时由建源线程关掉旧源、解除阻塞），再断开连接，
 * 让播放核心立刻收到读错误；等建源与空闲长连接都不判停滞。
 * [close] 关闭全部已建立的字节源。
 */
class LoopbackStreamServer(
    port: Int = 0,
    private val acceptSocket: (ServerSocket) -> Socket = { it.accept() },
    private val rebuildTimeoutMillis: Long = SOURCE_REBUILD_TIMEOUT_MILLIS,
    /** 建源线程：SMB 重连可能长时间阻塞，不能挂在请求线程上。 */
    private val sourceOpener: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "loopback-source-open").apply { isDaemon = true }
    },
    /** 诊断日志出口：连接级打点跟随设置开关，不逐读、不逐帧。 */
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
) : AutoCloseable {

    private val entries = ConcurrentHashMap<String, Entry>()
    private val idsByKey = ConcurrentHashMap<String, String>()

    /** 已 accept 的连接；[close] 逐个断开，阻塞在读上的处理线程随之退出。 */
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val nextId = AtomicLong(1)

    /** 看门狗异常的限频打点时刻：一分钟最多一条。 */
    @Volatile
    private var lastWatchdogFailureAtNanos = 0L

    private val serverSocket = ServerSocket(port, BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
    private val acceptor = Thread({ acceptLoop() }, "loopback-stream-accept").apply {
        isDaemon = true
        start()
    }

    /** 传输看门狗：扫在拷贝响应体的连接，按「最后一次读到新字节」判停滞。 */
    private val stallWatchdog = Thread({ watchTransferStalls() }, "loopback-stream-watchdog").apply {
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

    /**
     * 关闭已建立的字节源，地址与条目保留：停播后当场交还句柄与会话占用，下一次请求重新建源。
     * 与 [discardSources] 的区别是这里真的关，所以只在没有播放时要读的时候调用。
     */
    fun closeSources() {
        entries.values.forEach { it.closeSource() }
    }

    /** 这条流是否已有可直接复用的字节源；没有源或已登记陈旧时为 false。 */
    fun hasFreshSource(key: String): Boolean {
        val id = idsByKey[key] ?: return false
        return entries[id]?.hasFreshSource() == true
    }

    /** 登记一条流并返回播放器可用的本机地址；同一个键重复登记只留第一次的 opener。 */
    fun serve(key: String, open: () -> ByteSource): String {
        val id = idsByKey.computeIfAbsent(key) {
            val created = nextId.getAndIncrement().toString()
            entries[created] = Entry(
                open = open,
                runAsync = { task -> sourceOpener.execute { task() } },
                timeoutMillis = rebuildTimeoutMillis,
            )
            created
        }
        return "http://$LOOPBACK_ADDRESS:$port/$id"
    }

    override fun close() {
        runCatching { serverSocket.close() }
        stallWatchdog.interrupt()
        // 监听关掉不影响已建立的连接：逐个断开，处理线程不会残留在读上。
        connections.forEach {
            it.endReason = EndReason.ServerClosed
            runCatching { it.socket.close() }
        }
        connections.clear()
        entries.values.forEach { it.close() }
        entries.clear()
        idsByKey.clear()
        // 在途建源不在这里等、也不打断：建好后由建源线程自己关掉。
        sourceOpener.shutdown()
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
            val tracked = Connection(connection)
            connections.add(tracked)
            try {
                Thread({ handle(tracked) }, "loopback-stream-connection").apply { isDaemon = true }.start()
            } catch (failure: Throwable) {
                connections.remove(tracked)
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
     * 传输看门狗：只看正在拷贝响应体的连接。
     * 判据是源侧读在途超过 [STALL_TIMEOUT_MILLIS] 仍未返回——这期间没有任何新字节；
     * 写阻塞（等播放核心取走数据）不算停滞。后台播放同样判：暂停时不读，不会误伤。
     */
    private fun watchTransferStalls() {
        while (!serverSocket.isClosed) {
            try {
                Thread.sleep(STALL_CHECK_INTERVAL_MILLIS)
            } catch (interrupted: InterruptedException) {
                return
            }
            // 一次异常不能让之后的停滞判定永久失效：限频记一条，再继续下一轮。
            runCatching { checkTransferStalls() }.onFailure { logWatchdogFailure(it) }
        }
    }

    /** 看门狗自身异常的限频打点：一分钟最多一条，既不刷屏也不至于无声失效。 */
    private fun logWatchdogFailure(failure: Throwable) {
        val now = System.nanoTime()
        if (now - lastWatchdogFailureAtNanos < TimeUnit.MINUTES.toNanos(1)) return
        lastWatchdogFailureAtNanos = now
        diagnostics.log { "回环流看门狗异常: ${failure.javaClass.simpleName}" }
    }

    private fun checkTransferStalls() {
        val now = System.nanoTime()
        connections.forEach { connection ->
            if (!connection.transferring || connection.stalled.get()) return@forEach
            val readStartedAtNanos = connection.readStartedAtNanos
            if (readStartedAtNanos == 0L) return@forEach
            val waitedNanos = now - readStartedAtNanos
            if (waitedNanos >= TimeUnit.MILLISECONDS.toNanos(SLOW_READ_LOG_MILLIS)) {
                logSlowRead(connection, TimeUnit.NANOSECONDS.toMillis(waitedNanos))
            }
            if (waitedNanos < TimeUnit.MILLISECONDS.toNanos(STALL_TIMEOUT_MILLIS)) return@forEach
            if (!connection.stalled.compareAndSet(false, true)) return@forEach
            // 只断 socket 打断不了卡在源侧的读，也拦不住下一次请求复用这条卡死的源：
            // 先打陈旧标记，下一次请求重建时由建源线程关掉旧源、解除阻塞。
            connection.activeEntry?.markStale()
            connection.endReason = EndReason.Stalled
            // 读取还卡着时处理线程不会走到收尾，这一行必须当场写出，否则看不到断开原因。
            logConnectionEnd(connection)
            runCatching { connection.socket.close() }
        }
    }

    /** 单次读停滞：同一次读取最多记一条，不逐读记。 */
    private fun logSlowRead(connection: Connection, waitedMillis: Long) {
        if (!connection.slowReadLogged.compareAndSet(false, true)) return
        diagnostics.log {
            "回环流读取停滞: 位置 ${connection.readPosition} | 长度 ${connection.readLength} | " +
                "已等 ${waitedMillis}ms"
        }
    }

    /** 连接结束的唯一一行：原因与首字节耗时；同一条连接只写一次，停滞时由看门狗先写。 */
    private fun logConnectionEnd(connection: Connection) {
        if (!connection.endLogged.compareAndSet(false, true)) return
        diagnostics.log {
            "回环流连接结束: 原因: ${connection.endReason.label} | 首字节: ${connection.firstByteText()}"
        }
    }

    private fun elapsedMillis(sinceNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sinceNanos).coerceAtLeast(0L)

    /**
     * 连接处理线程的顶层边界：运行时异常（如 SMB 读超时）按连接失败收尾。
     * 异常不能逃逸到线程顶层，否则会终止整个进程。
     *
     * 一条连接按长连接连续处理请求；客户端要求关闭、请求读不出或响应没写完整时结束这条连接。
     * 任何失败都只关这条连接：播放核心下次请求重新建连，不向它暴露错误。
     */
    internal fun handle(connection: Socket) {
        handle(Connection(connection), tracked = false)
    }

    private fun handle(connection: Connection, tracked: Boolean = true) {
        try {
            connection.socket.use { socket ->
                val input = socket.getInputStream()
                // 输出不套缓冲：响应头立刻发出，响应体按拷贝块整块写。
                val output = socket.getOutputStream()
                while (true) {
                    val request = try {
                        readRequest(input)
                    } catch (unreadable: IOException) {
                        null
                    } ?: return
                    if (!serve(output, request, connection)) return
                    if (!request.keepAlive) return
                }
            }
        } catch (failure: Throwable) {
            // 连接异常只结束本次连接，连接线程正常退出。
            runCatching { System.err.println("回环流连接结束: $failure") }
        } finally {
            // 线程退出时摘掉登记，[close] 之后不再重复处理这条连接。
            if (tracked) connections.remove(connection)
            logConnectionEnd(connection)
        }
    }

    /**
     * 响应一条请求；返回这条连接能否继续承载下一条请求。
     * 播放器提前断开时只结束本次连接，不失效字节源。
     */
    private fun serve(output: OutputStream, request: Request, connection: Connection): Boolean {
        val id = request.path.trimStart('/').substringBefore('/')
        val entry = entries[id]
        val hit = entry?.hasFreshSource() == true
        // 每条连接只在首个请求写这一行：长连接上的多个 Range 不逐请求记。
        if (connection.firstRequestLogged.compareAndSet(false, true)) {
            diagnostics.log {
                "回环流收到请求: $id | Range: ${request.rangeHeader ?: "-"} | 源: ${if (hit) "命中" else "新建"}"
            }
        }
        if (entry == null) return respondStatus(output, request, STATUS_NOT_FOUND)
        val sourceStartNanos = System.nanoTime()
        val lookup = entry.source()
        // 建源完成时刻：首字节耗时从这里算，不含建源本身。
        val sourceReadyAtNanos = System.nanoTime()
        // 「建源结束」只由发起这次重建的请求写；并发等待同一轮重建的请求不重复记。
        if (lookup.startedBuild) {
            diagnostics.log {
                "回环流建源结束: $id | 耗时: ${elapsedMillis(sourceStartNanos)}ms | " +
                    (lookup.failure?.let { "失败: ${it.javaClass.simpleName}" } ?: "成功")
            }
        }
        val source = lookup.source ?: return respondStatus(output, request, STATUS_UNAVAILABLE)
        return try {
            respond(output, request, connection, entry, source, sourceReadyAtNanos)
        } catch (disconnected: IOException) {
            false
        }
    }

    /** 写一条带 Range 的响应；返回这条连接能否继续承载下一条请求。 */
    private fun respond(
        output: OutputStream,
        request: Request,
        connection: Connection,
        entry: Entry,
        source: ByteSource,
        sourceReadyAtNanos: Long,
    ): Boolean {
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
            copyRange(output, connection, entry, source, range.start, range.contentLength, sourceReadyAtNanos)
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
        connection: Connection,
        entry: Entry,
        source: ByteSource,
        start: Long,
        length: Long,
        sourceReadyAtNanos: Long,
    ): Boolean {
        val buffer = ByteArray(readBlockBytes(source))
        var position = start
        var remaining = length
        // 看门狗只认传输阶段：源侧读在途超时才算停滞。
        connection.beginTransfer(entry)
        try {
            while (remaining > 0) {
                val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                val readStartedAtNanos = connection.beginRead(position, wanted)
                val read = try {
                    source.readAt(position, buffer, 0, wanted)
                } catch (failure: Exception) {
                    // 读取失败按原样废弃这条源，异常照旧向上抛。
                    entry.invalidate(source)
                    throw failure
                } catch (failure: Error) {
                    // Error 不当作源失效：照旧上抛，由连接层统一记录。
                    throw failure
                } finally {
                    connection.endRead()
                    val waitedMillis = elapsedMillis(readStartedAtNanos)
                    if (waitedMillis >= SLOW_READ_LOG_MILLIS) {
                        logSlowRead(connection, waitedMillis)
                    }
                }
                if (read <= 0) {
                    entry.invalidate(source)
                    return false
                }
                output.write(buffer, 0, read)
                connection.noteFirstByte(sourceReadyAtNanos)
                position += read
                remaining -= read
            }
            return true
        } finally {
            connection.endTransfer()
        }
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

    /** 一条连接结束的原因；打点与排障用同一套文案。 */
    private enum class EndReason(val label: String) {
        /** 传输停滞：看门狗主动断开。 */
        Stalled("停滞断开"),

        /** 客户端自己断开，或请求结束后的正常收尾。 */
        ClientClosed("客户端断开"),

        /** 服务关闭时统一断开。 */
        ServerClosed("关闭"),
    }

    /** 一条连接的传输状态；看门狗只按这里的读取时刻判定停滞，连接结束即从监控里摘掉。 */
    private class Connection(val socket: Socket) {

        /** 是否正在拷贝响应体：只有传输阶段判停滞，等建源与空闲长连接都不算。 */
        @Volatile
        var transferring = false

        /**
         * 最近一次读到新字节的时刻，也是当前这次读取的开始时刻；0 表示没有读在途。
         * 读在途超过 [STALL_TIMEOUT_MILLIS] 未返回，就说明这段时间完全没有新字节。
         */
        @Volatile
        var readStartedAtNanos = 0L

        @Volatile
        var readPosition = 0L

        @Volatile
        var readLength = 0

        /** 本次读取的停滞日志是否已经记过：单次读最多一条。 */
        val slowReadLogged = AtomicBoolean(false)

        /** 已被看门狗判定停滞：只断一次、只判一次。 */
        val stalled = AtomicBoolean(false)

        /** 是否已写过「收到请求」：长连接上的多个 Range 只记首条。 */
        val firstRequestLogged = AtomicBoolean(false)

        /** 连接结束原因；看门狗与服务关闭会先写它。 */
        @Volatile
        var endReason = EndReason.ClientClosed

        /** 连接结束那一行是否已经写出：停滞时由看门狗写，其余由处理线程收尾时写。 */
        val endLogged = AtomicBoolean(false)

        /** 建源完成到写出首字节的耗时；没有写出响应体时保持 null。 */
        @Volatile
        var firstByteMillis: Long? = null

        /** 正在拷贝的流；判停滞时按它给字节源打陈旧标记。 */
        @Volatile
        var activeEntry: Entry? = null

        fun beginTransfer(entry: Entry) {
            activeEntry = entry
            transferring = true
        }

        fun endTransfer() {
            transferring = false
            activeEntry = null
            readStartedAtNanos = 0L
        }

        /** 开始一次读取，返回这次读取的开始时刻。 */
        fun beginRead(position: Long, length: Int): Long {
            readPosition = position
            readLength = length
            slowReadLogged.set(false)
            val startedAtNanos = System.nanoTime()
            readStartedAtNanos = startedAtNanos
            return startedAtNanos
        }

        fun endRead() {
            readStartedAtNanos = 0L
        }


        fun noteFirstByte(sourceReadyAtNanos: Long) {
            if (firstByteMillis != null) return
            firstByteMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - sourceReadyAtNanos,
            ).coerceAtLeast(0L)
        }

        fun firstByteText(): String = firstByteMillis?.let { "${it}ms" } ?: "未写出"
    }

    /** 一次取源的结果；[startedBuild] 表示这一请求是否发起了本次重建，用于只记一条建源日志。 */
    private class SourceLookup(
        val source: ByteSource?,
        val failure: Throwable?,
        val startedBuild: Boolean,
    )

    /**
     * 一条流：opener 只在第一次用到时跑一次，之后复用同一个字节源。
     * 陈旧标记在退后台时打上，下一次请求关闭重建；失败废弃只认被废弃的那一条源。
     *
     * 重建不持锁也不占用请求线程：锁只保护「谁在重建」与 source 引用的切换，
     * 关旧源与建新源都在建源线程上做；请求最多等 [timeoutMillis]。
     */
    private class Entry(
        private val open: () -> ByteSource,
        private val runAsync: (() -> Unit) -> Unit,
        private val timeoutMillis: Long,
    ) {

        private val lock = Any()

        @Volatile
        private var source: ByteSource? = null

        /** 已登记陈旧：不打断正在进行的读取，下一次请求先关闭再重建。 */
        @Volatile
        private var stale = false

        /** 建源期间被要求关闭源：这一轮建好的源由它自己关掉，不发布。 */
        private var cleared = false

        @Volatile
        private var closed = false

        /** 在途重建；非空期间来的请求跟着它一起等结果，不另起一次建源。 */
        private var rebuilding: Rebuild? = null

        /** 当前是否已有可直接复用的字节源；只用于请求打点的命中标记。 */
        fun hasFreshSource(): Boolean = source != null && !stale

        /**
         * 取字节源：命中缓存、等待或发起一次重建。
         * 重建发起者与并发等待者拿到同一个结果，只有发起者标记 `startedBuild`，用于连接级打点。
         * 关闭后不再建立新源。
         */
        fun source(): SourceLookup {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var started = false
            // 内联块里要么给出本轮轮次，要么已经返回。
            val pending = synchronized(lock) {
                if (closed) return SourceLookup(null, IllegalStateException("流已关闭"), false)
                val inFlight = rebuilding
                if (inFlight != null) {
                    inFlight
                } else {
                    val current = source
                    if (current != null && !stale) return SourceLookup(current, null, false)
                    // 这次重建消费当前标记：空标记不该让第一次建源白建一次。
                    consumeStale()
                    val created = Rebuild()
                    rebuilding = created
                    started = true
                    created
                }
            }
            if (started) submitRebuild(pending)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L || !pending.finished.await(remaining, TimeUnit.NANOSECONDS)) {
                return SourceLookup(null, TimeoutException("重建字节源超时"), started)
            }
            pending.failure?.let { return SourceLookup(null, it, started) }
            val resolved = pending.result ?: return SourceLookup(null, IllegalStateException("流已关闭"), started)
            return SourceLookup(resolved, null, started)
        }

        /** 把这一轮重建交给建源线程；投递被拒时立即结算这一轮，请求按失败收尾。 */
        private fun submitRebuild(round: Rebuild) {
            val rejected = runCatching { runAsync { rebuild(round) } }.exceptionOrNull() ?: return
            round.failure = rejected
            synchronized(lock) { if (rebuilding === round) rebuilding = null }
            round.finished.countDown()
        }

        /**
         * 建源：关闭旧源与建立新源都不持锁，锁只在发布结果时短暂持有。
         * 建源期间收到陈旧标记时，建好的这一条立即作废再建一次；二次建源期间再来的标记留给下一次请求。
         * 建源期间这条流被关闭时，建出来的源在这里自己关掉。
         */
        private fun rebuild(round: Rebuild) {
            var result: ByteSource? = null
            var failure: Throwable? = null
            try {
                var expired: ByteSource? = null
                synchronized(lock) {
                    expired = source
                    source = null
                }
                // 旧源可能还有别的响应在读，关闭后那一条按读取失败收尾。
                expired?.let { runCatching { it.close() } }
                var created = open()
                if (consumeStale()) {
                    runCatching { created.close() }
                    created = open()
                }
                var kept = false
                synchronized(lock) {
                    if (!closed && !cleared) {
                        source = created
                        result = created
                        kept = true
                    }
                    cleared = false
                    rebuilding = null
                }
                if (!kept) runCatching { created.close() }
            } catch (thrown: Throwable) {
                failure = thrown
                synchronized(lock) { rebuilding = null }
            }
            round.failure = failure
            round.result = result
            round.finished.countDown()
        }

        /**
         * 关闭缓存源并清空，条目与地址保留。
         * 建源期间被关闭时由 [cleared] 让这一轮建好的源自己关掉，不会没人认领。
         */
        fun closeSource() {
            var current: ByteSource? = null
            synchronized(lock) {
                current = source
                source = null
                stale = false
                if (rebuilding != null) cleared = true
            }
            current?.let { runCatching { it.close() } }
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

        /** 关闭这条流：只改状态并关掉当前源，不等在途建源；建好的源由建源线程自己关掉。 */
        fun close() {
            var current: ByteSource? = null
            synchronized(lock) {
                closed = true
                current = source
                source = null
                stale = false
            }
            current?.let { runCatching { it.close() } }
        }

        /** 一轮重建：发起方与并发请求共用一个结果，[finished] 一定有且只触发一次。 */
        private class Rebuild {
            val finished = CountDownLatch(1)

            @Volatile
            var result: ByteSource? = null

            @Volatile
            var failure: Throwable? = null
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
