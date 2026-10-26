package com.cc3301.ccplayer.diagnostics

import com.cc3301.ccplayer.network.SourceProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsMessagesTest {

    @Test
    fun `连接源日志剔除 userinfo 只留名称与主机`() {
        val line = connectionLogLine(SourceProtocol.Smb, "nas", "smb://user:pass@nas:445/share")

        assertEquals("连接源: Smb nas", line)
        assertFalse(line.contains("user"))
        assertFalse(line.contains("pass"))
    }

    @Test
    fun `连接源名称为空时只记主机`() {
        val line = connectionLogLine(SourceProtocol.WebDav, "", "https://user:pass@nas/dav")

        assertEquals("连接源: WebDav nas", line)
        assertFalse(line.contains("user"))
        assertFalse(line.contains("pass"))
    }

    @Test
    fun `库内目录与播放导航记录源名称和相对路径`() {
        assertEquals(
            "导航: 目录 我的源 /剧集/第1季",
            libraryNavigationLine("我的源", "剧集", "第1季", directory = true),
        )
        assertEquals(
            "导航: 播放 我的源 /剧集/第1集.mkv",
            libraryNavigationLine("我的源", "剧集", "第1集.mkv", directory = false),
        )
    }

    @Test
    fun `库内导航日志写入日志出口`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = Diagnostics(sink, UnconfinedTestDispatcher()).apply { enabled = true }

        logLibraryNavigation(
            diagnostics = diagnostics,
            rootName = "我的源",
            relativePath = "剧集",
            entryName = "第1季",
            directory = true,
        )

        assertEquals(listOf("导航: 目录 我的源 /剧集/第1季"), sink.lines)
        assertTrue(sink.lines.single().contains("我的源"))
    }

    @Test
    fun `屏幕方向切换日志记录切换前后的方向`() {
        assertEquals("屏幕方向: 竖屏 -> 横屏", screenOrientationChangeLine("竖屏", "横屏"))
        assertEquals("屏幕方向: 横屏 -> 竖屏", screenOrientationChangeLine("横屏", "竖屏"))
    }
}
