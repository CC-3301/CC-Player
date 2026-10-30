package com.cc3301.ccplayer.streaming

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class LoopbackStreamServerTest {

    private val payload = ByteArray(1000) { (it % 251).toByte() }
    private val server = LoopbackStreamServer()
    private val source = RecordingByteSource(payload)
    private var opened = 0
    private val url = server.serve("smb://nas/media/电影.mkv") {
        opened++
        source
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `不带 Range 的 GET 返回 200 与全量字节`() {
        val response = httpRequest(url)

        assertEquals(200, response.statusCode)
        assertEquals("1000", response.header("Content-Length"))
        assertEquals("bytes", response.header("Accept-Ranges"))
        assertNull(response.header("Content-Range"))
        assertArrayEquals(payload, response.body)
    }

    @Test
    fun `带 Range 的 GET 返回 206 与对应字节`() {
        val response = httpRequest(url, range = "bytes=100-199")

        assertEquals(206, response.statusCode)
        assertEquals("bytes 100-199/1000", response.header("Content-Range"))
        assertEquals("100", response.header("Content-Length"))
        assertArrayEquals(payload.copyOfRange(100, 200), response.body)
    }

    @Test
    fun `开区间 Range 一直读到文件末尾`() {
        val response = httpRequest(url, range = "bytes=990-")

        assertEquals(206, response.statusCode)
        assertArrayEquals(payload.copyOfRange(990, 1000), response.body)
    }

    @Test
    fun `Range 越界返回 416`() {
        val response = httpRequest(url, range = "bytes=5000-6000")

        assertEquals(416, response.statusCode)
        assertEquals("bytes */1000", response.header("Content-Range"))
        assertEquals(0, response.body.size)
    }

    @Test
    fun `HEAD 只返回头部`() {
        val response = httpRequest(url, method = "HEAD")

        assertEquals(200, response.statusCode)
        assertEquals("1000", response.header("Content-Length"))
        assertEquals(0, response.body.size)
    }

    @Test
    fun `未知流向返回 404`() {
        val response = httpRequest("http://127.0.0.1:${server.port}/4040")

        assertEquals(404, response.statusCode)
    }

    @Test
    fun `同一个键恒得同一个地址`() {
        assertEquals(url, server.serve("smb://nas/media/电影.mkv") { opened++; source })
    }

    @Test
    fun `字节源在第一个请求到来时才建立，之后复用`() {
        assertEquals(0, opened)

        httpRequest(url)
        httpRequest(url)

        assertEquals(1, opened)
    }

    @Test
    fun `字节源建立失败时返回 503`() {
        val failing = server.serve("smb://nas/media/坏文件.mkv") { throw IllegalStateException("连不上") }

        assertEquals(503, httpRequest(failing).statusCode)
    }

    @Test
    fun `关闭后已建立的字节源一并关闭`() {
        httpRequest(url)

        server.close()

        assertEquals(true, source.closed)
    }

    @Test
    fun `读取抛运行时异常后监听仍存活且不复用重建`() {
        var opens = 0
        val unstable = server.serve("smb://nas/media/超时.mkv") {
            opens++
            FailingByteSource(IllegalStateException("Timeout expired"))
        }

        val failed = httpRequest(unstable)
        assertEquals(200, failed.statusCode)
        assertEquals(0, failed.body.size)

        // 同一个流保留原字节源，失败就再次快速失败；监听线程仍可服务其它流。
        assertEquals(200, httpRequest(unstable).statusCode)
        assertEquals(1, opens)
        assertArrayEquals(payload, httpRequest(url).body)
    }

    @Test
    fun `读取抛 Error 后监听仍存活`() {
        val failing = server.serve("smb://nas/media/读取错误.mkv") {
            FailingByteSource(AssertionError("读取失败"))
        }

        assertEquals(200, httpRequest(failing).statusCode)
        assertArrayEquals(payload, httpRequest(url).body)
    }

    @Test
    fun `连接边界直接调用时运行时异常不逃逸`() {
        listOf(IllegalStateException("Timeout expired"), AssertionError("读取失败"))
            .forEachIndexed { index, failure ->
                val target = server.serve("smb://nas/media/边界-$index.mkv") {
                    FailingByteSource(failure)
                }

                val response = requestThroughBoundary(target)

                assertEquals(200, response.statusCode)
                assertEquals(0, response.body.size)
            }
    }

    @Test
    fun `客户端提前断开不失效字节源`() {
        val big = ByteArray(1 shl 20) { (it % 251).toByte() }
        var opens = 0
        var target = ""
        ServerSocket(0).use { listener ->
            Socket(InetAddress.getByName("127.0.0.1"), listener.localPort).use { client ->
                listener.accept().use { connection ->
                    val aborting = object : ByteSource {
                        override val size: Long = big.size.toLong()

                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                            if (!client.isClosed) {
                                client.setSoLinger(true, 0)
                                client.close()
                            }
                            if (position >= big.size) return 0
                            val count = minOf(length.toLong(), big.size - position).toInt()
                            big.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                            return count
                        }

                        override fun close() = Unit
                    }
                    target = server.serve("smb://nas/media/断开.mkv") {
                        opens++
                        aborting
                    }
                    client.getOutputStream().apply {
                        write("GET ${URI(target).path} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }

                    // 写失败只结束本次连接；字节源保持缓存。
                    server.handle(connection)
                }
            }
        }

        assertArrayEquals(big, httpRequest(target).body)
        assertEquals(1, opens)
    }

    @Test
    fun `接受线程遇 Error 时关闭监听`() {
        val gate = CountDownLatch(1)
        val before = acceptThreads()
        val fresh = LoopbackStreamServer(
            acceptSocket = {
                gate.await()
                throw AssertionError("accept 失败")
            },
        )
        val address = fresh.serve("key") { RecordingByteSource(payload) }
        val acceptor = (acceptThreads() - before).single()
        gate.countDown()
        acceptor.join(2_000)

        assertFalse(acceptor.isAlive)
        assertThrows(IOException::class.java) { httpRequest(address) }
    }

    @Test
    fun `连续接受失败达到上限后关闭监听`() {
        val gate = CountDownLatch(1)
        val failures = AtomicInteger()
        val before = acceptThreads()
        val fresh = LoopbackStreamServer(
            acceptSocket = {
                if (failures.getAndIncrement() == 0) gate.await()
                throw IllegalStateException("accept 失败")
            },
        )
        val address = fresh.serve("key") { RecordingByteSource(payload) }
        val acceptor = (acceptThreads() - before).single()
        gate.countDown()
        acceptor.join(2_000)

        assertFalse(acceptor.isAlive)
        assertEquals(LOOPBACK_ACCEPT_FAILURE_LIMIT, failures.get())
        assertThrows(IOException::class.java) { httpRequest(address) }
    }

    @Test
    fun `拷贝块固定为一兆字节`() {
        assertEquals(1024 * 1024, COPY_BUFFER_BYTES)
    }

    @Test
    fun `关闭后接受线程退出`() {
        val before = acceptThreads()
        val fresh = LoopbackStreamServer()
        val acceptor = (acceptThreads() - before).single()

        fresh.close()
        acceptor.join(2_000)

        assertFalse(acceptor.isAlive)
    }

    @Test
    fun `同一条连接可以连续处理多条请求`() {
        KeepAliveConnection(url).use { client ->
            val full = client.request()
            val ranged = client.request(range = "bytes=10-19")

            assertEquals("keep-alive", full.header("Connection"))
            assertEquals(200, full.statusCode)
            assertArrayEquals(payload, full.body)
            assertEquals(206, ranged.statusCode)
            assertArrayEquals(payload.copyOfRange(10, 20), ranged.body)
            // 两条请求共用同一个字节源。
            assertEquals(1, opened)
        }
    }

    @Test
    fun `客户端要求关闭时响应声明关闭并结束连接`() {
        KeepAliveConnection(url).use { client ->
            val response = client.request(close = true)

            assertEquals("close", response.header("Connection"))
            assertArrayEquals(payload, response.body)
            assertEquals(-1, client.readAfterEnd())
        }
    }

    @Test
    fun `未知流也保持长连接并补齐长度`() {
        KeepAliveConnection("http://127.0.0.1:${server.port}/4040").use { client ->
            val missing = client.request()
            val alive = client.request(url)

            assertEquals(404, missing.statusCode)
            assertEquals("0", missing.header("Content-Length"))
            assertEquals(200, alive.statusCode)
            assertArrayEquals(payload, alive.body)
        }
    }

    @Test
    fun `响应体没写满时结束连接`() {
        val short = server.serve("smb://nas/media/半截.mkv") { ShortByteSource(payload) }
        KeepAliveConnection(short).use { client ->
            val response = client.request()

            assertEquals(200, response.statusCode)
            assertEquals("1000", response.header("Content-Length"))
            assertTrue(response.body.size < payload.size)
            // 帧边界已坏：这条连接不再承载下一条请求。
            assertEquals(-1, client.readAfterEnd())
        }
    }

    @Test
    fun `关闭时断开已建立的连接并结束处理线程`() {
        val before = connectionThreads()
        KeepAliveConnection(url).use { client ->
            assertEquals(200, client.request().statusCode)
            val worker = (connectionThreads() - before).single()

            server.close()

            // 阻塞在请求头上的处理线程随连接断开退出，不留下残留线程。
            assertEquals(-1, client.readAfterEnd())
            worker.join(2_000)
            assertFalse(worker.isAlive)
        }
    }

    private fun requestThroughBoundary(url: String): HttpResponse {
        val path = URI(url).path
        ServerSocket(0).use { listener ->
            Socket(InetAddress.getByName("127.0.0.1"), listener.localPort).use { client ->
                listener.accept().use { connection ->
                    client.getOutputStream().apply {
                        write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    server.handle(connection)
                    return HttpResponse.parse(client.getInputStream().readBytes())
                }
            }
        }
    }

    private fun connectionThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys
            .filter { it.name == "loopback-stream-connection" && it.isAlive }
            .toSet()

    private fun acceptThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys
            .filter { it.name == "loopback-stream-accept" && it.isAlive }
            .toSet()
}

/** 测试用的长连接客户端：一条连接上按 Content-Length 逐条读回响应。 */
class KeepAliveConnection(private val url: String, readTimeoutMillis: Int = 3_000) : Closeable {

    private val socket = Socket(InetAddress.getByName(URI(url).host), URI(url).port).apply {
        soTimeout = readTimeoutMillis
    }
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    /** 在 [target] 指向的流上发一条请求并读回这条响应。 */
    fun request(target: String = url, range: String? = null, close: Boolean = false): HttpResponse {
        val uri = URI(target)
        val head = buildString {
            append("GET ${uri.path} HTTP/1.1\r\n")
            append("Host: ${uri.host}\r\n")
            if (range != null) append("Range: $range\r\n")
            if (close) append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray())
        output.flush()
        return readResponse()
    }

    /** 读一条响应；响应体按 Content-Length 读，不用连接关闭来界定。 */
    private fun readResponse(): HttpResponse {
        val head = readHead()
        val length = head.split("\r\n")
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) break
            read += count
        }
        return HttpResponse.parse(head.toByteArray() + body.copyOf(read))
    }

    /** 读到空行为止，返回含空行的响应头文本。 */
    private fun readHead(): String {
        val head = StringBuilder()
        var matched = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) break
            head.append(byte.toChar())
            matched = when {
                (matched == 0 || matched == 2) && byte == 13 -> matched + 1
                (matched == 1 || matched == 3) && byte == 10 -> matched + 1
                else -> 0
            }
            if (matched == 4) break
        }
        return head.toString()
    }

    /** 再读一个字节看连接是否已结束；已结束返回 -1。 */
    fun readAfterEnd(): Int = input.read()

    override fun close() {
        runCatching { socket.close() }
    }
}

/** 记录是否被关闭的字节源。 */
class RecordingByteSource(private val bytes: ByteArray) : ByteSource, Closeable {

    var closed = false

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() {
        closed = true
    }
}

/** 只读出前 [readable] 字节的字节源，之后读取返回 0，模拟读到一半读不动。 */
class ShortByteSource(private val bytes: ByteArray, private val readable: Int = 10) : ByteSource {

    override val size: Long = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= readable) return 0
        val count = minOf(length.toLong(), readable - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() = Unit
}

/** 每次读取都抛出指定异常，模拟网络读超时等失败。 */
class FailingByteSource(private val failure: Throwable) : ByteSource {

    override val size: Long = 1000

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = throw failure

    override fun close() = Unit
}
