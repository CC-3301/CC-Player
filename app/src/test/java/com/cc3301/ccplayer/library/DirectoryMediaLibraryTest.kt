package com.cc3301.ccplayer.library

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryMediaLibraryTest {

    private val tree = FakeMediaFileTree()
    private val library = DirectoryMediaLibrary(tree)

    private val root = testDirectory("媒体", "root")

    @Test
    fun `目录即列表：视频、音频与子目录都出现，顺序与文件树一致`() = runTest {
        tree.addDirectory(
            root,
            testVideo("电影.mkv", "root/电影.mkv"),
            testAudio("歌单.mp3", "root/歌单.mp3"),
            testDirectory("剧集", "root/剧集"),
        )

        val entries = library.list(root).getOrThrow()

        assertEquals(listOf("电影.mkv", "歌单.mp3", "剧集"), entries.map { it.name })
    }

    @Test
    fun `不递归展开：子目录本身出现，其中的文件不出现`() = runTest {
        val series = testDirectory("剧集", "root/剧集")
        tree.addDirectory(root, series)
        tree.addDirectory(series, testVideo("第01集.mkv", "root/剧集/第01集.mkv"))

        val entries = library.list(root).getOrThrow()

        assertEquals(listOf("剧集"), entries.map { it.name })
    }

    @Test
    fun `空目录返回空列表`() = runTest {
        tree.addDirectory(root)

        assertEquals(emptyList<MediaFile>(), library.list(root).getOrThrow())
    }

    @Test
    fun `非媒体文件被过滤，媒体条目保留原样`() = runTest {
        val movie = testVideo("电影.mkv", "root/电影.mkv")
        tree.addDirectory(root, movie, testDocument("封面.jpg", "root/封面.jpg"), testDocument("字幕.srt", "root/字幕.srt"))

        val entries = library.list(root).getOrThrow()

        assertEquals(listOf(movie), entries)
    }

    @Test
    fun `目录不可读时返回失败`() = runTest {
        tree.addDirectory(root).markUnreadable(root.location)

        val result = library.list(root)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `解析已登记的目录返回该目录，未知位置返回空`() = runTest {
        tree.addDirectory(root)

        assertEquals(root, library.resolveDirectory(root.location))
        assertNull(library.resolveDirectory("root/不存在"))
    }
}
