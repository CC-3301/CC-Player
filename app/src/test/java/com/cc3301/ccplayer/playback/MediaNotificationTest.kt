package com.cc3301.ccplayer.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体通知展示内容：标题去扩展名、时长与播放页一致、封面回退与播放中的刷新节奏。
 */
class MediaNotificationTest {

    @Test
    fun `video title drops the extension`() {
        assertEquals("第 1 集", notificationTitle("第 1 集.mkv"))
    }

    @Test
    fun `audio title drops the extension`() {
        assertEquals("track", notificationTitle("track.flac"))
    }

    @Test
    fun `title without an extension stays unchanged`() {
        assertEquals("README", notificationTitle("README"))
    }

    @Test
    fun `hidden file keeps its leading dot`() {
        assertEquals(".nomedia", notificationTitle(".nomedia"))
    }

    @Test
    fun `duration matches the progress shown by the player`() {
        assertEquals(93_000L, notificationDurationMillis(PlaybackProgress(positionMillis = 12_000, durationMillis = 93_000)))
    }

    @Test
    fun `cover uses the cached thumbnail when present`() {
        val file = File("thumbnails/cached.img")

        assertEquals(NotificationCover.CachedImage(file), notificationCover(file))
    }

    @Test
    fun `cover falls back to the app icon without a cached thumbnail`() {
        assertEquals(NotificationCover.AppIcon, notificationCover(null))
    }

    @Test
    fun `progress refreshes every second while playing`() {
        assertEquals(1_000L, notificationRefreshIntervalMillis(PlaybackState.Playing))
    }

    @Test
    fun `progress refresh stops outside playback`() {
        assertNull(notificationRefreshIntervalMillis(PlaybackState.Idle))
        assertNull(notificationRefreshIntervalMillis(PlaybackState.Loading))
        assertNull(notificationRefreshIntervalMillis(PlaybackState.Paused))
        assertNull(notificationRefreshIntervalMillis(PlaybackState.Ended))
    }

    @Test
    fun `seek is available only while normal playback is controllable`() {
        assertTrue(
            mediaSeekAllowed(
                hasMedia = true,
                switching = false,
                initialLoading = false,
                restoring = false,
                blocksBackgroundPlayback = false,
            ),
        )
    }
}
