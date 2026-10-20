package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

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
    fun `打开文件按 Range 发 GET，长度取自响应`() = runTest {
        FakeWebDavServer(bytes = payload).use { server ->
            transport.openRead("${server.root}/电影.mkv", "cc", "secret").use { handle ->
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
            transport.openRead("${server.root}/电影.mkv", "cc", "secret").use { handle ->
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
            transport.openRead("${server.root}/电影.mkv", "cc", "secret").use { handle ->
                assertEquals(0, handle.readAt(payload.size.toLong(), ByteArray(16), 0, 16))
            }
        }
    }

    @Test
    fun `服务器忽略 Range 时从头给流，读到的仍是请求位置的数据`() = runTest {
        FakeWebDavServer(bytes = payload, ignoreRange = true).use { server ->
            transport.openRead("${server.root}/电影.mkv", "cc", "secret").use { handle ->
                val buffer = ByteArray(32)

                assertEquals(32, handle.readAt(2000, buffer, 0, 32))
                assertArrayEquals(payload.copyOfRange(2000, 2032), buffer)
            }
        }
    }

    @Test
    fun `服务器没给出长度时打开失败`() = runTest {
        FakeWebDavServer(bytes = payload, withoutLength = true).use { server ->
            val exception = runCatching { transport.openRead("${server.root}/电影.mkv", "cc", "secret") }.exceptionOrNull()

            assertEquals(MediaFailure.Unknown, (exception as MediaSourceException).failure)
        }
    }

    @Test
    fun `打开文件状态码非 2xx 归到来源失败原因`() = runTest {
        FakeWebDavServer(bytes = payload, getStatus = 404).use { server ->
            val exception = runCatching { transport.openRead("${server.root}/电影.mkv", "cc", "secret") }.exceptionOrNull()

            assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
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
