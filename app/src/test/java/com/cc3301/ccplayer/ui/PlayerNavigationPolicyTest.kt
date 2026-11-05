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
    ): PlayerBackAction = playerBackAction(
        state = state,
        switching = switching,
        restoring = restoring,
        initialLoading = initialLoading,
        failed = failed,
        inAppBackgroundPlayback = inAppBackgroundPlayback,
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
    fun `通知入口只回到已有播放会话的播放页`() {
        assertEquals(
            NotificationOpenAction.ShowPlayerPage,
            notificationOpenAction(
                action = OPEN_PLAYER_ACTION,
                restored = false,
                playbackState = PlaybackState.Playing,
                playerVisible = false,
            ),
        )
        assertEquals(
            NotificationOpenAction.ShowPlayerPage,
            notificationOpenAction(
                action = OPEN_PLAYER_ACTION,
                restored = false,
                playbackState = PlaybackState.Paused,
                playerVisible = false,
            ),
        )
    }

    @Test
    fun `界面重建时的通知入口不处理`() {
        assertEquals(
            NotificationOpenAction.Ignore,
            notificationOpenAction(
                action = OPEN_PLAYER_ACTION,
                restored = true,
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
                    restored = false,
                    playbackState = state,
                    playerVisible = false,
                ),
            )
        }
        assertEquals(
            NotificationOpenAction.Ignore,
            notificationOpenAction(
                action = OPEN_PLAYER_ACTION,
                restored = false,
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
                restored = false,
                playbackState = PlaybackState.Playing,
                playerVisible = false,
            ),
        )
        assertEquals(
            NotificationOpenAction.Ignore,
            notificationOpenAction(
                action = null,
                restored = false,
                playbackState = PlaybackState.Playing,
                playerVisible = false,
            ),
        )
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
