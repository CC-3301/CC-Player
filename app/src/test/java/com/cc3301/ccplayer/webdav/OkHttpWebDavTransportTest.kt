package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OkHttpWebDavTransportTest {

    private val transport = OkHttpWebDavTransport()

    private val payload = ByteArray(4096) { (it % 241).toByte() }

    private val listing = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/dav/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/%E5%89%A7%E9%9B%86/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/%E7%94%B5%E5%BD%B1.mkv</d:href>
            <d:propstat>
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength>3072</d:getcontentlength>
                <d:getlastmodified>Wed, 15 Nov 2023 12:34:56 GMT</d:getlastmodified>
              </d:prop>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `列目录发 PROPFIND，带 Depth 与 Basic 凭据，响应解析成条目`() = runTest {
        FakeWebDavServer(listing = listing).use { server ->
            val entries = transport.list(server.root, "cc", "secret")

            assertEquals(
                listOf(
                    WebDavEntry(name = "剧集", isDirectory = true, size = 0, lastModified = 0),
                    WebDavEntry(name = "电影.mkv", isDirectory = false, size = 3072, lastModified = 1_700_051_696_000),
                ),
                entries,
            )
            val request = server.received().single()
            assertEquals("PROPFIND", request.method)
            assertEquals("/dav/", request.path)
            assertEquals("1", request.header("Depth"))
            assertEquals(Credentials.basic("cc", "secret"), request.header("Authorization"))
        }
    }

    @Test
    fun `地址里的中文与空格按 UTF-8 百分号编码发出`() = runTest {
        FakeWebDavServer(listing = listing).use { server ->
            transport.list("${server.root}/剧集 第二季", "cc", "secret")

            assertEquals("/dav/%E5%89%A7%E9%9B%86%20%E7%AC%AC%E4%BA%8C%E5%AD%A3/", server.received().single().path)
        }
    }

    @Test
    fun `目录地址已带末尾斜杠时不重复补斜杠`() = runTest {
        FakeWebDavServer(listing = listing).use { server ->
            transport.list("${server.root}/", "cc", "secret")

            assertEquals("/dav/", server.received().single().path)
        }
    }

    @Test
    fun `列目录状态码非 2xx 归到来源失败原因`() = runTest {
        FakeWebDavServer(listing = "", propfindStatus = 401).use { server ->
            val exception = runCatching { transport.list(server.root, "cc", "错的") }.exceptionOrNull()

            assertEquals(MediaFailure.Authentication, (exception as MediaSourceException).failure)
        }
    }

    @Test
    fun `连不上服务器归为不可达`() = runTest {
        val url = closedPortUrl()

        val exception = runCatching { transport.list(url, "cc", "secret") }.exceptionOrNull()

        assertEquals(MediaFailure.Unreachable, (exception as MediaSourceException).failure)
    }

    @Test
    fun `大小未知时打开就发探测请求，长度取自响应`() = runTest {
        FakeWebDavServer(bytes = payload).use { server ->
            transport.openRead("${server.root}/电影.mkv", "cc", "secret", size = 0).use { handle ->
                val buffer = ByteArray(16)

                assertEquals(payload.size.toLong(), handle.size)
                assertEquals(16, handle.readAt(0, buffer, 0, 16))
                assertArrayEquals(payload.copyOfRange(0, 16), buffer)
                assertEquals("bytes=0-", server.received().single().header("Range"))
            }
        }
    }

    @Test
    fun `连续读沿用同一个流，跳读重发带新起点的 Range`() = runTest {
        FakeWebDavServer(bytes = payload).use { server ->
            transport.openRead("${server.root}/电影.mkv", "cc", "secret", size = 0).use { handle ->
                val buffer = ByteArray(64)
                handle.readAt(0, buffer, 0, 64)
                handle.readAt(64, buffer, 0, 64)

                assertEquals(1, server.received().size)

                handle.readAt(2000, buffer, 0, 64)

                assertEquals(listOf("bytes=0-", "bytes=2000-"), server.received().map { it.header("Range") })
            }
        }
    }

    @Test
    fun `读到的位置超过文件长度时返回 0`() = runTest {
        FakeWebDavServer(bytes = payload).use { server ->
            transport.openRead("${server.root}/电影.mkv", "cc", "secret", size = 0).use { handle ->
                assertEquals(0, handle.readAt(payload.size.toLong(), ByteArray(16), 0, 16))
            }
        }
    }

    @Test
    fun `大小未知时服务器忽略 Range 仍按顺序丢弃到请求位置`() = runTest {
        FakeWebDavServer(bytes = payload, ignoreRange = true).use { server ->
            transport.openRead("${server.root}/电影.mkv", "cc", "secret", size = 0).use { handle ->
                val buffer = ByteArray(32)

                assertEquals(32, handle.readAt(2000, buffer, 0, 32))
                assertArrayEquals(payload.copyOfRange(2000, 2032), buffer)
            }
        }
    }

    @Test
    fun `服务器没给出长度时打开失败`() = runTest {
        FakeWebDavServer(bytes = payload, withoutLength = true).use { server ->
            val exception = runCatching { transport.openRead("${server.root}/电影.mkv", "cc", "secret", size = 0) }.exceptionOrNull()

            assertEquals(MediaFailure.Unknown, (exception as MediaSourceException).failure)
        }
    }

    @Test
    fun `打开文件状态码非 2xx 归到来源失败原因`() = runTest {
        FakeWebDavServer(bytes = payload, getStatus = 404).use { server ->
            val exception = runCatching { transport.openRead("${server.root}/电影.mkv", "cc", "secret", size = 0) }.exceptionOrNull()

            assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
        }
    }

    @Test
    fun `关闭句柄后阻塞读取快速失败且不再发起请求`() = runTest {
        FakeWebDavServer(bytes = payload, stallBody = true).use { server ->
            val handle = transport.openRead("${server.root}/电影.mkv", "cc", "secret", size = 0)
            // 首个 GET 已发出响应头并停在正文前。
            assertTrue("首个 GET 应停在正文前", server.awaitStalledBody(1))

            val failure = AtomicReference<Throwable?>()
            val reading = Thread {
                // 跳读会发第二个 GET；第二个响应也停在正文前，读线程阻塞在这里。
                failure.set(runCatching { handle.readAt(2000, ByteArray(16), 0, 16) }.exceptionOrNull())
            }
            reading.start()
            assertTrue("第二个 GET 的响应头应已发出", server.awaitStalledBody(2))

            val startedAt = System.nanoTime()
            handle.close()
            reading.join(TimeUnit.SECONDS.toMillis(2))

            assertFalse("关闭后阻塞读取应立即退出", reading.isAlive)
            assertNotNull("关闭后的读取应失败", failure.get())
            assertTrue(
                "关闭不应等待读取超时",
                System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(2),
            )

            val afterClose = runCatching { handle.readAt(4000, ByteArray(16), 0, 16) }.exceptionOrNull()
            assertNotNull("关闭后读取必须直接失败", afterClose)
            assertEquals("关闭后不得再发起 GET", 2, server.received().size)
        }
    }

    @Test
    fun `等响应头时关闭句柄能快速退出且不再发起请求`() = runTest {
        FakeWebDavServer(bytes = payload, stallHeadersAfterFirst = true).use { server ->
            val handle = transport.openRead("${server.root}/电影.mkv", "cc", "secret", size = 0)
            val failure = AtomicReference<Throwable?>()
            val reading = Thread {
                // 跳读会发第二个 GET，服务器迟迟不回响应头，读线程停在等响应头。
                failure.set(runCatching { handle.readAt(2000, ByteArray(16), 0, 16) }.exceptionOrNull())
            }
            reading.start()
            assertTrue("第二个 GET 应已到达并等响应头", server.awaitStalledHeaders(1))

            val startedAt = System.nanoTime()
            handle.close()
            reading.join(TimeUnit.SECONDS.toMillis(2))

            assertFalse("关闭后等响应头的读取应立即退出", reading.isAlive)
            assertNotNull("关闭后的读取应失败", failure.get())
            assertTrue(
                "关闭不应等待读超时",
                System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(2),
            )

            val afterClose = runCatching { handle.readAt(4000, ByteArray(16), 0, 16) }.exceptionOrNull()
            assertNotNull("关闭后读取必须直接失败", afterClose)
            assertEquals("关闭后不得再发起 GET", 2, server.received().size)
        }
    }

    @Test
    fun `已知大小时打开不发请求，首个读取才发带新起点的 Range`() = runTest {
        FakeWebDavServer(bytes = payload).use { server ->
            transport.openRead("${server.root}/电影.mkv", "cc", "secret", size = payload.size.toLong()).use { handle ->
                assertEquals("打开句柄不应为取长度发请求", 0, server.received().size)

                val buffer = ByteArray(16)
                assertEquals(16, handle.readAt(1000, buffer, 0, 16))
                assertArrayEquals(payload.copyOfRange(1000, 1016), buffer)
                assertEquals("bytes=1000-", server.received().single().header("Range"))
            }
        }
    }

    @Test
    fun `服务器忽略 Range 时抽帧读取抛区间不支持`() = runTest {
        FakeWebDavServer(bytes = payload, ignoreRange = true).use { server ->
            val handle = transport.openRead("${server.root}/电影.mkv", "cc", "secret", size = payload.size.toLong())
            val failure = runCatching { handle.readAt(1000, ByteArray(16), 0, 16) }.exceptionOrNull()
            handle.close()

            // 按位置读拿不到目标字节时不再从文件开头丢弃，由调用方按网络源记下结论。
            assertTrue(failure is WebDavRangeUnsupportedException)
            assertEquals(1, server.received().size)
        }
    }

    /** 监听后立刻关闭的端口：连上去必然被拒。 */
    private fun closedPortUrl(): String {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/dav"
        socket.close()
        return url
    }
}
