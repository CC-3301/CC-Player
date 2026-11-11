package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScreenPhaseTest {

    private fun facts(
        entryPhase: EntryPhase? = null,
        preparesInPage: Boolean = false,
        playbackState: PlaybackState = PlaybackState.Playing,
        switching: Boolean = false,
        restoring: Boolean = false,
    ): PlayerScreenLoadFacts = PlayerScreenLoadFacts(
        entryPhase = entryPhase,
        preparesInPage = preparesInPage,
        playbackState = playbackState,
        switching = switching,
        restoring = restoring,
    )

    private fun phaseOf(
        entryPhase: EntryPhase? = null,
        preparesInPage: Boolean = false,
        playbackState: PlaybackState = PlaybackState.Playing,
        switching: Boolean = false,
        restoring: Boolean = false,
    ): PlayerScreenPhase = playerScreenPhase(
        facts(
            entryPhase = entryPhase,
            preparesInPage = preparesInPage,
            playbackState = playbackState,
            switching = switching,
            restoring = restoring,
        ),
    )

    @Test
    fun `页面内准备中是准备中`() {
        assertEquals(
            PlayerScreenPhase.Preparing,
            phaseOf(entryPhase = EntryPhase.Preparing, preparesInPage = true, playbackState = PlaybackState.Idle),
        )
    }

    @Test
    fun `页面内等待接手是等待接手`() {
        assertEquals(
            PlayerScreenPhase.AwaitingHandover,
            phaseOf(entryPhase = EntryPhase.AwaitingHandover, preparesInPage = true, playbackState = PlaybackState.Idle),
        )
    }

    @Test
    fun `准备期优先于恢复与切集`() {
        // 打开新目标会先把旧加载的切集与恢复标志清掉，准备期一旦成立就按准备期显示。
        assertEquals(
            PlayerScreenPhase.Preparing,
            phaseOf(
                entryPhase = EntryPhase.Preparing,
                preparesInPage = true,
                playbackState = PlaybackState.Loading,
                switching = true,
                restoring = true,
            ),
        )
        assertEquals(
            PlayerScreenPhase.AwaitingHandover,
            phaseOf(
                entryPhase = EntryPhase.AwaitingHandover,
                preparesInPage = true,
                playbackState = PlaybackState.Loading,
                switching = true,
                restoring = true,
            ),
        )
    }

    @Test
    fun `音频打开不在页面内准备，按加载中或就绪收口`() {
        // 音频的准备在浏览页完成：进播放页时即使入口还停在准备阶段，也不算准备期。
        assertEquals(
            PlayerScreenPhase.Loading,
            phaseOf(entryPhase = EntryPhase.Preparing, preparesInPage = false, playbackState = PlaybackState.Loading),
        )
        assertEquals(
            PlayerScreenPhase.Ready,
            phaseOf(entryPhase = EntryPhase.Preparing, preparesInPage = false, playbackState = PlaybackState.Playing),
        )
    }

    @Test
    fun `接手后的首次加载是加载中`() {
        assertEquals(
            PlayerScreenPhase.Loading,
            phaseOf(entryPhase = EntryPhase.Accepted, playbackState = PlaybackState.Idle),
        )
        assertEquals(
            PlayerScreenPhase.Loading,
            phaseOf(entryPhase = null, playbackState = PlaybackState.Loading),
        )
    }

    @Test
    fun `切集中优先于加载中`() {
        assertEquals(
            PlayerScreenPhase.Switching,
            phaseOf(playbackState = PlaybackState.Loading, switching = true),
        )
        assertEquals(
            PlayerScreenPhase.Switching,
            phaseOf(entryPhase = EntryPhase.Accepted, playbackState = PlaybackState.Loading, switching = true),
        )
    }

    @Test
    fun `恢复中优先于切集中与加载中`() {
        // 恢复原片期间切集标志仍为真，阶段按恢复中算。
        assertEquals(
            PlayerScreenPhase.Restoring,
            phaseOf(playbackState = PlaybackState.Loading, switching = true, restoring = true),
        )
        assertEquals(
            PlayerScreenPhase.Restoring,
            phaseOf(entryPhase = EntryPhase.Accepted, playbackState = PlaybackState.Paused, switching = true, restoring = true),
        )
    }

    @Test
    fun `播放中与暂停片尾空闲都是就绪`() {
        assertEquals(PlayerScreenPhase.Ready, phaseOf(playbackState = PlaybackState.Playing))
        assertEquals(PlayerScreenPhase.Ready, phaseOf(playbackState = PlaybackState.Paused))
        assertEquals(PlayerScreenPhase.Ready, phaseOf(playbackState = PlaybackState.Ended))
        assertEquals(PlayerScreenPhase.Ready, phaseOf(playbackState = PlaybackState.Idle))
    }

    @Test
    fun `准备期标志只覆盖准备中与等待接手`() {
        val expected = mapOf(
            PlayerScreenPhase.Preparing to true,
            PlayerScreenPhase.AwaitingHandover to true,
            PlayerScreenPhase.Loading to false,
            PlayerScreenPhase.Switching to false,
            PlayerScreenPhase.Restoring to false,
            PlayerScreenPhase.Ready to false,
        )

        expected.forEach { (phase, preparing) ->
            assertEquals(phase.name, preparing, phase.isPreparing)
        }
    }

    @Test
    fun `首次加载标志覆盖准备期与加载中`() {
        val expected = mapOf(
            PlayerScreenPhase.Preparing to true,
            PlayerScreenPhase.AwaitingHandover to true,
            PlayerScreenPhase.Loading to true,
            PlayerScreenPhase.Switching to false,
            PlayerScreenPhase.Restoring to false,
            PlayerScreenPhase.Ready to false,
        )

        expected.forEach { (phase, initialLoading) ->
            assertEquals(phase.name, initialLoading, phase.isInitialLoading)
        }
    }

    @Test
    fun `切集与恢复标志各自只对应自己的阶段`() {
        assertEquals(PlayerScreenPhase.values().count { it.isSwitching }, 1)
        assertEquals(PlayerScreenPhase.values().count { it.isRestoring }, 1)
        assertTrue(PlayerScreenPhase.Switching.isSwitching)
        assertFalse(PlayerScreenPhase.Switching.isRestoring)
        assertTrue(PlayerScreenPhase.Restoring.isRestoring)
        assertFalse(PlayerScreenPhase.Restoring.isSwitching)
    }

    @Test
    fun `阶段只由事实派生且六种取值互斥`() {
        // 每个阶段都能由事实构造出来；加载阶段之外的组合不会落到加载阶段。
        val produced = setOf(
            phaseOf(entryPhase = EntryPhase.Preparing, preparesInPage = true, playbackState = PlaybackState.Idle),
            phaseOf(entryPhase = EntryPhase.AwaitingHandover, preparesInPage = true, playbackState = PlaybackState.Idle),
            phaseOf(playbackState = PlaybackState.Loading),
            phaseOf(playbackState = PlaybackState.Loading, switching = true),
            phaseOf(playbackState = PlaybackState.Paused, switching = true, restoring = true),
            phaseOf(playbackState = PlaybackState.Playing),
        )

        assertEquals(PlayerScreenPhase.values().toSet(), produced)
    }

    @Test
    fun `宿主按入口事实组装会话与阶段`() {
        val viewModel = PlayerScreenViewModel()
        val ready = viewModel.session(
            entryPhase = null,
            preparesInPage = false,
            bufferingShown = false,
            environment = PlayerScreenEnvironment(),
        )
        assertEquals(PlayerScreenPhase.Ready, ready.loading.phase)

        val preparing = viewModel.session(
            entryPhase = EntryPhase.Preparing,
            preparesInPage = true,
            bufferingShown = true,
            environment = PlayerScreenEnvironment(autoPlayNext = true),
        )
        assertEquals(PlayerScreenPhase.Preparing, preparing.loading.phase)
        assertTrue(preparing.loading.buffering)
        assertTrue(preparing.menu.autoPlayNext)
    }
}
