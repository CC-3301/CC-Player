package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.testVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingPlaybackTest {

    private val pending = PendingPlayback()
    private val current = testVideo("第1集.mkv", "content://root/第1集.mkv")
    private val playlist = listOf(current)
    private val directoryEntries = listOf(current)

    @Test
    fun `服务与画面未就绪时不开始播放`() {
        pending.set(current, playlist, directoryEntries, generation = 1)
        var started: Pair<MediaFile, List<MediaFile>>? = null

        pending.start(canStart = false) { media, files, _, _ -> started = media to files }

        assertNull(started)
    }

    @Test
    fun `就绪后把播放列表与代际交给播放控制层且只起播一次`() {
        pending.set(current, playlist, directoryEntries, generation = 7)
        val starts = mutableListOf<Triple<MediaFile, List<MediaFile>, List<MediaFile>>>()
        val generations = mutableListOf<Int>()

        pending.start(canStart = true) { media, files, entries, generation ->
            starts += Triple(media, files, entries)
            generations += generation
        }
        pending.start(canStart = true) { media, files, entries, generation ->
            starts += Triple(media, files, entries)
            generations += generation
        }

        assertEquals(1, starts.size)
        assertEquals(current, starts.single().first)
        assertEquals(playlist, starts.single().second)
        assertEquals(directoryEntries, starts.single().third)
        assertEquals(listOf(7), generations)
    }

    @Test
    fun `从未就绪到就绪只起播一次`() {
        pending.set(current, playlist, directoryEntries, generation = 2)
        var starts = 0

        pending.start(canStart = false) { _, _, _, _ -> starts += 1 }
        pending.start(canStart = true) { _, _, _, _ -> starts += 1 }
        pending.start(canStart = true) { _, _, _, _ -> starts += 1 }

        assertEquals(1, starts)
    }

    @Test
    fun `清空后不再起播`() {
        pending.set(current, playlist, directoryEntries, generation = 3)
        pending.clear()
        var started = false

        pending.start(canStart = true) { _, _, _, _ -> started = true }

        assertEquals(false, started)
    }
}
