package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 定时暂停的会话行为：假时钟推进到点、取消、重选替换、播完本集、到点后继续播放。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSleepTimerTest {

    private val engine = FakePlaybackEngine()

    @Test
    fun `定时暂停初始为关`() = runTest {
        val controller = controller()

        assertEquals(SleepTimerState.Off, controller.sleepTimerState.value)
        assertNull(controller.sleepTimerOption.value)
    }

    @Test
    fun `定时选项随设置与取消更新`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()

        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))
        assertEquals(SleepTimerOption.AfterDuration(10), controller.sleepTimerOption.value)

        controller.startSleepTimer(SleepTimerOption.AfterDuration(30))
        assertEquals(SleepTimerOption.AfterDuration(30), controller.sleepTimerOption.value)

        controller.cancelSleepTimer()
        assertNull(controller.sleepTimerOption.value)
    }

    @Test
    fun `倒计时到点后定时选项清空`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()
        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))

        advance(10 * MINUTE)

        assertEquals(SleepTimerState.Off, controller.sleepTimerState.value)
        assertNull(controller.sleepTimerOption.value)
    }

    @Test
    fun `十分钟到点暂停而不是退出`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()

        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))
        advance(10 * MINUTE)

        assertEquals(1, engine.pauseCalls)
        assertEquals(0, engine.stopCalls)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `三十分钟到点暂停`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()

        controller.startSleepTimer(SleepTimerOption.AfterDuration(30))
        advance(30 * MINUTE)

        assertEquals(1, engine.pauseCalls)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `一小时到点暂停`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()

        controller.startSleepTimer(SleepTimerOption.AfterDuration(60))
        advance(60 * MINUTE)

        assertEquals(1, engine.pauseCalls)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `两小时到点暂停`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()

        controller.startSleepTimer(SleepTimerOption.AfterDuration(120))
        advance(120 * MINUTE)

        assertEquals(1, engine.pauseCalls)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `自定义分钟数到点暂停`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()

        controller.startSleepTimer(SleepTimerOption.AfterDuration(customSleepMinutes("45")!!))
        advance(44 * MINUTE)
        assertEquals(0, engine.pauseCalls)

        advance(MINUTE)
        assertEquals(1, engine.pauseCalls)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `没到点不暂停`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()

        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))
        advance(10 * MINUTE - 1)

        assertEquals(0, engine.pauseCalls)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(SleepTimerState.Countdown, controller.sleepTimerState.value)
    }

    @Test
    fun `取消定时后到点不暂停`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()
        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))

        controller.cancelSleepTimer()
        advance(10 * MINUTE)

        assertEquals(SleepTimerState.Off, controller.sleepTimerState.value)
        assertEquals(0, engine.pauseCalls)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `重选定时是替换而不是叠加`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()
        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))

        advance(6 * MINUTE)
        controller.startSleepTimer(SleepTimerOption.AfterDuration(30))
        advance(4 * MINUTE)

        assertEquals(0, engine.pauseCalls)

        advance(26 * MINUTE)

        assertEquals(1, engine.pauseCalls)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `重选定时后放弃原来的时长`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()
        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))

        advance(6 * MINUTE)
        controller.startSleepTimer(SleepTimerOption.EndOfEpisode)
        advance(10 * MINUTE)

        assertEquals(0, engine.pauseCalls)
        assertEquals(SleepTimerState.EndOfEpisode, controller.sleepTimerState.value)
    }

    @Test
    fun `定时到点后回到关`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()
        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))

        advance(10 * MINUTE)

        assertEquals(SleepTimerState.Off, controller.sleepTimerState.value)
    }

    @Test
    fun `播完本集在当前文件播完后暂停且不进下一集`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.startSleepTimer(SleepTimerOption.EndOfEpisode)

        endCurrentEpisode()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        assertEquals(0, engine.stopCalls)
    }

    @Test
    fun `播完本集在 keep-open 暂停回声之后仍然暂停`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.startSleepTimer(SleepTimerOption.EndOfEpisode)
        engine.reportMediaLoaded()

        // keep-open 先置暂停，随后的 eof 仍要按“播完本集”暂停。
        engine.reportPauseChanged(true)
        engine.reportPlaybackEnded()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        assertEquals(SleepTimerState.Off, controller.sleepTimerState.value)
    }

    @Test
    fun `播完本集到点后定时回到关`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.startSleepTimer(SleepTimerOption.EndOfEpisode)

        endCurrentEpisode()

        assertEquals(SleepTimerState.Off, controller.sleepTimerState.value)
    }

    @Test
    fun `播完本集不受单集循环影响`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.setLoopMode(LoopMode.SingleLoop)
        controller.startSleepTimer(SleepTimerOption.EndOfEpisode)

        endCurrentEpisode()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `播完本集不受列表循环影响`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep2.mkv")
        controller.setLoopMode(LoopMode.ListLoop)
        controller.startSleepTimer(SleepTimerOption.EndOfEpisode)

        endCurrentEpisode()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(listOf("root/ep2.mkv"), engine.startedUrls)
    }

    @Test
    fun `播完本集在自动连播关闭时同样暂停`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.setAutoPlayNext(false)
        controller.startSleepTimer(SleepTimerOption.EndOfEpisode)

        endCurrentEpisode()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `到点暂停后继续播放接着播而不是重播`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(30_000, 600_000)
        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))
        advance(10 * MINUTE)

        controller.resume()

        assertEquals(1, engine.resumeCalls)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `播完本集暂停后继续播放从头重播当前文件`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.startSleepTimer(SleepTimerOption.EndOfEpisode)
        endCurrentEpisode()

        controller.resume()

        assertEquals(listOf("root/ep1.mkv", "root/ep1.mkv"), engine.startedUrls)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `暂停在文件末尾时继续播放从头重播当前文件`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(600_000, 600_000)

        controller.pause()
        engine.reportPauseChanged(true)
        controller.resume()

        assertEquals(listOf("root/ep1.mkv", "root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `定时到点后手动切集仍然自动播放`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()
        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))
        advance(10 * MINUTE)

        controller.next()
        engine.reportMediaLoaded()

        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `重播后定时状态与进度都重置`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.startSleepTimer(SleepTimerOption.EndOfEpisode)
        endCurrentEpisode()

        controller.resume()

        assertEquals(SleepTimerState.Off, controller.sleepTimerState.value)
        assertEquals(PlaybackProgress(), controller.progress.value)
    }

    @Test
    fun `会话结束后到点不再暂停`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()
        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))

        controller.stop()
        advance(10 * MINUTE)

        assertEquals(0, engine.pauseCalls)
        assertEquals(PlaybackState.Idle, controller.state.value)
    }

    @Test
    fun `停止会话清掉定时`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", current = "ep1.mkv")
        controller.startSleepTimer(SleepTimerOption.AfterDuration(10))

        controller.stop()

        assertEquals(SleepTimerState.Off, controller.sleepTimerState.value)
    }

    private fun TestScope.controller(): PlaybackController =
        PlaybackController(
            engine,
            FakePlaybackProgressRepository(),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            nowMillis = { testScheduler.currentTime },
        )

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun open(controller: PlaybackController, vararg names: String, current: String) {
        controller.openPlaylist(names.map(::mediaFile), mediaFile(current))
    }

    private fun endCurrentEpisode() {
        engine.reportMediaLoaded()
        engine.reportPlaybackEnded()
    }

    private fun mediaFile(name: String): MediaFile = MediaFile(name = name, location = "root/$name")

    private companion object {
        const val MINUTE = 60_000L
    }
}
