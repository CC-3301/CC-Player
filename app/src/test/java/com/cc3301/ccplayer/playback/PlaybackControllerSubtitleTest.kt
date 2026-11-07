package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/** 字幕三态：内嵌 / 外挂 / 关闭，互斥可切；换集按起播规则重算。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSubtitleTest {

    private val engine = FakePlaybackEngine()
    private val dispatcher = UnconfinedTestDispatcher()
    private val controller = PlaybackController(
        engine,
        FakePlaybackProgressRepository(),
        CoroutineScope(dispatcher),
        nowMillis = { dispatcher.scheduler.currentTime },
    )

    private val chinese = SubtitleTrack(id = 3, language = "chs", title = null, isDefault = false)
    private val english = SubtitleTrack(id = 5, language = "eng", title = null, isDefault = true)

    @Test
    fun `带 default 标记的内嵌轨道优先显示`() {
        engine.embeddedTracks = listOf(chinese, english)

        openEpisode("ep01.mkv", "ep01.srt")

        assertEquals(listOf(5), engine.selectedEmbeddedTracks)
        assertEquals(SubtitleSelection.Embedded(5), controller.subtitleSelection.value)
    }

    @Test
    fun `起播时匹配到的外挂全部加载，显示内嵌轨道`() {
        engine.embeddedTracks = listOf(chinese)

        openEpisode("ep01.mkv", "ep01.cht.ass", "ep01.srt")

        assertEquals(listOf("root/ep01.srt", "root/ep01.cht.ass"), engine.addedExternalSubtitles)
        assertEquals(listOf(3), engine.selectedEmbeddedTracks)
        assertEquals(emptyList<String>(), engine.selectedExternalSubtitles)
        assertEquals(SubtitleSelection.Embedded(3), controller.subtitleSelection.value)
        assertEquals(listOf(chinese), controller.subtitleTracks.value)
        assertEquals(listOf("ep01.srt", "ep01.cht.ass"), controller.externalSubtitles.value.map { it.name })
    }

    @Test
    fun `没有内嵌时显示得分最高的外挂`() {
        openEpisode("ep01.mkv", "ep01.cht.ass", "ep01.srt", "封面.jpg")

        assertEquals(listOf("root/ep01.srt", "root/ep01.cht.ass"), engine.addedExternalSubtitles)
        assertEquals(emptyList<Int>(), engine.selectedEmbeddedTracks)
        assertEquals(listOf("root/ep01.srt"), engine.selectedExternalSubtitles)
        assertEquals(SubtitleSelection.External("root/ep01.srt"), controller.subtitleSelection.value)
    }

    @Test
    fun `没有内嵌也没有外挂时关闭字幕`() {
        openEpisode("ep01.mkv", "ep02.chs.ass")

        assertEquals(emptyList<String>(), engine.addedExternalSubtitles)
        assertEquals(1, engine.disableSubtitleCalls)
        assertEquals(SubtitleSelection.Off, controller.subtitleSelection.value)
    }

    @Test
    fun `切到外挂后不再选内嵌，切回内嵌再选中轨道`() {
        engine.embeddedTracks = listOf(chinese)
        openEpisode("ep01.mkv", "ep01.srt")

        controller.selectExternalSubtitle("root/ep01.srt")
        assertEquals(listOf("root/ep01.srt"), engine.selectedExternalSubtitles)
        assertEquals(SubtitleSelection.External("root/ep01.srt"), controller.subtitleSelection.value)

        controller.selectEmbeddedSubtitle(chinese.id)
        assertEquals(listOf(3, 3), engine.selectedEmbeddedTracks)
        assertEquals(SubtitleSelection.Embedded(3), controller.subtitleSelection.value)
    }

    @Test
    fun `关闭后没有字幕，可以再切回任一路`() {
        engine.embeddedTracks = listOf(chinese)
        openEpisode("ep01.mkv", "ep01.srt")

        controller.disableSubtitles()
        assertEquals(SubtitleSelection.Off, controller.subtitleSelection.value)

        controller.selectExternalSubtitle("root/ep01.srt")
        assertEquals(SubtitleSelection.External("root/ep01.srt"), controller.subtitleSelection.value)
    }

    @Test
    fun `换集后回到起播默认，并按新一集的候选重算`() {
        engine.embeddedTracks = listOf(chinese)
        controller.openPlaylistForTest(TEST_ENTRY_GENERATION,
            listOf(mediaFile("ep01.mkv"), mediaFile("ep02.mkv")),
            mediaFile("ep01.mkv"),
            listOf(document("ep01.srt"), document("ep02.chs.ass")),
        )
        controller.selectExternalSubtitle("root/ep01.srt")

        engine.reportMediaLoaded()
        controller.next()
        engine.reportMediaLoaded()

        assertEquals(SubtitleSelection.Embedded(3), controller.subtitleSelection.value)
        assertEquals(listOf("root/ep02.chs.ass"), controller.externalSubtitles.value.map { it.location })
    }

    @Test
    fun `换集回到内嵌优先并重新加载外挂`() {
        controller.openPlaylistForTest(TEST_ENTRY_GENERATION,
            listOf(mediaFile("ep01.mkv"), mediaFile("ep02.mkv")),
            mediaFile("ep01.mkv"),
            listOf(document("ep01.srt"), document("ep02.srt")),
        )
        engine.reportMediaLoaded()
        assertEquals(SubtitleSelection.External("root/ep01.srt"), controller.subtitleSelection.value)

        engine.embeddedTracks = listOf(chinese)
        controller.next()
        engine.reportMediaLoaded()

        assertEquals(SubtitleSelection.Embedded(3), controller.subtitleSelection.value)
        assertEquals(listOf("root/ep01.srt", "root/ep02.srt"), engine.addedExternalSubtitles)
    }

    @Test
    fun `没有起播媒体时切字幕不动作`() {
        controller.selectEmbeddedSubtitle(1)
        controller.selectExternalSubtitle("root/ep01.srt")
        controller.disableSubtitles()

        assertEquals(emptyList<Int>(), engine.selectedEmbeddedTracks)
        assertEquals(emptyList<String>(), engine.selectedExternalSubtitles)
        assertEquals(0, engine.disableSubtitleCalls)
    }

    @Test
    fun `字幕状态不跨会话保留`() {
        engine.embeddedTracks = listOf(chinese)
        openEpisode("ep01.mkv", "ep01.srt")

        controller.stop()

        assertEquals(emptyList<SubtitleTrack>(), controller.subtitleTracks.value)
        assertEquals(emptyList<MediaFile>(), controller.externalSubtitles.value)
        assertEquals(SubtitleSelection.Off, controller.subtitleSelection.value)
    }

    private fun openEpisode(mediaName: String, vararg directoryNames: String) {
        controller.openPlaylistForTest(TEST_ENTRY_GENERATION,
            listOf(mediaFile(mediaName)),
            mediaFile(mediaName),
            directoryNames.map(::document),
        )
        engine.reportMediaLoaded()
    }

    private fun mediaFile(name: String): MediaFile = MediaFile(name = name, location = "root/$name")

    private fun document(name: String): MediaFile =
        MediaFile(name = name, location = "root/$name", size = 512)
}
