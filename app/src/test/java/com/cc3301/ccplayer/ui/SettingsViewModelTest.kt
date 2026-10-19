package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.settings.FakePreferencesDataStore
import com.cc3301.ccplayer.settings.OrientationMode
import com.cc3301.ccplayer.settings.ResumeMode
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.settings.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 设置页的状态：默认值读出、改动写回。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val settings = SettingsRepository(FakePreferencesDataStore())

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = SettingsViewModel(settings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `默认设置项`() = runTest {
        advanceUntilIdle()

        assertEquals(SettingsState(), viewModel.state.value)
    }

    @Test
    fun `默认值为连播开、续播、位置记忆开、硬解、屏幕方向、单列、3 列、名称升序`() = runTest {
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.autoPlayNext)
        assertEquals(ResumeMode.LastPosition, state.resumeMode)
        assertTrue(state.autoRestorePosition)
        assertEquals(DecodeMode.Hardware, state.decodeMode)
        assertEquals(OrientationMode.System, state.orientationMode)
        assertEquals(ViewMode.List, state.viewMode)
        assertEquals(3, state.gridColumns)
        assertEquals(SortOrder.NameAscending, state.sortOrder)
    }

    @Test
    fun `连播开关改动写回设置并反映到状态`() = runTest {
        viewModel.setAutoPlayNext(false)
        advanceUntilIdle()

        assertFalse(settings.autoPlayNext.first())
        assertFalse(viewModel.state.value.autoPlayNext)
    }

    @Test
    fun `续播模式改动写回设置并反映到状态`() = runTest {
        viewModel.setResumeMode(ResumeMode.FromStart)
        advanceUntilIdle()

        assertEquals(ResumeMode.FromStart, settings.resumeMode.first())
        assertEquals(ResumeMode.FromStart, viewModel.state.value.resumeMode)
    }

    @Test
    fun `位置记忆开关改动写回设置并反映到状态`() = runTest {
        viewModel.setAutoRestorePosition(false)
        advanceUntilIdle()

        assertFalse(settings.autoRestorePosition.first())
        assertFalse(viewModel.state.value.autoRestorePosition)
    }

    @Test
    fun `解码模式改动写回设置并反映到状态`() = runTest {
        viewModel.setDecodeMode(DecodeMode.Software)
        advanceUntilIdle()

        assertEquals(DecodeMode.Software, settings.decodeMode.first())
        assertEquals(DecodeMode.Software, viewModel.state.value.decodeMode)
    }

    @Test
    fun `方向模式改动写回设置并反映到状态`() = runTest {
        viewModel.setOrientationMode(OrientationMode.FollowVideo)
        advanceUntilIdle()

        assertEquals(OrientationMode.FollowVideo, settings.orientationMode.first())
        assertEquals(OrientationMode.FollowVideo, viewModel.state.value.orientationMode)
    }

    @Test
    fun `视图模式与列数改动写回设置并反映到状态`() = runTest {
        viewModel.setViewMode(ViewMode.Grid)
        viewModel.setGridColumns(4)
        advanceUntilIdle()

        assertEquals(ViewMode.Grid, settings.viewMode.first())
        assertEquals(4, settings.gridColumns.first())
        assertEquals(ViewMode.Grid, viewModel.state.value.viewMode)
        assertEquals(4, viewModel.state.value.gridColumns)
    }

    @Test
    fun `排序改动写回设置并反映到状态`() = runTest {
        viewModel.setSortOrder(SortOrder.LastModifiedNewestFirst)
        advanceUntilIdle()

        assertEquals(SortOrder.LastModifiedNewestFirst, settings.sortOrder.first())
        assertEquals(SortOrder.LastModifiedNewestFirst, viewModel.state.value.sortOrder)
    }

    @Test
    fun `已保存的设置项在状态里读出`() = runTest {
        settings.setAutoPlayNext(false)
        settings.setResumeMode(ResumeMode.FromStart)
        settings.setAutoRestorePosition(false)
        settings.setDecodeMode(DecodeMode.Software)
        settings.setOrientationMode(OrientationMode.Landscape)
        settings.setViewMode(ViewMode.Grid)
        settings.setGridColumns(2)
        settings.setSortOrder(SortOrder.NameDescending)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.autoPlayNext)
        assertEquals(ResumeMode.FromStart, state.resumeMode)
        assertFalse(state.autoRestorePosition)
        assertEquals(DecodeMode.Software, state.decodeMode)
        assertEquals(OrientationMode.Landscape, state.orientationMode)
        assertEquals(ViewMode.Grid, state.viewMode)
        assertEquals(2, state.gridColumns)
        assertEquals(SortOrder.NameDescending, state.sortOrder)
    }
}
