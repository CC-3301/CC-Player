package com.cc3301.ccplayer.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbAddressTest {

    @Test
    fun `端口缺省时只留主机`() {
        assertEquals("nas", SmbAddress.normalize("nas"))
        assertEquals("nas", SmbAddress.normalize("  nas  "))
    }

    @Test
    fun `默认端口不写出来`() {
        assertEquals("nas", SmbAddress.normalize("nas:445"))
    }

    @Test
    fun `自定义端口保留`() {
        assertEquals("nas:1445", SmbAddress.normalize("nas:1445"))
    }

    @Test
    fun `空地址、带路径或端口不合法时无效`() {
        listOf("", "  ", "nas/media", "/nas", "nas:abc", "nas:0", "nas:70000", "na s").forEach { address ->
            assertNull("地址：$address", SmbAddress.normalize(address))
        }
    }
}
