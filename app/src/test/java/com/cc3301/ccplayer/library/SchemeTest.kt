package com.cc3301.ccplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SchemeTest {

    @Test
    fun `取方案分隔符之前的方案名，不区分大小写`() {
        assertEquals("smb", schemeOf("smb://nas/media/电影.mkv"))
        assertEquals("smb", schemeOf("SMB://nas/media/电影.mkv"))
        assertEquals("content", schemeOf("content://root/媒体"))
    }

    @Test
    fun `没有方案分隔符的位置取到空串`() {
        assertEquals("", schemeOf("root/媒体"))
    }
}
