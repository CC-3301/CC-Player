package com.cc3301.ccplayer.streaming

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        // 单次读取块固定 64 KiB。
        assertEquals(64 * 1024, COPY_BUFFER_BYTES)

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
    fun `读取抛运行时异常后废弃字节源，下一次请求重新建立`() {
        var opens = 0
        val unstable = server.serve("smb://nas/media/超时.mkv") {
            opens++
            if (opens == 1) {
                FailingByteSource(IllegalStateException("Timeout expired"))
            } else {
                RecordingByteSource(payload)
            }
        }

        val failed = httpRequest(unstable)
        assertEquals(200, failed.statusCode)
        assertEquals(0, failed.body.size)

        // 这条字节源已不可用：重试建一条新的，不再打在同一条坏源上。
        val retried = httpRequest(unstable)
        assertEquals(200, retried.statusCode)
        assertArrayEquals(payload, retried.body)
        assertEquals(2, opens)
        assertArrayEquals(payload, httpRequest(url).body)
    }

    @Test
    fun `读到 0 后废弃字节源，下一次请求重新建立`() {
        var opens = 0
        val short = server.serve("smb://nas/media/半截源.mkv") {
            opens++
            if (opens == 1) ShortByteSource(payload) else RecordingByteSource(payload)
        }

        val half = httpRequest(short)
        assertEquals(200, half.statusCode)
        assertEquals(10, half.body.size)

        // 读不动不补零、不重试同一条源：下次请求换新源读全。
        assertArrayEquals(payload, httpRequest(short).body)
        assertEquals(2, opens)
    }

    @Test
    fun `丢弃已建立的字节源后下一次请求重新建立`() {
        httpRequest(url)
        assertEquals(1, opened)
        assertFalse(source.closed)

        server.discardSources()

        // 只打陈旧标记：正在进行的响应不受影响，下一次请求才关闭旧源并重建。
        assertFalse(source.closed)
        assertArrayEquals(payload, httpRequest(url).body)
        assertTrue(source.closed)
        assertEquals(2, opened)
    }

    @Test
    fun `首次请求前丢弃不额外多建一次`() {
        var opens = 0
        val first = RecordingByteSource(payload)
        val target = server.serve("smb://nas/media/首次建源.mkv") {
            opens++
            first
        }

        // 没有源时陈旧标记在建源前先消费：第一次建源就是这次请求的源，不多建一次。
        server.discardSources()

        assertArrayEquals(payload, httpRequest(target).body)
        assertEquals(1, opens)
        assertFalse(first.closed)
    }

    @Test
    fun `建源期间收到丢弃标记时建好后立即重建一次`() {
        var opens = 0
        val first = RecordingByteSource(payload)
        val second = RecordingByteSource(payload)
        val target = server.serve("smb://nas/media/建源并发.mkv") {
            opens++
            // 模拟首次建源阻塞期间退到后台。
            if (opens == 1) server.discardSources()
            if (opens == 1) first else second
        }

        assertArrayEquals(payload, httpRequest(target).body)
        assertEquals(2, opens)
        assertTrue(first.closed)
        assertFalse(second.closed)
    }

    @Test
    fun `二次建源期间再收到丢弃标记，下一次请求消费`() {
        var opens = 0
        val first = RecordingByteSource(payload)
        val second = RecordingByteSource(payload)
        val third = RecordingByteSource(payload)
        val target = server.serve("smb://nas/media/二次建源.mkv") {
            opens++
            when (opens) {
                // 首次建源期间落一次丢弃：建好后立即重建一次。
                1 -> {
                    server.discardSources()
                    first
                }
                // 二次建源期间再落一次：不在这里循环，留给下一次请求消费。
                2 -> {
                    server.discardSources()
                    second
                }
                else -> third
            }
        }

        assertArrayEquals(payload, httpRequest(target).body)
        assertEquals(2, opens)
        assertTrue(first.closed)
        assertFalse(second.closed)

        // 下一次请求消费遗留的标记：关闭二次建出来的源，再建一次。
        assertArrayEquals(payload, httpRequest(target).body)
        assertEquals(3, opens)
        assertTrue(second.closed)
        assertFalse(third.closed)
    }

    @Test
    fun `读取抛 Error 时连接按失败收尾且源不失效`() {
        var opens = 0
        var closed = false
        val target = server.serve("smb://nas/media/错误不失效.mkv") {
            opens++
            object : ByteSource {
                override val size: Long = payload.size.toLong()

                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                    throw FakeError("读取失败")

                override fun close() {
                    closed = true
                }
            }
        }

        val failed = httpRequest(target)
        // 连接按失败收尾：响应头已经发出，响应体被截断。
        assertEquals(200, failed.statusCode)
        assertEquals("1000", failed.header("Content-Length"))
        assertEquals(0, failed.body.size)

        // 下一次请求沿用同一条源：Error 不当作源失效，也没有被关闭。
        assertEquals(200, httpRequest(target).statusCode)
        assertEquals(1, opens)
        assertFalse(closed)
    }

    @Test
    fun `迟到的失败不关掉并发重建出来的新源`() {
        var opens = 0
        lateinit var target: String
        val fresh = RecordingByteSource(payload)
        val failing = object : ByteSource {
            override val size: Long = payload.size.toLong()

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                // 这次读取失败之前，另一次请求已经把字节源重建并读出完整响应。
                server.discardSources()
                assertArrayEquals(payload, httpRequest(target).body)
                return 0
            }

            override fun close() = Unit
        }
        target = server.serve("smb://nas/media/迟到失败.mkv") {
            opens++
            if (opens == 1) failing else fresh
        }

        httpRequest(target)

        // 只有被废弃的那一条才关闭：重建出来的新源留着，不被迟到的失败带走。
        assertEquals(2, opens)
        assertArrayEquals(payload, httpRequest(target).body)
        assertEquals(2, opens)
    }

    @Test
    fun `读取抛 Error 后监听仍存活`() {
        val failing = server.serve("smb://nas/media/读取错误.mkv") {
            FailingByteSource(FakeError("读取失败"))
        }

        assertEquals(200, httpRequest(failing).statusCode)
        assertArrayEquals(payload, httpRequest(url).body)
    }

    @Test
    fun `连接边界直接调用时运行时异常不逃逸`() {
        listOf(IllegalStateException("Timeout expired"), FakeError("读取失败"))
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
                throw FakeError("accept 失败")
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

    @Test
    fun `单次读取不越过字节源声明的上限`() {
        val limited = LimitRecordingByteSource(ByteArray(8 * 1024) { (it % 251).toByte() }, maxReadBytes = 1024)
        val target = server.serve("smb://nas/media/限读.mkv") { limited }

        val response = httpRequest(target)

        assertArrayEquals(limited.bytes, response.body)
        assertTrue(limited.reads.isNotEmpty())
        assertTrue("单次读取越过上限：${limited.reads}", limited.reads.all { it <= 1024 })
    }

    @Test(timeout = 10_000L)
    fun `重建字节源超时后请求返回 503 而不是一直等`() {
        val release = CountDownLatch(1)
        val slow = LoopbackStreamServer(rebuildTimeoutMillis = 100)
        try {
            val target = slow.serve("smb://nas/media/慢重连.mkv") {
                // 模拟 SMB 重连卡住：建源一直不返回。
                release.await()
                RecordingByteSource(payload)
            }

            val started = System.nanoTime()
            val response = httpRequest(target)
            val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            // 超时按本次请求失败收尾：回 503，等待上限就是配置的 100ms，不会挂死在建源上。
            assertEquals(503, response.statusCode)
            assertTrue("等了 $waitedMillis ms", waitedMillis < 1_000)
        } finally {
            release.countDown()
            slow.close()
        }
    }

    @Test(timeout = 10_000L)
    fun `重建期间关闭立即返回，建好的源由建源线程自己关掉`() {
        val release = CountDownLatch(1)
        val building = CountDownLatch(1)
        val builtClosed = AtomicBoolean(false)
        val built = object : ByteSource {
            override val size: Long = payload.size.toLong()

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = 0

            override fun close() {
                builtClosed.set(true)
            }
        }
        val fresh = LoopbackStreamServer(rebuildTimeoutMillis = 10_000)
        val target = fresh.serve("smb://nas/media/在途建源.mkv") {
            building.countDown()
            release.await()
            built
        }
        val request = Thread { runCatching { httpRequest(target) } }.apply {
            isDaemon = true
            start()
        }
        try {
            assertTrue("建源没有开始", building.await(2_000, TimeUnit.MILLISECONDS))

            val started = System.nanoTime()
            fresh.close()
            val closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            // 关闭只改状态并返回，不等在建源上：建源还卡着，源也确实还没建出来。
            assertTrue("关闭耗时 $closeMillis ms", closeMillis < 1_000)
            assertFalse("关闭不该等建源返回", builtClosed.get())

            // 后台建源返回后自己关掉建出来的源。
            release.countDown()
            awaitCondition { builtClosed.get() }
            assertTrue(builtClosed.get())
        } finally {
            release.countDown()
            runCatching { fresh.close() }
            request.join(5_000)
        }
    }

    @Test(timeout = 10_000L)
    fun `建源线程已关时请求当场失败，不把超时走满`() {
        val rejected = Executors.newSingleThreadExecutor().apply { shutdown() }
        val fresh = LoopbackStreamServer(rebuildTimeoutMillis = 10_000, sourceOpener = rejected)
        try {
            val target = fresh.serve("smb://nas/media/拒绝.mkv") { RecordingByteSource(payload) }

            val first = System.nanoTime()
            val response = httpRequest(target)
            val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - first)

            // 投递被拒就当场结算这一轮：回 503，不是等满 10 秒超时。
            assertEquals(503, response.statusCode)
            assertTrue("等了 $waitedMillis ms", waitedMillis < 1_000)

            // 在途轮次已清空：下一次请求同样当场失败，不会挂在前一轮上。
            assertEquals(503, httpRequest(target).statusCode)
        } finally {
            fresh.close()
        }
    }

    @Test(timeout = 15_000L)
    fun `读到一半停住时按 3 秒判停滞并截断响应体`() {
        val diagnostics = RecordingDiagnostics()
        val logged = LoopbackStreamServer(diagnostics = diagnostics)
        val big = ByteArray(64 * 1024) { (it % 251).toByte() }
        val gate = CountDownLatch(1)
        try {
            val target = logged.serve("smb://nas/media/停滞.mkv") {
                StallingByteSource(big, firstChunkBytes = 32 * 1024, gate = gate)
            }
            val started = System.nanoTime()

            KeepAliveConnection(target, readTimeoutMillis = 10_000).use { client ->
                val response = client.request()

                // 先写出首块 32 KiB 再卡住：响应体是部分字节，内容与源一致。
                assertEquals(200, response.statusCode)
                assertEquals("65536", response.header("Content-Length"))
                assertTrue("响应体太小：${response.body.size}", response.body.size >= 32 * 1024)
                assertTrue("响应体不应写满：${response.body.size}", response.body.size < big.size)
                assertArrayEquals(big.copyOf(response.body.size), response.body)
            }

            // 判据是读在途超过 3 秒：耗时不能明显短于阈值。
            val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("断开耗时 $waitedMillis ms", waitedMillis >= STALL_TIMEOUT_MILLIS)
            assertTrue("断开耗时 $waitedMillis ms", waitedMillis < 8_000)
            awaitCondition { diagnostics.messages.any { it.contains("原因: 停滞断开") } }
        } finally {
            gate.countDown()
            logged.close()
        }
    }

    @Test(timeout = 25_000L)
    fun `慢但持续有新字节的大块读取不触发看门狗`() {
        val big = ByteArray(256 * 1024) { (it % 251).toByte() }
        val chunked = server.serve("smb://nas/media/慢速.mkv") {
            SlowChunkByteSource(big, chunkBytes = COPY_BUFFER_BYTES, pauseMillis = 900)
        }

        val started = System.nanoTime()
        val response = httpRequest(chunked)
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        // 每块 64 KiB 都在停滞阈值内返回，整段传输超过 3 秒：慢但持续有数据不触发看门狗。
        assertArrayEquals(big, response.body)
        assertTrue("总耗时 $waitedMillis ms", waitedMillis > STALL_TIMEOUT_MILLIS)
    }

    @Test(timeout = 25_000L)
    fun `停滞断开后作废字节源，下一次请求重建`() {
        val big = ByteArray(64 * 1024) { (it % 251).toByte() }
        val gate = CountDownLatch(1)
        var opens = 0
        val target = server.serve("smb://nas/media/停滞重建.mkv") {
            opens++
            if (opens == 1) {
                StallingByteSource(big, firstChunkBytes = 32 * 1024, gate = gate)
            } else {
                RecordingByteSource(big)
            }
        }

        // 第一次请求卡在后半段：看门狗断开连接并把这条源标记陈旧。
        KeepAliveConnection(target, readTimeoutMillis = 10_000).use { it.request() }
        assertEquals(1, opens)

        // 下一次请求重建：建源线程关掉卡死的旧源，新源读到完整字节。
        val second = httpRequest(target)
        assertArrayEquals(big, second.body)
        assertEquals(2, opens)
        assertEquals(0L, gate.count)
    }

    @Test
    fun `单次读取块固定 64 KiB`() {
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        val recording = LimitRecordingByteSource(bytes)
        val target = server.serve("smb://nas/media/读块.mkv") { recording }

        assertArrayEquals(bytes, httpRequest(target).body)

        // 源不声明单次读上限：每次读取都取固定 64 KiB。
        assertEquals(listOf(64 * 1024, 64 * 1024, 64 * 1024, 64 * 1024), recording.reads)
    }

    @Test(timeout = 20_000L)
    fun `空闲长连接不被看门狗断开`() {
        KeepAliveConnection(url, readTimeoutMillis = 6_000).use { client ->
            assertArrayEquals(payload, client.request().body)

            // 没有传输就没有停滞：空闲长连接保持可用，下一请求直接命中已有源。
            Thread.sleep(STALL_TIMEOUT_MILLIS.toInt() + 500L)

            assertArrayEquals(payload, client.request().body)
        }
    }

    @Test
    fun `连接级打点记录收到请求、建源与连接结束`() {
        val diagnostics = RecordingDiagnostics()
        val logged = LoopbackStreamServer(diagnostics = diagnostics)
        try {
            val target = logged.serve("smb://nas/media/打点.mkv") { RecordingByteSource(payload) }
            KeepAliveConnection(target).use { client ->
                assertArrayEquals(payload, client.request().body)
                assertArrayEquals(payload, client.request().body)
            }
            awaitCondition { diagnostics.messages.any { it.contains("连接结束") } }

            val lines = diagnostics.messages.filter { it.startsWith("回环流") }
            // 一条连接只写三行：首个请求、建源与连接结束；第二个 Range 不再另起一行。
            assertEquals(3, lines.size)
            assertTrue(lines[0].contains("收到请求"))
            assertTrue(lines[0].contains("Range: -"))
            assertTrue(lines[0].contains("源: 新建"))
            assertTrue(lines[1].contains("建源结束"))
            assertTrue(lines[1].contains("成功"))
            assertTrue(lines[2].contains("原因: 客户端断开"))
            assertTrue(lines[2].contains("首字节:"))
        } finally {
            logged.close()
        }
    }

    @Test(timeout = 15_000L)
    fun `并发等待同一轮重建时只由发起者写建源结束`() {
        val diagnostics = RecordingDiagnostics()
        val logged = LoopbackStreamServer(diagnostics = diagnostics)
        val release = CountDownLatch(1)
        val building = CountDownLatch(1)
        try {
            val target = logged.serve("smb://nas/media/并发建源.mkv") {
                building.countDown()
                release.await()
                RecordingByteSource(payload)
            }
            val first = Thread { runCatching { httpRequest(target) } }.apply {
                isDaemon = true
                start()
            }
            assertTrue("建源没有开始", building.await(2_000, TimeUnit.MILLISECONDS))
            val second = Thread { runCatching { httpRequest(target) } }.apply {
                isDaemon = true
                start()
            }
            // 让第二个请求进入等待同一轮重建。
            Thread.sleep(200)
            release.countDown()
            first.join(5_000)
            second.join(5_000)

            val buildLines = diagnostics.messages.filter { it.contains("建源结束") }
            assertEquals(1, buildLines.size)
            assertTrue(diagnostics.messages.any { it.contains("源: 新建") })
        } finally {
            release.countDown()
            logged.close()
        }
    }

    @Test
    fun `单次读取超过 1 秒记录一条停滞日志`() {
        val diagnostics = RecordingDiagnostics()
        val logged = LoopbackStreamServer(diagnostics = diagnostics)
        try {
            val target = logged.serve("smb://nas/media/慢读.mkv") {
                SlowFirstReadByteSource(payload, pauseMillis = SLOW_READ_LOG_MILLIS + 200L)
            }

            assertArrayEquals(payload, httpRequest(target).body)

            // 每次读取最多一条：不逐读记。
            val stallLines = diagnostics.messages.filter { it.contains("读取停滞") }
            assertEquals(1, stallLines.size)
            assertTrue(stallLines.single().contains("位置 0"))
        } finally {
            logged.close()
        }
    }

    @Test
    fun `建源失败记录耗时与异常类名`() {
        val diagnostics = RecordingDiagnostics()
        val logged = LoopbackStreamServer(diagnostics = diagnostics)
        try {
            val target = logged.serve("smb://nas/media/坏文件.mkv") {
                throw IllegalStateException("连不上")
            }

            assertEquals(503, httpRequest(target).statusCode)

            val buildLine = diagnostics.messages.single { it.contains("建源结束") }
            assertTrue(buildLine.contains("失败: IllegalStateException"))
            assertTrue(buildLine.contains("耗时:"))
        } finally {
            logged.close()
        }
    }

    private fun awaitCondition(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("等待超时", condition())
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

/** 记录每次请求长度的字节源，并声明单次读上限，用于验证按上限分块。 */
class LimitRecordingByteSource(val bytes: ByteArray, override val maxReadBytes: Int = 0) : ByteSource {

    /** 每次读取请求的长度，按发生顺序。 */
    val reads = mutableListOf<Int>()

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        reads += length
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() = Unit
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

/** 测试用 Error 子类：与非异常的断言失败区分开。 */
class FakeError(message: String) : Error(message)

/** 记录诊断日志的出口：连接级打点按内容断言。 */
private class RecordingDiagnostics : DiagnosticsLog {

    override val enabled: Boolean = true

    val messages = CopyOnWriteArrayList<String>()

    override fun log(message: String) {
        messages += message
    }
}

/** 每次读取都先停一下再返回一小段：模拟慢但持续有新字节的传输。 */
private class SlowChunkByteSource(
    private val bytes: ByteArray,
    private val chunkBytes: Int,
    private val pauseMillis: Long,
) : ByteSource {

    override val size: Long = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        Thread.sleep(pauseMillis)
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), chunkBytes.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() = Unit
}

/** 先吐出 [firstChunkBytes] 字节，随后卡住直到被关闭：模拟传输中途停滞。 */
private class StallingByteSource(
    private val bytes: ByteArray,
    private val firstChunkBytes: Int,
    private val gate: CountDownLatch,
) : ByteSource {

    override val size: Long = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position == 0L) {
            val count = minOf(firstChunkBytes.toLong(), length.toLong(), bytes.size.toLong()).toInt()
            bytes.copyInto(buffer, offset, 0, count)
            return count
        }
        gate.await()
        return 0
    }

    override fun close() {
        gate.countDown()
    }
}

/** 首次读取故意超过停滞阈值，之后正常返回。 */
private class SlowFirstReadByteSource(
    private val bytes: ByteArray,
    private val pauseMillis: Long,
) : ByteSource {

    private var firstRead = true

    override val size: Long = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (firstRead) {
            firstRead = false
            Thread.sleep(pauseMillis)
        }
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
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
