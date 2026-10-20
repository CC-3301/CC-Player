package com.cc3301.ccplayer.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavEncodingTest {

    @Test
    fun `路径段按 UTF-8 百分号编码`() {
        assertEquals("%E5%89%A7%E9%9B%86", encodePathSegment("剧集"))
        assertEquals("A%20B.mkv", encodePathSegment("A B.mkv"))
        assertEquals("2023-11-15.mkv", encodePathSegment("2023-11-15.mkv"))
    }

    @Test
    fun `已经编码过的路径段不二次编码`() {
        assertEquals("A%20B.mkv", encodePathSegment("A%20B.mkv"))
        assertEquals("%E5%89%A7%E9%9B%86", encodePathSegment("%E5%89%A7%E9%9B%86"))
    }

    @Test
    fun `编码请求地址时保留末尾斜杠`() {
        assertEquals("http://host/dav/", encodeWebDavUrl("http://host/dav/"))
        assertEquals("http://host/dav", encodeWebDavUrl("http://host/dav"))
    }

    @Test
    fun `百分号解码不把加号当空格`() {
        assertEquals("A B", percentDecode("A%20B"))
        assertEquals("A+B", percentDecode("A+B"))
        assertEquals("剧集", percentDecode("%E5%89%A7%E9%9B%86"))
    }

    @Test
    fun `非法百分号序列原样保留`() {
        assertEquals("原样%ZZ", percentDecode("原样%ZZ"))
        assertEquals("结尾%2", percentDecode("结尾%2"))
    }
}
