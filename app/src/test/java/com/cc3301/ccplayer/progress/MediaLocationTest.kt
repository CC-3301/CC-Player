package com.cc3301.ccplayer.progress

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLocationTest {

    private val safRoot =
        "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies"

    @Test
    fun `SAF 目录的直接子项算在目录下`() {
        assertTrue(isDirectChildOf(safRoot, "$safRoot%2F第01集.mkv"))
    }

    @Test
    fun `SAF 子目录里的文件不算目录的直接子项`() {
        assertFalse(isDirectChildOf(safRoot, "$safRoot%2F剧集%2F第01集.mkv"))
    }

    @Test
    fun `SAF 同级目录的条目不算在目录下`() {
        val sibling = safRoot + "2"

        assertFalse(isDirectChildOf(safRoot, "$sibling%2F第01集.mkv"))
    }

    @Test
    fun `网络源位置按斜杠分隔判断`() {
        val directory = "smb://nas/媒体/剧集"

        assertTrue(isDirectChildOf(directory, "$directory/第01集.mkv"))
        assertFalse(isDirectChildOf(directory, "$directory/第二季/第01集.mkv"))
        assertFalse(isDirectChildOf(directory, "smb://nas/媒体/剧集2/第01集.mkv"))
    }
}
