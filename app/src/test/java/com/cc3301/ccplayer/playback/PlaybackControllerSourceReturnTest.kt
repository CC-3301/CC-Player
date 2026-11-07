package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归还字节源：当前目标不再需要该源时按位置交还，触发点是播到结束、暂停在片尾、
 * 真正停止、换目标与切集接管成功。控制层只表达「不再需要」，摘走与关闭在界面侧。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSourceReturnTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    /** 每次归还发生时，引擎已经下发过几次加载：用来断言归还晚于新加载下发。 */
    private val startsAtReturn = mutableListOf<Int>()

    private fun TestScope.controller(returned: MutableList<String>): PlaybackController = PlaybackController(
        engine,
        progress,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        nowMillis = { testScheduler.currentTime },
        returnSource = { location ->
            returned += location
            startsAtReturn += engine.startedUrls.size
        },
    )

    private fun mediaFile(name: String): MediaFile =
        MediaFile(name = name, location = "root/$name", source = MediaSource.Network)

    /** 起播第一集并等就绪，作为正常播放状态。 */
    private fun TestScope.playingController(
        returned: MutableList<String>,
        vararg names: String = arrayOf("ep1.mkv", "ep2.mkv"),
    ): PlaybackController {
        val controller = controller(returned)
        val files = names.map(::mediaFile)
        controller.openPlaylistForTest(TEST_ENTRY_GENERATION, files, files.first())
        engine.reportMediaLoaded()
        return controller
    }

    @Test
    fun `播到结束归还当前目标的字节源`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned)
        controller.setAutoPlayNext(false)

        engine.reportPlaybackEnded()

        assertEquals(listOf("root/ep1.mkv"), returned)
    }

    @Test
    fun `按下暂停停在片尾时当场归还`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned)
        engine.reportPositionChanged(600_000, 600_000)

        // 已经暂停再按暂停不会回属性变更回声，按下那一刻就要交还。
        controller.pause()

        assertEquals(listOf("root/ep1.mkv"), returned)
    }

    @Test
    fun `引擎回声的暂停停在片尾时同样归还`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned)
        engine.reportPositionChanged(600_000, 600_000)

        // 不经过控制层的暂停（播完停在末帧、音频焦点丢失）只有引擎回声。
        engine.reportPauseChanged(true)

        assertEquals(listOf("root/ep1.mkv"), returned)
    }

    @Test
    fun `暂停在片尾之外不归还`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned)
        engine.reportPositionChanged(500_000, 600_000)

        controller.pause()
        engine.reportPauseChanged(true)

        assertTrue(returned.isEmpty())
    }

    @Test
    fun `换目标在发起那一刻归还旧目标`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned)
        val target = mediaFile("ep9.mkv")

        controller.openPlaylistForTest(generation = 2, files = listOf(target), current = target)

        assertEquals(listOf("root/ep1.mkv"), returned)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `切集接管成功归还原目标，新目标不受影响`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned)

        controller.next()
        assertTrue("接管前不归还原目标", returned.isEmpty())
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        engine.reportPositionChanged(1_000, 600_000)

        assertEquals(listOf("root/ep1.mkv"), returned)
        assertFalse(controller.switching.value)
        assertEquals(1, controller.currentIndex.value)
    }

    @Test
    fun `真正停止归还当前目标的字节源`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned)

        controller.stop()

        assertEquals(listOf("root/ep1.mkv"), returned)
    }

    @Test
    fun `单集循环重载同一位置不归还，也不弹播放失败`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned, names = arrayOf("ep1.mkv"))
        val failures = mutableListOf<PlaybackFailure>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.playbackFailures.collect { failures += it }
        }
        controller.setLoopMode(LoopMode.SingleLoop)

        engine.reportPlaybackEnded()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        engine.reportPositionChanged(1_000, 600_000)

        assertTrue("同一位置重载不产生交还", returned.isEmpty())
        assertTrue("正在读的源不能被摘走", failures.isEmpty())
        assertEquals(0, controller.currentIndex.value)
    }

    @Test
    fun `点列表里当前正在播的那一项不归还`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned)

        controller.play(mediaFile("ep1.mkv"))

        assertTrue(returned.isEmpty())
        assertEquals(0, controller.currentIndex.value)
    }

    @Test
    fun `切集失败回退原片时归还失败目标`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned)
        controller.next()
        val target = engine.currentLoadToken

        // 第一次按既有护栏重发一次，第二次才判失败并回退原片。
        engine.reportPlaybackFailed(loadToken = target)
        engine.reportPlaybackFailed(loadToken = engine.currentLoadToken)

        assertEquals("失败目标不再被读取", listOf("root/ep2.mkv"), returned)
        assertTrue(controller.restoring.value)
    }

    @Test
    fun `切集目标前移时归还前一个目标`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned, names = arrayOf("ep1.mkv", "ep2.mkv", "ep3.mkv"))
        controller.next()

        controller.next()

        assertEquals("前移后前一个目标不再被读取", listOf("root/ep2.mkv"), returned)
        assertEquals(2, controller.currentIndex.value)
    }

    @Test
    fun `切集中途停止同时归还起点与目标`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned)
        controller.next()

        controller.stop()

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), returned)
    }

    @Test
    fun `连点前移时旧目标在新加载下发后才归还`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned, names = arrayOf("ep1.mkv", "ep2.mkv", "ep3.mkv"))
        controller.next()
        startsAtReturn.clear()

        controller.next()

        // 归还晚于新加载下发：交还那一刻引擎已经换到 ep3，旧目标不再被读。
        assertEquals(listOf("root/ep3.mkv"), engine.startedUrls.takeLast(1))
        assertEquals(listOf(3), startsAtReturn)
        assertEquals(listOf("root/ep2.mkv"), returned)
    }

    @Test
    fun `列表里有同位置重复项，从一个切到另一个不归还`() = runTest {
        val returned = mutableListOf<String>()
        val controller = controller(returned)
        val first = mediaFile("ep1.mkv")
        // 两条列表项指向同一个文件：位置相同，索引不同。
        val duplicate = first.copy(name = "ep1 副本.mkv")
        controller.openPlaylistForTest(TEST_ENTRY_GENERATION, listOf(first, duplicate), first)
        engine.reportMediaLoaded()

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        engine.reportPositionChanged(1_000, 600_000)

        assertEquals(1, controller.currentIndex.value)
        assertTrue("同一位置的两条列表项共用一条回环条目，不能归还", returned.isEmpty())
    }

    @Test
    fun `首次加载中按停止归还正在加载的目标`() = runTest {
        val returned = mutableListOf<String>()
        val controller = controller(returned)
        val files = listOf(mediaFile("ep1.mkv"))
        controller.openPlaylistForTest(TEST_ENTRY_GENERATION, files, files.first())

        controller.stop()

        assertEquals(listOf("root/ep1.mkv"), returned)
    }

    @Test
    fun `本地目标不归还字节源`() = runTest {
        val returned = mutableListOf<String>()
        val controller = controller(returned)
        val local = MediaFile(name = "ep1.mkv", location = "content://root/ep1.mkv")
        controller.openPlaylistForTest(TEST_ENTRY_GENERATION, listOf(local), local)
        engine.reportMediaLoaded()

        controller.stop()

        assertTrue("本地目标没有字节源", returned.isEmpty())
    }

    @Test
    fun `切集中途停止把起点与目标一起归还`() = runTest {
        val returned = mutableListOf<String>()
        val controller = playingController(returned)
        controller.next()

        controller.stop()

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), returned)
    }
}
