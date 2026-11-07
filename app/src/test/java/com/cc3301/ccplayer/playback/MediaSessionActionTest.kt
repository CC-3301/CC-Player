package com.cc3301.ccplayer.playback

import android.support.v4.media.session.PlaybackStateCompat
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知栏媒体动作到控制层的映射，以及播放/暂停按钮随播放状态切换。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaSessionActionTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()
    private val dispatcher = UnconfinedTestDispatcher()
    private val controller = PlaybackController(
        engine,
        progress,
        CoroutineScope(dispatcher),
        nowMillis = { dispatcher.scheduler.currentTime },
    )

    @Test
    fun `the pause action pauses the session`() {
        open("ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()

        MediaSessionAction.Pause.applyTo(controller)

        assertEquals(1, engine.pauseCalls)
    }

    @Test
    fun `the play action resumes the session`() {
        open("ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.pause()

        MediaSessionAction.Play.applyTo(controller)

        assertEquals(1, engine.resumeCalls)
    }

    @Test
    fun `the play action after playback ended reopens the current file`() {
        open("ep1.mkv", "ep2.mkv", current = "ep2.mkv")
        engine.reportMediaLoaded()
        engine.reportPlaybackEnded()

        MediaSessionAction.Play.applyTo(controller)

        assertEquals(PlaybackState.Loading, controller.state.value)
        assertEquals(listOf("root/ep2.mkv", "root/ep2.mkv"), engine.startedUrls)
    }

    @Test
    fun `the next action plays the next episode`() {
        open("ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()

        MediaSessionAction.Next.applyTo(controller)

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
    }

    @Test
    fun `the previous action plays the previous episode`() {
        open("ep1.mkv", "ep2.mkv", current = "ep2.mkv")
        engine.reportMediaLoaded()

        MediaSessionAction.Previous.applyTo(controller)

        assertEquals(listOf("root/ep2.mkv", "root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `the toggle shows pause while the media plays`() {
        assertEquals(MediaSessionAction.Pause, mediaSessionActionFor(PlaybackState.Playing))
    }

    @Test
    fun `the toggle shows play in every state that is not playing`() {
        val states = listOf(PlaybackState.Idle, PlaybackState.Loading, PlaybackState.Paused, PlaybackState.Ended)

        assertEquals(List(states.size) { MediaSessionAction.Play }, states.map(::mediaSessionActionFor))
    }

    @Test
    fun `every notification button intent maps back to its action`() {
        assertEquals(
            MediaSessionAction.entries,
            MediaSessionAction.entries.map { MediaSessionAction.fromIntentAction(it.intentAction) },
        )
    }

    @Test
    fun `an intent action that is not from the notification maps to no action`() {
        assertNull(MediaSessionAction.fromIntentAction("android.intent.action.VIEW"))
        assertNull(MediaSessionAction.fromIntentAction(null))
    }

    @Test
    fun `initial loading disables every media action`() {
        MediaSessionAction.entries.forEach { action ->
            assertFalse(mediaActionAllowed(action, switching = false, initialLoading = true, restoring = false))
        }
    }

    @Test
    fun `switching allows only previous and next media actions`() {
        MediaSessionAction.entries.forEach { action ->
            assertEquals(
                action.name,
                action == MediaSessionAction.Previous || action == MediaSessionAction.Next,
                mediaActionAllowed(action, switching = true, initialLoading = false, restoring = false),
            )
        }
    }

    @Test
    fun `outside loading every media action is allowed`() {
        MediaSessionAction.entries.forEach { action ->
            assertTrue(mediaActionAllowed(action, switching = false, initialLoading = false, restoring = false))
        }
    }

    @Test
    fun `restoring allows play pause and both episode actions`() {
        MediaSessionAction.entries.forEach { action ->
            assertTrue(mediaActionAllowed(action, switching = false, initialLoading = false, restoring = true))
        }
    }

    @Test
    fun `dispatch blocks play while the target is loading`() {
        val applied = mutableListOf<MediaSessionAction>()

        dispatchMediaAction(
            MediaSessionAction.Play,
            state = PlaybackState.Paused,
            switching = true,
            restoring = false,
        ) { applied += it }
        dispatchMediaAction(
            MediaSessionAction.Next,
            state = PlaybackState.Paused,
            switching = true,
            restoring = false,
        ) { applied += it }

        assertEquals(listOf(MediaSessionAction.Next), applied)
    }

    @Test
    fun `dispatch lets play through while the original is being restored`() {
        val applied = mutableListOf<MediaSessionAction>()

        dispatchMediaAction(
            MediaSessionAction.Play,
            state = PlaybackState.Paused,
            switching = false,
            restoring = true,
        ) { applied += it }

        assertEquals(listOf(MediaSessionAction.Play), applied)
    }

    @Test
    fun `dispatch blocks every action during the initial load`() {
        val applied = mutableListOf<MediaSessionAction>()

        MediaSessionAction.entries.forEach { action ->
            dispatchMediaAction(
                action,
                state = PlaybackState.Loading,
                switching = false,
                restoring = false,
            ) { applied += it }
        }

        assertTrue(applied.isEmpty())
    }

    @Test
    fun `dispatch keeps pause while background playback is blocked during loading gates`() {
        val applied = mutableListOf<MediaSessionAction>()

        MediaSessionAction.entries.forEach { action ->
            dispatchMediaAction(
                action,
                state = PlaybackState.Loading,
                switching = false,
                restoring = false,
                blocksBackgroundPlayback = true,
            ) { applied += it }
        }

        assertEquals(listOf(MediaSessionAction.Pause), applied)
    }

    @Test
    fun `seek is blocked while loading switching or restoring`() {
        assertFalse(
            mediaSeekAllowed(
                state = PlaybackState.Loading,
                hasMedia = true,
                switching = false,
                initialLoading = true,
                restoring = false,
                blocksBackgroundPlayback = false,
            ),
        )
        assertFalse(
            mediaSeekAllowed(
                state = PlaybackState.Paused,
                hasMedia = true,
                switching = true,
                initialLoading = false,
                restoring = false,
                blocksBackgroundPlayback = false,
            ),
        )
        assertFalse(
            mediaSeekAllowed(
                state = PlaybackState.Paused,
                hasMedia = true,
                switching = false,
                initialLoading = false,
                restoring = true,
                blocksBackgroundPlayback = false,
            ),
        )
    }

    @Test
    fun `seek is blocked without media and when background playback is off in background`() {
        assertFalse(
            mediaSeekAllowed(
                state = PlaybackState.Playing,
                hasMedia = false,
                switching = false,
                initialLoading = false,
                restoring = false,
                blocksBackgroundPlayback = false,
            ),
        )
        assertFalse(
            mediaSeekAllowed(
                state = PlaybackState.Playing,
                hasMedia = true,
                switching = false,
                initialLoading = false,
                restoring = false,
                blocksBackgroundPlayback = true,
            ),
        )
    }

    @Test
    fun `seek action is declared only when seeking is allowed`() {
        val actions = listOf(MediaSessionAction.Previous, MediaSessionAction.Pause, MediaSessionAction.Next)

        assertEquals(
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
            playbackStateActions(actions),
        )
        assertEquals(
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SEEK_TO,
            playbackStateActions(actions, seekable = true),
        )
    }

    @Test
    fun `playback state actions follow the allowed set`() {
        assertEquals(
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE,
            playbackStateActions(
                availableMediaActions(switching = false, initialLoading = false, restoring = true),
            ),
        )
        assertEquals(
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
            playbackStateActions(
                availableMediaActions(switching = true, initialLoading = false, restoring = false),
            ),
        )
        assertEquals(
            0L,
            playbackStateActions(
                availableMediaActions(switching = false, initialLoading = true, restoring = false),
            ),
        )
    }

    private fun open(vararg names: String, current: String) {
        val files = names.map { MediaFile(name = it, location = "root/$it") }
        controller.openPlaylistForTest(files, files.first { it.name == current })
    }
}