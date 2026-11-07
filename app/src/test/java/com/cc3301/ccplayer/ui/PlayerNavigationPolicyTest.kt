package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.playback.OPEN_PLAYER_ACTION
import com.cc3301.ccplayer.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放页返回与库页面点开条目的判定。 */
class PlayerNavigationPolicyTest {

    private fun back(
        state: PlaybackState = PlaybackState.Playing,
        switching: Boolean = false,
        restoring: Boolean = false,
        initialLoading: Boolean = false,
        failed: Boolean = false,
        inAppBackgroundPlayback: Boolean = false,
        entryPhase: EntryPhase? = null,
        atEpisodeEnd: Boolean = false,
    ): PlayerBackAction = playerBackAction(
        state = state,
        switching = switching,
        restoring = restoring,
        initialLoading = initialLoading,
        failed = failed,
        inAppBackgroundPlayback = inAppBackgroundPlayback,
        entryPhase = entryPhase,
        atEpisodeEnd = atEpisodeEnd,
    )

    @Test
    fun `播放中返回在 APP 内后台播放开启时保持播放`() {
        assertEquals(
            PlayerBackAction.LeaveAndContinue,
            back(state = PlaybackState.Playing, inAppBackgroundPlayback = true),
        )
    }

    @Test
    fun `暂停与片尾停留按同一规则离开`() {
        listOf(PlaybackState.Paused, PlaybackState.Ended).forEach { state ->
            assertEquals(
                PlayerBackAction.LeaveAndContinue,
                back(state = state, inAppBackgroundPlayback = true),
            )
            assertEquals(
                PlayerBackAction.StopAndLeave,
                back(state = state, inAppBackgroundPlayback = false),
            )
        }
    }

    @Test
    fun `APP 内后台播放关闭时返回结束播放`() {
        assertEquals(
            PlayerBackAction.StopAndLeave,
            back(state = PlaybackState.Playing, inAppBackgroundPlayback = false),
        )
    }

    @Test
    fun `切集中返回只取消这一跳`() {
        assertEquals(
            PlayerBackAction.CancelSwitch,
            back(state = PlaybackState.Loading, switching = true, inAppBackgroundPlayback = true),
        )
        assertEquals(
            PlayerBackAction.CancelSwitch,
            back(state = PlaybackState.Loading, switching = true, inAppBackgroundPlayback = false),
        )
    }

    @Test
    fun `首次加载返回退出播放页`() {
        assertEquals(
            PlayerBackAction.StopAndLeave,
            back(state = PlaybackState.Loading, initialLoading = true, inAppBackgroundPlayback = true),
        )
    }

    @Test
    fun `恢复原片返回退出播放页`() {
        // 恢复期间 switching 仍为真：必须先按恢复态退出，不能再取消切集。
        assertEquals(
            PlayerBackAction.StopAndLeave,
            back(
                state = PlaybackState.Paused,
                switching = true,
                restoring = true,
                inAppBackgroundPlayback = true,
            ),
        )
    }

    @Test
    fun `准备期有旧会话时返回撤回打开交接`() {
        listOf(EntryPhase.Preparing, EntryPhase.AwaitingHandover).forEach { phase ->
            assertEquals(
                PlayerBackAction.CancelOpening,
                back(state = PlaybackState.Playing, entryPhase = phase, inAppBackgroundPlayback = true),
            )
            assertEquals(
                PlayerBackAction.CancelOpening,
                back(state = PlaybackState.Paused, entryPhase = phase),
            )
        }
    }

    @Test
    fun `准备期没有旧会话时返回退出播放页`() {
        listOf(EntryPhase.Preparing, EntryPhase.AwaitingHandover).forEach { phase ->
            assertEquals(
                PlayerBackAction.StopAndLeave,
                back(state = PlaybackState.Idle, entryPhase = phase, inAppBackgroundPlayback = true),
            )
        }
    }

    @Test
    fun `准备期旧会话停在片尾时返回不撤回`() {
        // 片尾包含「已结束」与「暂停在片尾位置」两种形态，都按普通退出处理。
        listOf(PlaybackState.Ended, PlaybackState.Paused).forEach { state ->
            assertEquals(
                PlayerBackAction.StopAndLeave,
                back(state = state, entryPhase = EntryPhase.AwaitingHandover, atEpisodeEnd = true),
            )
        }
    }

    @Test
    fun `准备期顶栏返回与系统返回等价`() {
        listOf(EntryPhase.Preparing, EntryPhase.AwaitingHandover).forEach { phase ->
            assertFalse(playerTopBarBackEndsPlayback(phase))
        }
        listOf(null, EntryPhase.Accepted, EntryPhase.Failed, EntryPhase.Cancelled).forEach { phase ->
            assertTrue(playerTopBarBackEndsPlayback(phase))
        }
    }

    @Test
    fun `错误态返回退出播放页`() {
        assertEquals(
            PlayerBackAction.StopAndLeave,
            back(state = PlaybackState.Ended, failed = true, inAppBackgroundPlayback = true),
        )
    }

    @Test
    fun `没有播放内容时返回退出播放页`() {
        listOf(PlaybackState.Idle, PlaybackState.Loading).forEach { state ->
            assertEquals(
                PlayerBackAction.StopAndLeave,
                back(state = state, inAppBackgroundPlayback = true),
            )
        }
    }

    @Test
    fun `面板打开时系统返回只关闭面板`() {
        assertTrue(playerBackClosesPanel(anyPanelOpen = true))
        assertFalse(playerBackClosesPanel(anyPanelOpen = false))
    }

    @Test
    fun `Activity 重建但播放会话仍在时通知入口放行`() {
        listOf(PlaybackState.Playing, PlaybackState.Paused, PlaybackState.Ended).forEach { state ->
            assertEquals(
                NotificationOpenAction.ShowPlayerPage,
                notificationOpenAction(
                    action = OPEN_PLAYER_ACTION,
                    playbackState = state,
                    playerVisible = false,
                ),
            )
        }
    }

    @Test
    fun `已消费的通知入口不处理`() {
        assertEquals(
            NotificationOpenAction.Ignore,
            notificationOpenAction(
                action = null,
                playbackState = PlaybackState.Playing,
                playerVisible = false,
            ),
        )
    }

    @Test
    fun `没有播放会话或播放页已在前台时通知入口不处理`() {
        listOf(null, PlaybackState.Idle).forEach { state ->
            assertEquals(
                NotificationOpenAction.Ignore,
                notificationOpenAction(
                    action = OPEN_PLAYER_ACTION,
                    playbackState = state,
                    playerVisible = false,
                ),
            )
        }
        assertEquals(
            NotificationOpenAction.Ignore,
            notificationOpenAction(
                action = OPEN_PLAYER_ACTION,
                playbackState = PlaybackState.Playing,
                playerVisible = true,
            ),
        )
    }

    @Test
    fun `不是通知入口的动作不处理`() {
        assertEquals(
            NotificationOpenAction.Ignore,
            notificationOpenAction(
                action = "android.intent.action.MAIN",
                playbackState = PlaybackState.Playing,
                playerVisible = false,
            ),
        )
    }

    @Test
    fun `入口准备失败时已有播放会话只离开播放页`() {
        listOf(PlaybackState.Playing, PlaybackState.Paused, PlaybackState.Ended).forEach { state ->
            assertEquals(
                FailedEntryExit.LeavePlayerPage,
                failedEntryExit(state, atEpisodeEnd = false),
            )
        }
    }

    @Test
    fun `入口准备失败且旧会话停在片尾时结束播放`() {
        listOf(PlaybackState.Ended, PlaybackState.Paused).forEach { state ->
            assertEquals(
                FailedEntryExit.StopPlayback,
                failedEntryExit(state, atEpisodeEnd = true),
            )
        }
    }

    @Test
    fun `入口准备失败且没有播放会话时结束播放`() {
        assertEquals(FailedEntryExit.StopPlayback, failedEntryExit(null, atEpisodeEnd = false))
        assertEquals(FailedEntryExit.StopPlayback, failedEntryExit(PlaybackState.Idle, atEpisodeEnd = false))
    }

    @Test
    fun `库页面点正在播放的条目回到播放页`() {
        assertEquals(
            LibraryEntryAction.ReopenPlayerPage,
            libraryEntryAction(
                entryLocation = "root/ep1.mkv",
                currentLocation = "root/ep1.mkv",
                playbackActive = true,
            ),
        )
    }

    @Test
    fun `库页面点另一条视频按普通入口打开`() {
        assertEquals(
            LibraryEntryAction.OpenEntry,
            libraryEntryAction(
                entryLocation = "root/ep2.mkv",
                currentLocation = "root/ep1.mkv",
                playbackActive = true,
            ),
        )
    }

    @Test
    fun `没有正在播放的内容时按普通入口打开`() {
        assertEquals(
            LibraryEntryAction.OpenEntry,
            libraryEntryAction(
                entryLocation = "root/ep1.mkv",
                currentLocation = "root/ep1.mkv",
                playbackActive = false,
            ),
        )
        assertEquals(
            LibraryEntryAction.OpenEntry,
            libraryEntryAction(
                entryLocation = "root/ep1.mkv",
                currentLocation = null,
                playbackActive = true,
            ),
        )
    }
}
