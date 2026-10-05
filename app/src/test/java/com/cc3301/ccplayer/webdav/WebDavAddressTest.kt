package com.cc3301.ccplayer.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavAddressTest {

    @Test
    fun `规范化源地址时去掉空白与末尾斜杠`() {
        assertEquals("https://dav.example.com/remote.php/dav/files/cc", WebDavAddress.normalize("  https://dav.example.com/remote.php/dav/files/cc/  "))
        assertEquals("http://192.168.1.10:5005/dav", WebDavAddress.normalize("http://192.168.1.10:5005/dav"))
        assertEquals("http://192.168.1.10", WebDavAddress.normalize("http://192.168.1.10/"))
    }

    @Test
    fun `方案统一成小写`() {
        assertEquals("https://dav.example.com/dav", WebDavAddress.normalize("HTTPS://dav.example.com/dav"))
        assertEquals("http://dav.example.com/dav", WebDavAddress.normalize("Http://dav.example.com/dav"))
    }

    @Test
    fun `地址里的中文与空格原样保留`() {
        assertEquals("https://dav.example.com/我的 网盘", WebDavAddress.normalize("https://dav.example.com/我的 网盘"))
    }

    @Test
    fun `不合法地址规范化为空`() {
        listOf(
            "",
            "   ",
            "dav.example.com/dav",
            "smb://nas/media",
            "ftp://dav.example.com/dav",
            "http://",
            "http://:5005/dav",
            "https:///dav",
            "http://dav.example.com/da\nv",
            "http://dav.example.com/da\tv",
        ).forEach { address ->
            assertNull("地址：$address", WebDavAddress.normalize(address))
        }
    }
}
