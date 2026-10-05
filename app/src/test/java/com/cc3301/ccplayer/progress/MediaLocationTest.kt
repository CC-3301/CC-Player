package com.cc3301.ccplayer.progress

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLocationTest {

    private val safRoot =
        "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies"

    @Test
    fun `SAF 目录的直接子项与更深层级都算在该目录下`() {
        assertTrue(isUnderDirectory(safRoot, "$safRoot%2F剧集%2F第01集.mkv"))
        assertTrue(isUnderDirectory(safRoot, "$safRoot%2F第01集.mkv"))
    }

    @Test
    fun `SAF 同级目录的条目不算在该目录下`() {
        val sibling = "$safRoot" + "2"

        assertFalse(isUnderDirectory(safRoot, "$sibling%2F第01集.mkv"))
    }

    @Test
    fun `网络源位置按斜杠分隔判断`() {
        val directory = "smb://nas/媒体/剧集"

        assertTrue(isUnderDirectory(directory, "$directory/第01集.mkv"))
        assertFalse(isUnderDirectory(directory, "smb://nas/媒体/剧集2/第01集.mkv"))
    }
}
