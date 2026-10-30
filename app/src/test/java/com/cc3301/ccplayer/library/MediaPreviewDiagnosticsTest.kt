package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.diagnostics.Diagnostics
import com.cc3301.ccplayer.diagnostics.MemoryDiagnosticsSink
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPreviewDiagnosticsTest {

    private val directory: File = Files.createTempDirectory("preview-diagnostics").toFile()
    private val extractor = FakeMediaPreviewExtractor()

    @After
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun `缩略图抽取失败在开启时写日志`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val source = source(Diagnostics(sink, UnconfinedTestDispatcher(testScheduler)).apply { enabled = true })
        val file = networkWithCredentials("电影.mkv")
        extractor.imageFailure = IOException("读取失败")

        source.preview(file)

        val text = sink.lines.joinToString("\n")
        assertTrue(text.contains("缩略图抽取失败: 电影.mkv"))
        assertTrue(text.contains("IOException"))
        assertFalse(text.contains(file.location))
        assertFalse(text.contains("user:pass"))
    }

    @Test
    fun `缩略图抽取失败在关闭时不写日志`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val source = source(Diagnostics(sink, UnconfinedTestDispatcher(testScheduler)))
        extractor.imageFailure = IOException("读取失败")

        source.preview(networkWithCredentials("电影.mkv"))

        assertTrue(sink.lines.isEmpty())
    }

    @Test
    fun `网络封面入队与取许可等待分成两行记`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val source = source(Diagnostics(sink, UnconfinedTestDispatcher(testScheduler)).apply { enabled = true })
        extractor.imageBytes = byteArrayOf(1, 2, 3)

        source.preview(networkWithCredentials("电影.mkv"))

        val text = sink.lines.joinToString("\n")
        assertTrue("应记入队：$text", text.contains("网络封面: 电影.mkv 入队"))
        assertTrue("应记取许可等待：$text", text.contains("网络封面: 电影.mkv 取许可等待 "))
        assertFalse(text.contains("user:pass"))
    }

    private fun source(diagnostics: Diagnostics): CachedMediaPreviewSource =
        CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(
                directory = directory,
                maxBytes = 1 shl 20,
                ioDispatcher = Dispatchers.Unconfined,
            ),
            diagnostics = diagnostics,
        )

    private fun networkWithCredentials(name: String) = MediaFile(
        name = name,
        location = "smb://user:pass@nas/media/$name",
        source = MediaSource.Network,
    )
}
