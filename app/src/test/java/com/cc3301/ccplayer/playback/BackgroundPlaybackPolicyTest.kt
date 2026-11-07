package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台播放策略：开关与前后台的到达顺序都要能触发暂停，通知动作集合随状态收敛。
 */
class BackgroundPlaybackPolicyTest {

    private var pauses = 0
    private var changes = 0

    private val policy = BackgroundPlaybackPolicy(
        pausePlayback = { pauses++ },
        onPolicyChanged = { changes++ },
    )

    @Test
    fun `关闭开关后退到后台立即暂停`() {
        policy.setBackgroundPlaybackEnabled(false)

        policy.setAppInForeground(false)

        assertEquals(1, pauses)
    }

    @Test
    fun `绑定晚于退后台时同步前后台仍会暂停`() {
        policy.setBackgroundPlaybackEnabled(false)
        // 绑定完成时 Activity 已处于后台，同步上报 false。
        policy.setAppInForeground(false)

        assertEquals(1, pauses)
    }

    @Test
    fun `播放开始后再次上报后台仍会暂停`() {
        policy.setBackgroundPlaybackEnabled(false)
        policy.setAppInForeground(false)

        // 开播后重复上报同一状态，策略仍要补一次暂停。
        policy.setAppInForeground(false)

        assertEquals(2, pauses)
    }

    @Test
    fun `开关首值晚于退后台时到达也会暂停`() {
        policy.setAppInForeground(false)

        policy.setBackgroundPlaybackEnabled(false)

        assertEquals(1, pauses)
    }

    @Test
    fun `开启后台播放时退到后台不暂停`() {
        policy.setAppInForeground(false)

        assertEquals(0, pauses)
    }

    @Test
    fun `前台关闭开关不暂停`() {
        policy.setBackgroundPlaybackEnabled(false)

        assertEquals(0, pauses)
    }

    @Test
    fun `策略变化时刷新通知`() {
        policy.setAppInForeground(false)
        policy.setBackgroundPlaybackEnabled(false)

        assertEquals(2, changes)
    }

    @Test
    fun `后台关闭时只允许暂停`() {
        policy.setBackgroundPlaybackEnabled(false)
        policy.setAppInForeground(false)

        assertTrue(policy.allows(MediaSessionAction.Pause))
        assertFalse(policy.allows(MediaSessionAction.Play))
        assertFalse(policy.allows(MediaSessionAction.Previous))
        assertFalse(policy.allows(MediaSessionAction.Next))
    }

    @Test
    fun `后台关闭时切集动作不生效`() {
        policy.setBackgroundPlaybackEnabled(false)
        policy.setAppInForeground(false)

        assertFalse(policy.allows(MediaSessionAction.Next))
        assertFalse(policy.allows(MediaSessionAction.Previous))
    }

    @Test
    fun `前台或开启后台播放时所有动作生效`() {
        policy.setAppInForeground(true)
        MediaSessionAction.entries.forEach { action -> assertTrue(policy.allows(action)) }

        policy.setAppInForeground(false)
        MediaSessionAction.entries.forEach { action -> assertTrue(policy.allows(action)) }
    }

    @Test
    fun `dispatch 按策略拦截`() {
        val applied = mutableListOf<MediaSessionAction>()
        policy.setBackgroundPlaybackEnabled(false)
        policy.setAppInForeground(false)

        policy.dispatch(MediaSessionAction.Play) { applied += it }
        policy.dispatch(MediaSessionAction.Next) { applied += it }
        policy.dispatch(MediaSessionAction.Pause) { applied += it }

        assertEquals(listOf(MediaSessionAction.Pause), applied)
    }

    @Test
    fun `后台关闭时通知栏只剩暂停`() {
        policy.setBackgroundPlaybackEnabled(false)
        policy.setAppInForeground(false)

        assertEquals(listOf(MediaSessionAction.Pause), policy.notificationActions(PlaybackState.Playing))
        assertEquals(listOf(MediaSessionAction.Pause), policy.notificationActions(PlaybackState.Paused))
    }

    @Test
    fun `前台或开启后台播放时通知栏保留上下集与播放暂停`() {
        policy.setAppInForeground(true)
        assertEquals(
            listOf(MediaSessionAction.Previous, MediaSessionAction.Pause, MediaSessionAction.Next),
            policy.notificationActions(PlaybackState.Playing),
        )
        assertEquals(
            listOf(MediaSessionAction.Previous, MediaSessionAction.Play, MediaSessionAction.Next),
            policy.notificationActions(PlaybackState.Paused),
        )

        policy.setAppInForeground(false)
        assertEquals(
            listOf(MediaSessionAction.Previous, MediaSessionAction.Pause, MediaSessionAction.Next),
            policy.notificationActions(PlaybackState.Playing),
        )
    }

    @Test
    fun `目标加载期间通知栏保留上下集与播放暂停`() {
        policy.setAppInForeground(true)

        assertEquals(
            listOf(MediaSessionAction.Previous, MediaSessionAction.Play, MediaSessionAction.Next),
            policy.notificationActions(
                PlaybackState.Loading,
                switching = true,
                initialLoading = false,
                restoring = false,
            ),
        )
    }

    @Test
    fun `服务下发链路先按加载阶段过滤再按后台策略过滤`() {
        policy.setAppInForeground(true)
        val applied = mutableListOf<MediaSessionAction>()

        MediaSessionAction.entries.forEach { action ->
            dispatchMediaAction(
                action,
                state = PlaybackState.Loading,
                switching = true,
                restoring = false,
            ) { allowedAction ->
                policy.dispatch(allowedAction) { passed -> applied += passed }
            }
        }

        assertEquals(
            listOf(
                MediaSessionAction.Play,
                MediaSessionAction.Pause,
                MediaSessionAction.Previous,
                MediaSessionAction.Next,
            ),
            applied,
        )
    }

    @Test
    fun `恢复原片期间通知栏保留上下集与播放暂停`() {
        policy.setAppInForeground(true)

        assertEquals(
            listOf(MediaSessionAction.Previous, MediaSessionAction.Play, MediaSessionAction.Next),
            policy.notificationActions(
                PlaybackState.Paused,
                switching = false,
                initialLoading = false,
                restoring = true,
            ),
        )
    }

    @Test
    fun `后台关闭时加载阶段不滤掉暂停`() {
        policy.setBackgroundPlaybackEnabled(false)
        policy.setAppInForeground(false)

        assertEquals(
            listOf(MediaSessionAction.Pause),
            policy.notificationActions(
                PlaybackState.Loading,
                switching = true,
                initialLoading = false,
                restoring = false,
            ),
        )
        assertEquals(
            listOf(MediaSessionAction.Pause),
            policy.notificationActions(
                PlaybackState.Loading,
                switching = false,
                initialLoading = true,
                restoring = false,
            ),
        )
    }

    @Test
    fun `首次加载期间通知栏不显示动作`() {
        policy.setAppInForeground(true)

        assertEquals(
            emptyList<MediaSessionAction>(),
            policy.notificationActions(
                PlaybackState.Loading,
                switching = false,
                initialLoading = true,
                restoring = false,
            ),
        )
    }
}
