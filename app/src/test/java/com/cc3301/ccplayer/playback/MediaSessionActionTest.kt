package com.cc3301.ccplayer.playback

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
    private val controller = PlaybackController(engine, progress, CoroutineScope(UnconfinedTestDispatcher()))

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
    fun `the next action plays the next episode`() {
        open("ep1.mkv", "ep2.mkv", current = "ep1.mkv")

        MediaSessionAction.Next.applyTo(controller)

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
    }

    @Test
    fun `the previous action plays the previous episode`() {
        open("ep1.mkv", "ep2.mkv", current = "ep2.mkv")

        MediaSessionAction.Previous.applyTo(controller)

        assertEquals(listOf("root/ep2.mkv", "root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `前台时通知栏动作不受后台播放开关影响`() {
        MediaSessionAction.entries.forEach { action ->
            assertTrue(action.appliesWhen(backgroundPlaybackEnabled = true, appInForeground = true))
            assertTrue(action.appliesWhen(backgroundPlaybackEnabled = false, appInForeground = true))
        }
    }

    @Test
    fun `开启后台播放时后台通知栏动作全部生效`() {
        MediaSessionAction.entries.forEach { action ->
            assertTrue(action.appliesWhen(backgroundPlaybackEnabled = true, appInForeground = false))
        }
    }

    @Test
    fun `关闭后台播放时后台播放键不生效而暂停与切集仍生效`() {
        assertFalse(MediaSessionAction.Play.appliesWhen(backgroundPlaybackEnabled = false, appInForeground = false))
        listOf(MediaSessionAction.Pause, MediaSessionAction.Previous, MediaSessionAction.Next).forEach { action ->
            assertTrue(action.appliesWhen(backgroundPlaybackEnabled = false, appInForeground = false))
        }
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

    private fun open(vararg names: String, current: String) {
        val files = names.map { MediaFile(name = it, location = "root/$it") }
        controller.openPlaylist(files, files.first { it.name == current })
    }
}