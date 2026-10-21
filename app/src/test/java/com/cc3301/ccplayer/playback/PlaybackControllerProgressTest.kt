package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import com.cc3301.ccplayer.progress.MediaProgress
import com.cc3301.ccplayer.settings.ResumeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 进度记忆的会话层：起播位置、落库时机、播完清除。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerProgressTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    private val ep1 = MediaFile(name = "ep1.mkv", location = "root/ep1.mkv", lastModified = 1_700_000_000_000)
    private val ep2 = MediaFile(name = "ep2.mkv", location = "root/ep2.mkv", lastModified = 1_700_000_000_001)

    @Test
    fun `续播模式默认播放上次位置`() = runTest {
        val controller = controller()

        assertEquals(ResumeMode.LastPosition, controller.resumeMode.value)
    }

    @Test
    fun `记录先到、媒体后加载完成时跳到上次位置`() = runTest {
        progress.records[ep1.location] = MediaProgress(ep1.location, 30_000, 60_000, ep1.lastModified)
        val controller = controller()

        controller.openPlaylist(listOf(ep1, ep2), ep1)
        runCurrent()
        engine.reportMediaLoaded()

        assertEquals(listOf(30_000L), engine.seekPositions)
    }

    @Test
    fun `媒体先加载完成、记录后到时也跳到上次位置`() = runTest {
        progress.records[ep1.location] = MediaProgress(ep1.location, 30_000, 60_000, ep1.lastModified)
        val controller = controller()

        controller.openPlaylist(listOf(ep1, ep2), ep1)
        engine.reportMediaLoaded()
        runCurrent()

        assertEquals(listOf(30_000L), engine.seekPositions)
    }

    @Test
    fun `设置始终从头播时不跳转`() = runTest {
        progress.records[ep1.location] = MediaProgress(ep1.location, 30_000, 60_000, ep1.lastModified)
        val controller = controller()
        controller.setResumeMode(ResumeMode.FromStart)

        controller.openPlaylist(listOf(ep1, ep2), ep1)
        engine.reportMediaLoaded()
        runCurrent()

        assertEquals(emptyList<Long>(), engine.seekPositions)
    }

    @Test
    fun `没有记录时从头播`() = runTest {
        val controller = controller()

        controller.openPlaylist(listOf(ep1, ep2), ep1)
        engine.reportMediaLoaded()
        runCurrent()

        assertEquals(emptyList<Long>(), engine.seekPositions)
    }

    @Test
    fun `记录位置为零时不跳转`() = runTest {
        progress.records[ep1.location] = MediaProgress(ep1.location, 0, 60_000, ep1.lastModified)
        val controller = controller()

        controller.openPlaylist(listOf(ep1, ep2), ep1)
        engine.reportMediaLoaded()
        runCurrent()

        assertEquals(emptyList<Long>(), engine.seekPositions)
    }

    @Test
    fun `迟到的记录不作用到新会话的媒体上`() = runTest {
        progress.records[ep1.location] = MediaProgress(ep1.location, 30_000, 60_000, ep1.lastModified)
        progress.holdFinds()
        val controller = controller()

        controller.openPlaylist(listOf(ep1, ep2), ep1)
        runCurrent()
        controller.stop()
        controller.openPlaylist(listOf(ep1, ep2), ep2)
        progress.releaseFinds()
        runCurrent()
        engine.reportMediaLoaded()

        assertEquals(emptyList<Long>(), engine.seekPositions)
    }

    @Test
    fun `切集期间原媒体的迟到位置不作用到目标`() = runTest {
        val controller = controller()
        controller.openPlaylist(listOf(ep1, ep2), ep1)
        engine.reportMediaLoaded()

        controller.next()
        engine.reportPositionChanged(30_000, 60_000)
        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertEquals(0L, controller.progress.value.positionMillis)
        assertNull(progress.records[ep1.location])
    }

    @Test
    fun `位置按间隔落库`() = runTest {
        val controller = controller()

        controller.openPlaylist(listOf(ep1, ep2), ep1)
        engine.reportMediaLoaded()
        runCurrent()
        engine.reportPositionChanged(30_000, 60_000)
        runCurrent()

        assertEquals(30_000L, progress.records[ep1.location]?.positionMillis)
        assertEquals(60_000L, progress.records[ep1.location]?.durationMillis)
        assertEquals(ep1.lastModified, progress.records[ep1.location]?.lastModified)
    }

    @Test
    fun `间隔内的位置更新不落库`() = runTest {
        val controller = controller()

        controller.openPlaylist(listOf(ep1, ep2), ep1)
        engine.reportMediaLoaded()
        runCurrent()
        engine.reportPositionChanged(1_000, 60_000)
        runCurrent()

        assertNull(progress.records[ep1.location])
    }

    @Test
    fun `中途退出保留最新位置`() = runTest {
        val controller = controller()

        controller.openPlaylist(listOf(ep1, ep2), ep1)
        engine.reportMediaLoaded()
        runCurrent()
        engine.reportPositionChanged(1_000, 60_000)
        runCurrent()
        controller.stop()
        runCurrent()

        assertEquals(1_000L, progress.records[ep1.location]?.positionMillis)
        assertEquals(60_000L, progress.records[ep1.location]?.durationMillis)
    }

    @Test
    fun `切换下一集时上一集的记录还在`() = runTest {
        val controller = controller()

        controller.openPlaylist(listOf(ep1, ep2), ep1)
        engine.reportMediaLoaded()
        runCurrent()
        engine.reportPositionChanged(30_000, 60_000)
        runCurrent()
        controller.next()
        runCurrent()

        assertEquals(30_000L, progress.records[ep1.location]?.positionMillis)
    }

    @Test
    fun `换媒体时引擎报的结束不清新媒体的记录`() = runTest {
        progress.records[ep2.location] = MediaProgress(ep2.location, 30_000, 60_000, ep2.lastModified)
        val controller = controller()

        controller.openPlaylist(listOf(ep1, ep2), ep1)
        engine.reportMediaLoaded()
        runCurrent()
        controller.playAt(1)
        engine.reportPlaybackEnded()
        runCurrent()

        assertEquals(30_000L, progress.records[ep2.location]?.positionMillis)
    }

    @Test
    fun `播到结尾时清除记录`() = runTest {
        progress.records[ep1.location] = MediaProgress(ep1.location, 5_000, 60_000, ep1.lastModified)
        val controller = controller()

        controller.openPlaylist(listOf(ep1), ep1)
        engine.reportMediaLoaded()
        runCurrent()
        engine.reportPositionChanged(60_000, 60_000)
        runCurrent()

        assertNull(progress.records[ep1.location])
    }

    @Test
    fun `看完后退出不重新写记录`() = runTest {
        val controller = controller()

        controller.openPlaylist(listOf(ep1), ep1)
        engine.reportMediaLoaded()
        runCurrent()
        engine.reportPositionChanged(60_000, 60_000)
        runCurrent()
        controller.stop()
        runCurrent()

        assertNull(progress.records[ep1.location])
    }

    @Test
    fun `播到结尾后重播不带回上次位置`() = runTest {
        progress.records[ep1.location] = MediaProgress(ep1.location, 5_000, 60_000, ep1.lastModified)
        val controller = controller()
        controller.setLoopMode(LoopMode.SingleLoop)

        controller.openPlaylist(listOf(ep1), ep1)
        engine.reportMediaLoaded()
        runCurrent()
        engine.reportPositionChanged(60_000, 60_000)
        runCurrent()
        engine.reportPlaybackEnded()
        runCurrent()
        engine.reportMediaLoaded()
        runCurrent()

        assertEquals(listOf(5_000L), engine.seekPositions)
    }

    /** 用 backgroundScope，避免测试推进虚拟时间时把看门狗也推到点。 */
    private fun TestScope.controller(): PlaybackController =
        PlaybackController(engine, progress, backgroundScope)
}
