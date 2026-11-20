package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.settings.OrientationMode
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
    fun `接手后以控制层的加载事实为准`() {
        assertEquals(
            PlayerScreenPhase.Loading,
            phaseOf(entryPhase = EntryPhase.Accepted, playbackState = PlaybackState.Loading),
        )
        assertEquals(
            PlayerScreenPhase.Loading,
            phaseOf(entryPhase = null, playbackState = PlaybackState.Loading),
        )
        // 已接手会保留到这次打开被替换：就绪与终态都不再算加载中。
        assertEquals(
            PlayerScreenPhase.Ready,
            phaseOf(entryPhase = EntryPhase.Accepted, playbackState = PlaybackState.Playing),
        )
        assertEquals(
            PlayerScreenPhase.Ready,
            phaseOf(entryPhase = EntryPhase.Accepted, playbackState = PlaybackState.Ended),
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

    private fun loadingOf(
        entryPhase: EntryPhase? = null,
        preparesInPage: Boolean = false,
        playbackState: PlaybackState = PlaybackState.Playing,
        switching: Boolean = false,
        restoring: Boolean = false,
        controllerBuffering: Boolean = false,
    ): PlayerLoadingFacts = playerScreenLoading(
        PlayerScreenLoadFacts(
            entryPhase = entryPhase,
            preparesInPage = preparesInPage,
            playbackState = playbackState,
            switching = switching,
            restoring = restoring,
            controllerBuffering = controllerBuffering,
        ),
    )

    @Test
    fun `加载事实同时给出阶段与缓冲`() {
        data class Case(
            val name: String,
            val loading: PlayerLoadingFacts,
            val phase: PlayerScreenPhase,
            val buffering: Boolean,
            val opening: Boolean,
        )

        val cases = listOf(
            Case(
                "页面内准备中",
                loadingOf(entryPhase = EntryPhase.Preparing, preparesInPage = true, playbackState = PlaybackState.Idle),
                PlayerScreenPhase.Preparing,
                buffering = true,
                opening = true,
            ),
            Case(
                "页面内等待接手",
                loadingOf(
                    entryPhase = EntryPhase.AwaitingHandover,
                    preparesInPage = true,
                    playbackState = PlaybackState.Paused,
                ),
                PlayerScreenPhase.AwaitingHandover,
                buffering = true,
                opening = true,
            ),
            Case(
                "接手后首次加载仍由入口顶着",
                loadingOf(
                    entryPhase = EntryPhase.Accepted,
                    preparesInPage = true,
                    playbackState = PlaybackState.Loading,
                ),
                PlayerScreenPhase.Loading,
                buffering = true,
                opening = false,
            ),
            Case(
                "已接手保留到下次打开：首个就绪后只看控制层",
                loadingOf(
                    entryPhase = EntryPhase.Accepted,
                    preparesInPage = true,
                    playbackState = PlaybackState.Playing,
                ),
                PlayerScreenPhase.Ready,
                buffering = false,
                opening = false,
            ),
            Case(
                "已接手到达片尾不算缓冲",
                loadingOf(
                    entryPhase = EntryPhase.Accepted,
                    preparesInPage = true,
                    playbackState = PlaybackState.Ended,
                ),
                PlayerScreenPhase.Ready,
                buffering = false,
                opening = false,
            ),
            Case(
                "接手后转入切集交还控制层",
                loadingOf(
                    entryPhase = EntryPhase.Accepted,
                    preparesInPage = true,
                    playbackState = PlaybackState.Loading,
                    switching = true,
                ),
                PlayerScreenPhase.Switching,
                buffering = false,
                opening = false,
            ),
            Case(
                "切集中控制层缓冲照样是缓冲",
                loadingOf(playbackState = PlaybackState.Loading, switching = true, controllerBuffering = true),
                PlayerScreenPhase.Switching,
                buffering = true,
                opening = false,
            ),
            Case(
                "恢复原片交还控制层",
                loadingOf(playbackState = PlaybackState.Paused, switching = true, restoring = true),
                PlayerScreenPhase.Restoring,
                buffering = false,
                opening = false,
            ),
            Case(
                "就绪后控制层缓冲",
                loadingOf(playbackState = PlaybackState.Playing, controllerBuffering = true),
                PlayerScreenPhase.Ready,
                buffering = true,
                opening = false,
            ),
            Case(
                "音频不在页面内准备，只认控制层缓冲",
                loadingOf(
                    entryPhase = EntryPhase.Preparing,
                    preparesInPage = false,
                    playbackState = PlaybackState.Loading,
                    controllerBuffering = true,
                ),
                PlayerScreenPhase.Loading,
                buffering = true,
                opening = true,
            ),
            Case(
                "没有进行中的打开",
                loadingOf(playbackState = PlaybackState.Loading),
                PlayerScreenPhase.Loading,
                buffering = false,
                opening = false,
            ),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.phase, case.loading.phase)
            assertEquals(case.name, case.buffering, case.loading.buffering)
            assertEquals(case.name, case.opening, case.loading.opening)
        }
    }

    @Test
    fun `同一播放页会话场景覆盖缓冲控件可用性与返回`() {
        data class Scenario(
            val name: String,
            val loading: PlayerLoadingFacts,
            val playbackState: PlaybackState,
            val buffering: Boolean,
            val playPauseAllowed: Boolean,
            val back: PlayerBackAction,
        )

        val scenarios = listOf(
            Scenario(
                name = "打开交接中",
                loading = loadingOf(
                    entryPhase = EntryPhase.Preparing,
                    preparesInPage = true,
                    playbackState = PlaybackState.Playing,
                ),
                playbackState = PlaybackState.Playing,
                buffering = true,
                playPauseAllowed = false,
                back = PlayerBackAction.CancelOpening,
            ),
            Scenario(
                name = "接手后首次加载",
                loading = loadingOf(
                    entryPhase = EntryPhase.Accepted,
                    preparesInPage = true,
                    playbackState = PlaybackState.Loading,
                ),
                playbackState = PlaybackState.Loading,
                buffering = true,
                playPauseAllowed = false,
                back = PlayerBackAction.StopAndLeave,
            ),
            Scenario(
                name = "切集中",
                loading = loadingOf(
                    playbackState = PlaybackState.Loading,
                    switching = true,
                    controllerBuffering = true,
                ),
                playbackState = PlaybackState.Loading,
                buffering = true,
                playPauseAllowed = false,
                back = PlayerBackAction.CancelSwitch,
            ),
            Scenario(
                name = "恢复原片",
                loading = loadingOf(
                    playbackState = PlaybackState.Paused,
                    switching = true,
                    restoring = true,
                ),
                playbackState = PlaybackState.Paused,
                buffering = false,
                playPauseAllowed = false,
                back = PlayerBackAction.StopAndLeave,
            ),
            Scenario(
                name = "就绪",
                loading = loadingOf(playbackState = PlaybackState.Playing),
                playbackState = PlaybackState.Playing,
                buffering = false,
                playPauseAllowed = true,
                back = PlayerBackAction.LeaveAndContinue,
            ),
        )

        scenarios.forEach { scenario ->
            assertEquals(scenario.name, scenario.buffering, scenario.loading.buffering)
            assertEquals(
                scenario.name,
                scenario.playPauseAllowed,
                actionAllowed(PlayerAction.PlayPause, scenario.loading.phase),
            )
            assertEquals(
                scenario.name,
                scenario.back,
                playerBackAction(
                    loading = scenario.loading,
                    playbackState = scenario.playbackState,
                    failed = false,
                    inAppBackgroundPlayback = true,
                ),
            )
        }
    }

    @Test
    fun `宿主按入口事实组装会话与阶段`() {
        val viewModel = PlayerScreenViewModel()
        val ready = viewModel.session(
            entryPhase = null,
            preparesInPage = false,
            orientationMode = OrientationMode.System,
            orientationOverride = null,
            screenOrientation = ScreenOrientation.Portrait,
            autoPlayNext = false,
        )
        assertEquals(PlayerScreenPhase.Ready, ready.loading.phase)
        assertFalse(ready.loading.buffering)
        assertFalse(ready.loading.opening)

        val preparing = viewModel.session(
            entryPhase = EntryPhase.Preparing,
            preparesInPage = true,
            orientationMode = OrientationMode.System,
            orientationOverride = null,
            screenOrientation = ScreenOrientation.Portrait,
            autoPlayNext = true,
        )
        assertEquals(PlayerScreenPhase.Preparing, preparing.loading.phase)
        assertTrue(preparing.loading.buffering)
        assertTrue(preparing.loading.opening)
        assertTrue(preparing.menu.autoPlayNext)
    }
}
