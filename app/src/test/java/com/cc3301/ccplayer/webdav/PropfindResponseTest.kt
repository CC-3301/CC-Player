package com.cc3301.ccplayer.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

class PropfindResponseTest {

    private val requestUrl = "https://dav.example.com/remote.php/dav/files/cc/media"

    @Test
    fun `解析目录与文件，请求地址自身那条略去`() {
        val entries = parsePropfind(MULTISTATUS, requestUrl)

        assertEquals(
            listOf(
                WebDavEntry(name = "剧集", isDirectory = true, size = 0, lastModified = 0),
                WebDavEntry(
                    name = "A&B - 电影.mkv",
                    isDirectory = false,
                    size = 3_200_000_000,
                    lastModified = 1_700_051_696_000,
                ),
            ),
            entries,
        )
    }

    @Test
    fun `请求地址自身那条按三种 href 写法都略去`() {
        listOf(
            "/remote.php/dav/files/cc/media/",
            "https://dav.example.com/remote.php/dav/files/cc/media",
            "remote.php/dav/files/cc/media",
        ).forEach { selfHref ->
            val body = multistatus(response(selfHref, collection = true), response("/remote.php/dav/files/cc/media/movie.mkv"))

            assertEquals("自身写法：$selfHref", listOf("movie.mkv"), parsePropfind(body, requestUrl).map { it.name })
        }
    }

    @Test
    fun `缺字段的条目按缺省值处理`() {
        val body = multistatus(
            """
            <D:response>
              <D:href>https://dav.example.com/dav/movie.mkv</D:href>
              <D:propstat>
                <D:prop><D:resourcetype/></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status>
              </D:propstat>
              <D:propstat>
                <D:prop><D:getcontentlength/><D:getlastmodified>不是日期</D:getlastmodified></D:prop>
                <D:status>HTTP/1.1 404 Not Found</D:status>
              </D:propstat>
            </D:response>
            """.trimIndent(),
        )

        assertEquals(
            listOf(WebDavEntry(name = "movie.mkv", isDirectory = false, size = 0, lastModified = 0)),
            parsePropfind(body, "https://dav.example.com/dav"),
        )
    }

    @Test
    fun `名字里的百分号编码与 XML 实体都还原`() {
        val body = multistatus(
            response("/dav/media/%E5%89%A7%E9%9B%86%20(1)/"),
            response("/dav/media/Tom%20&amp;%20Jerry.mkv"),
        )

        assertEquals(
            listOf("剧集 (1)", "Tom & Jerry.mkv"),
            parsePropfind(body, "https://dav.example.com/dav/media").map { it.name },
        )
    }

    @Test
    fun `resourceType 里有 collection 才算目录`() {
        val body = multistatus(
            response("/dav/media/剧集/", collection = true),
            response("/dav/media/movie.mkv"),
        )

        assertEquals(listOf(true, false), parsePropfind(body, "https://dav.example.com/dav/media").map { it.isDirectory })
    }

    @Test
    fun `没有 response 或响应体为空时没有条目`() {
        assertEquals(emptyList<WebDavEntry>(), parsePropfind("", requestUrl))
        assertEquals(emptyList<WebDavEntry>(), parsePropfind("<d:multistatus xmlns:d=\"DAV:\"/>", requestUrl))
    }

    private fun multistatus(vararg responses: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\">\n")
        responses.forEach { append(it).append('\n') }
        append("</d:multistatus>")
    }

    private fun response(href: String, collection: Boolean = false): String {
        val resourceType = if (collection) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/>"
        return """
            <d:response>
              <d:href>$href</d:href>
              <d:propstat>
                <d:prop>$resourceType</d:prop>
                <d:status>HTTP/1.1 200 OK</d:status>
              </d:propstat>
            </d:response>
        """.trimIndent()
    }

    private companion object {
        val MULTISTATUS = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:href>/remote.php/dav/files/cc/media/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype>
                    <d:getlastmodified>Wed, 15 Nov 2023 12:34:56 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/cc/media/%E5%89%A7%E9%9B%86/</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/cc/media/A&amp;B%20-%20电影.mkv</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontentlength>3200000000</d:getcontentlength>
                    <d:getlastmodified>Wed, 15 Nov 2023 12:34:56 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
    }
}
