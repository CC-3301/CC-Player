package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import com.cc3301.ccplayer.progress.MediaProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressCleanupMediaLibraryTest {

    private val tree = FakeMediaFileTree()
    private val progress = FakePlaybackProgressRepository()
    private val library = ProgressCleanupMediaLibrary(DirectoryMediaLibrary(tree), progress)

    private val root = testDirectory("剧集", "content://root/document/剧集")

    @Test
    fun `列举目录后清掉已不存在媒体的记录`() = runTest {
        tree.addDirectory(root, testVideo("第01集.mkv", "${root.location}%2F第01集.mkv"))
        progress.records["${root.location}%2F第01集.mkv"] = record("${root.location}%2F第01集.mkv")
        progress.records["${root.location}%2F已移走.mkv"] = record("${root.location}%2F已移走.mkv")

        val entries = library.list(root).getOrThrow()

        assertEquals(listOf("第01集.mkv"), entries.map { it.name })
        assertEquals(setOf("${root.location}%2F第01集.mkv"), progress.records.keys)
    }

    @Test
    fun `只在被列举的目录内清理，别的目录的记录不动`() = runTest {
        tree.addDirectory(root, testVideo("第01集.mkv", "${root.location}%2F第01集.mkv"))
        progress.records["content://root/document/别的剧集%2F第01集.mkv"] =
            record("content://root/document/别的剧集%2F第01集.mkv")

        library.list(root)

        assertTrue(progress.records.containsKey("content://root/document/别的剧集%2F第01集.mkv"))
    }

    @Test
    fun `子目录里的媒体记录不在本层判断`() = runTest {
        val series = testDirectory("第二季", "${root.location}%2F第二季")
        tree.addDirectory(root, series)
        progress.records["${series.location}%2F第01集.mkv"] = record("${series.location}%2F第01集.mkv")

        library.list(root)

        assertTrue(progress.records.containsKey("${series.location}%2F第01集.mkv"))
    }

    @Test
    fun `目录读不出来时不清理记录`() = runTest {
        tree.addDirectory(root).markUnreadable(root.location)
        progress.records["${root.location}%2F已移走.mkv"] = record("${root.location}%2F已移走.mkv")

        val result = library.list(root)

        assertTrue(result.isFailure)
        assertTrue(progress.records.containsKey("${root.location}%2F已移走.mkv"))
    }

    @Test
    fun `解析根目录透传`() = runTest {
        tree.addDirectory(root)

        assertEquals(root, library.resolveDirectory(root.location))
    }

    private fun record(location: String) = MediaProgress(location, 1_000, 60_000, 1_700_000_000_000)
}
