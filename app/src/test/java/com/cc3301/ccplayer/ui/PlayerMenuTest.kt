package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.playback.LoopMode
import com.cc3301.ccplayer.playback.SleepTimerOption
import com.cc3301.ccplayer.playback.SleepTimerState
import com.cc3301.ccplayer.settings.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** “更多”面板的选项顺序、文案与勾选状态。 */
class PlayerMenuTest {

    @Test
    fun `解码选项依次为硬解与软解`() {
        assertEquals(listOf(DecodeMode.Hardware, DecodeMode.Software), decodeModeOptions.map { it.value })
        assertEquals(R.string.player_decode_hardware, decodeModeOptions[0].label.resourceId)
        assertEquals(R.string.player_decode_software, decodeModeOptions[1].label.resourceId)
    }

    @Test
    fun `循环选项依次为顺序、列表循环与单集循环`() {
        assertEquals(
            listOf(LoopMode.Sequential, LoopMode.ListLoop, LoopMode.SingleLoop),
            loopModeOptions.map { it.value },
        )
        assertEquals(
            listOf(R.string.player_loop_sequential, R.string.player_loop_list, R.string.player_loop_single),
            loopModeOptions.map { it.label.resourceId },
        )
    }

    @Test
    fun `方向选项依次为屏幕方向、横屏、竖屏与跟随视频`() {
        assertEquals(
            listOf(
                OrientationMode.System,
                OrientationMode.Landscape,
                OrientationMode.Portrait,
                OrientationMode.FollowVideo,
            ),
            orientationModeOptions.map { it.value },
        )
        assertEquals(
            listOf(
                R.string.player_orientation_system,
                R.string.player_orientation_landscape,
                R.string.player_orientation_portrait,
                R.string.player_orientation_follow_video,
            ),
            orientationModeOptions.map { it.label.resourceId },
        )
    }

    @Test
    fun `定时选项依次为四个固定时长与播完本集`() {
        assertEquals(
            listOf(
                SleepTimerOption.AfterDuration(10),
                SleepTimerOption.AfterDuration(30),
                SleepTimerOption.AfterDuration(60),
                SleepTimerOption.AfterDuration(120),
                SleepTimerOption.EndOfEpisode,
            ),
            sleepTimerOptions.map { it.value },
        )
        assertEquals(
            listOf(
                R.string.player_sleep_timer_10_minutes,
                R.string.player_sleep_timer_30_minutes,
                R.string.player_sleep_timer_1_hour,
                R.string.player_sleep_timer_2_hours,
                R.string.player_sleep_timer_end_of_episode,
            ),
            sleepTimerOptions.map { it.label.resourceId },
        )
    }

    @Test
    fun `当前取值对应的选项带勾选`() {
        assertTrue(isOptionChecked(decodeModeOptions[1], DecodeMode.Software))
        assertFalse(isOptionChecked(decodeModeOptions[0], DecodeMode.Software))
        assertTrue(isOptionChecked(loopModeOptions[2], LoopMode.SingleLoop))
        assertFalse(isOptionChecked(loopModeOptions[1], LoopMode.SingleLoop))
        assertTrue(isOptionChecked(orientationModeOptions[3], OrientationMode.FollowVideo))
        assertFalse(isOptionChecked(orientationModeOptions[0], OrientationMode.FollowVideo))
    }

    @Test
    fun `定时只有播完本集能按状态标勾`() {
        assertTrue(isSleepTimerOptionChecked(SleepTimerOption.EndOfEpisode, SleepTimerState.EndOfEpisode))
        assertFalse(isSleepTimerOptionChecked(SleepTimerOption.EndOfEpisode, SleepTimerState.Countdown))
        assertFalse(isSleepTimerOptionChecked(SleepTimerOption.AfterDuration(30), SleepTimerState.Countdown))
        assertFalse(isSleepTimerOptionChecked(SleepTimerOption.AfterDuration(30), SleepTimerState.Off))
    }
}
