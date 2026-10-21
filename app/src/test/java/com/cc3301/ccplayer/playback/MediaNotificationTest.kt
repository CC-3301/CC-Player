package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.ThumbnailCache
import com.cc3301.ccplayer.library.ThumbnailPolicy
import com.cc3301.ccplayer.library.thumbnailCacheKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体通知展示内容：标题去扩展名、时长与播放页一致、封面命中/回退/后补图与刷新节奏。
 */
class MediaNotificationTest {

    private val directory: File = Files.createTempDirectory("notification-cover").toFile()
    private val cache = ThumbnailCache(directory = directory, maxBytes = 64)

    @After
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

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
    fun `notification content uses the title duration and position from current media`() {
        val content = content(
            state = PlaybackState.Playing,
            media = MediaFile(name = "第 1 集.mkv", location = "root/第 1 集.mkv"),
            progress = PlaybackProgress(positionMillis = 12_000, durationMillis = 93_000),
        )

        assertEquals("第 1 集", content.title)
        assertEquals(93_000L, content.durationMillis)
        assertEquals(12_000L, content.positionMillis)
    }

    @Test
    fun `missing media falls back to the app title`() {
        assertEquals("CC Player", content(state = PlaybackState.Idle, media = null).title)
    }

    @Test
    fun `cover uses the cached thumbnail when present`() = runBlocking {
        val media = MediaFile(name = "a.mkv", location = "root/a.mkv", lastModified = 100)
        val saved = cache.save(thumbnailCacheKey(media, ThumbnailPolicy()), byteArrayOf(1, 2, 3))
        val covers = NotificationCoverCache(cache)

        val content = content(state = PlaybackState.Playing, media = media, coverFile = covers.file(media))

        assertEquals(saved, content.coverFile)
    }

    @Test
    fun `cover falls back when the thumbnail is missing`() = runBlocking {
        val media = MediaFile(name = "a.mkv", location = "root/a.mkv", lastModified = 100)

        val content = content(state = PlaybackState.Playing, media = media, coverFile = NotificationCoverCache(cache).file(media))

        assertNull(content.coverFile)
    }

    @Test
    fun `a thumbnail written after the first miss is picked up`() = runBlocking {
        val media = MediaFile(name = "a.mkv", location = "root/a.mkv", lastModified = 100)
        val covers = NotificationCoverCache(cache)

        assertNull(covers.file(media))
        val saved = cache.save(thumbnailCacheKey(media, ThumbnailPolicy()), byteArrayOf(4, 5, 6))

        assertEquals(saved, covers.file(media))
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
    fun `playing position changes wait for the per-second ticker`() {
        assertFalse(
            shouldRefreshNotification(
                PlaybackState.Playing,
                previous = PlaybackProgress(positionMillis = 1_000, durationMillis = 90_000),
                current = PlaybackProgress(positionMillis = 2_000, durationMillis = 90_000),
            ),
        )
    }

    @Test
    fun `paused seek refreshes when the position callback arrives`() {
        val previous = PlaybackProgress(positionMillis = 1_000, durationMillis = 90_000)
        val current = PlaybackProgress(positionMillis = 2_000, durationMillis = 90_000)

        assertTrue(shouldRefreshNotification(PlaybackState.Paused, previous, current))
        assertEquals(
            2_000L,
            content(
                state = PlaybackState.Paused,
                media = MediaFile(name = "a.mkv", location = "root/a.mkv"),
                progress = current,
            ).positionMillis,
        )
    }

    @Test
    fun `duration changes refresh while playing`() {
        assertTrue(
            shouldRefreshNotification(
                PlaybackState.Playing,
                PlaybackProgress(positionMillis = 1_000, durationMillis = 0),
                PlaybackProgress(positionMillis = 1_000, durationMillis = 90_000),
            ),
        )
    }

    @Test
    fun `background playback off keeps the pause action during loading gates`() {
        val media = MediaFile(name = "a.mkv", location = "root/a.mkv")

        listOf(
            content(
                state = PlaybackState.Loading,
                media = media,
                switching = false,
                initialLoading = true,
                blocksBackgroundPlayback = true,
            ),
            content(
                state = PlaybackState.Loading,
                media = media,
                switching = true,
                initialLoading = false,
                blocksBackgroundPlayback = true,
            ),
        ).forEach { content ->
            assertEquals(listOf(MediaSessionAction.Pause), content.actions)
        }
    }

    @Test
    fun `seek is declared for playing and paused with media`() {
        assertTrue(seekAllowed(PlaybackState.Playing))
        assertTrue(seekAllowed(PlaybackState.Paused))
    }

    @Test
    fun `seek is not declared for idle or ended`() {
        assertFalse(seekAllowed(PlaybackState.Idle))
        assertFalse(seekAllowed(PlaybackState.Ended))
    }

    private fun seekAllowed(state: PlaybackState): Boolean = mediaSeekAllowed(
        state = state,
        hasMedia = true,
        switching = false,
        initialLoading = false,
        restoring = false,
        blocksBackgroundPlayback = false,
    )

    private fun content(
        state: PlaybackState,
        media: MediaFile?,
        progress: PlaybackProgress = PlaybackProgress(),
        switching: Boolean = false,
        initialLoading: Boolean = false,
        restoring: Boolean = false,
        blocksBackgroundPlayback: Boolean = false,
        coverFile: File? = null,
    ): MediaNotificationContent = mediaNotificationContent(
        state = state,
        media = media,
        progress = progress,
        switching = switching,
        initialLoading = initialLoading,
        restoring = restoring,
        blocksBackgroundPlayback = blocksBackgroundPlayback,
        coverFile = coverFile,
        fallbackTitle = "CC Player",
    )
}
