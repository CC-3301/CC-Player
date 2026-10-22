package com.cc3301.ccplayer.streaming

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun `关闭后接受线程退出`() {
        val before = acceptThreads()
        val fresh = LoopbackStreamServer()
        val acceptor = (acceptThreads() - before).single()

        fresh.close()
        acceptor.join(2_000)

        assertFalse(acceptor.isAlive)
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

    private fun acceptThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys
            .filter { it.name == "loopback-stream-accept" && it.isAlive }
            .toSet()
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

/** 每次读取都抛出指定异常，模拟网络读超时等失败。 */
class FailingByteSource(private val failure: Throwable) : ByteSource {

    override val size: Long = 1000

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = throw failure

    override fun close() = Unit
}
