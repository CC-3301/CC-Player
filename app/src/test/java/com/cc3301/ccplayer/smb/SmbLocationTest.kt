package com.cc3301.ccplayer.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbLocationTest {

    @Test
    fun `解析共享根`() {
        val location = SmbLocation.parse("smb://nas/media")

        assertEquals("nas", location?.host)
        assertEquals(445, location?.port)
        assertEquals("media", location?.share)
        assertEquals("", location?.path)
    }

    @Test
    fun `解析共享内路径与自定义端口`() {
        val location = SmbLocation.parse("smb://192.168.1.10:1445/media/剧集/第01集.mkv")

        assertEquals("192.168.1.10", location?.host)
        assertEquals(1445, location?.port)
        assertEquals("media", location?.share)
        assertEquals("剧集/第01集.mkv", location?.path)
    }

    @Test
    fun `字符串形式与解析互为逆运算`() {
        listOf(
            "smb://nas/media",
            "smb://nas/media/剧集/第01集.mkv",
            "smb://192.168.1.10:1445/media/movie mkv.mp4",
        ).forEach { location ->
            assertEquals(location, SmbLocation.parse(location)?.url)
        }
    }

    @Test
    fun `默认端口不出现在字符串形式里`() {
        assertEquals("smb://nas/media/a.mkv", SmbLocation(host = "nas", port = 445, share = "media", path = "a.mkv").url)
        assertEquals("smb://nas:1450/media/a.mkv", SmbLocation(host = "nas", port = 1450, share = "media", path = "a.mkv").url)
    }

    @Test
    fun `解析服务器根：共享为空`() {
        listOf("smb://nas", "smb://nas/").forEach { url ->
            val location = SmbLocation.parse(url)

            assertEquals("nas", location?.host)
            assertEquals(445, location?.port)
            assertEquals("", location?.share)
            assertEquals("", location?.path)
            assertEquals("smb://nas", location?.url)
        }
    }

    @Test
    fun `服务器根带自定义端口`() {
        val location = SmbLocation.parse("smb://nas:1445")

        assertEquals(1445, location?.port)
        assertEquals("smb://nas:1445", location?.url)
    }

    @Test
    fun `不合法位置解析为空`() {
        listOf(
            "content://root/a",
            "smb:///media",
            "smb://nas:abc/media",
            "smb://nas:0/media",
            "smb://nas:70000/media",
            "",
        ).forEach { location ->
            assertNull("位置：$location", SmbLocation.parse(location))
        }
    }
}
