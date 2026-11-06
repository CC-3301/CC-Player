package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音频焦点规则：暂降压音量、短暂丢失暂停后继续、永久丢失记下不应出声。 */
class AudioFocusPolicyTest {

    private val events = mutableListOf<String>()
    private var paused = false
    private var ducked: Boolean? = null
    private var duckChanges = 0

    private val policy = AudioFocusPolicy(
        pausePlayback = {
            events += "pause"
            paused = true
        },
        resumePlayback = {
            events += "resume"
            paused = false
        },
        setDucked = {
            ducked = it
            duckChanges++
        },
        isPaused = { paused },
    )

    @Test
    fun `暂降压低音量，恢复后还原且不暂停`() {
        policy.onFocusChange(AudioFocusChange.TransientDuck)
        assertEquals(true, ducked)
        assertTrue(events.isEmpty())

        policy.onFocusChange(AudioFocusChange.Gain)
        assertEquals(false, ducked)
        assertEquals(2, duckChanges)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `短暂丢失暂停，拿到焦点后继续`() {
        policy.onFocusChange(AudioFocusChange.TransientLoss)
        assertEquals(listOf("pause"), events)

        policy.onFocusChange(AudioFocusChange.Gain)
        assertEquals(listOf("pause", "resume"), events)
    }

    @Test
    fun `暂停中短暂丢失也记恢复意图，拿到焦点后继续`() {
        paused = true

        policy.onFocusChange(AudioFocusChange.TransientLoss)
        assertTrue(events.isEmpty())

        policy.onFocusChange(AudioFocusChange.Gain)
        assertEquals(listOf("resume"), events)
    }

    @Test
    fun `加载中短暂丢失先停住，拿到焦点后继续`() {
        // Loading 不是暂停态：要立刻停住，不能等加载完成再出声。
        policy.onFocusChange(AudioFocusChange.TransientLoss)
        assertEquals(listOf("pause"), events)

        policy.onFocusChange(AudioFocusChange.Gain)
        assertEquals(listOf("pause", "resume"), events)
    }

    @Test
    fun `永久丢失保持暂停，拿到焦点也不继续`() {
        policy.onFocusChange(AudioFocusChange.Loss)
        assertEquals(listOf("pause"), events)

        policy.onFocusChange(AudioFocusChange.Gain)
        assertEquals(listOf("pause"), events)
    }

    @Test
    fun `永久丢失期间要出声会再次停住，重新拿到焦点才放行`() {
        paused = true
        policy.onFocusChange(AudioFocusChange.Loss)
        assertTrue(events.isEmpty())

        // 用户按播放但焦点仍被占用：先停住，等 GAIN 再继续。
        paused = false
        policy.waitForFocus()
        assertEquals(listOf("pause"), events)

        policy.onFocusChange(AudioFocusChange.Gain)
        assertEquals(listOf("pause", "resume"), events)
    }

    @Test
    fun `暂降后叠加强制丢失会还原音量`() {
        policy.onFocusChange(AudioFocusChange.TransientDuck)
        policy.onFocusChange(AudioFocusChange.Loss)

        assertEquals(false, ducked)
        assertEquals(listOf("pause"), events)
    }

    @Test
    fun `结束或放弃焦点时把音量与意图重置`() {
        policy.onFocusChange(AudioFocusChange.TransientDuck)
        policy.onFocusChange(AudioFocusChange.TransientLoss)
        assertEquals(true, ducked)

        policy.reset()

        assertEquals(false, ducked)
        // 重置后不会因为旧恢复意图自动补播。
        policy.onFocusChange(AudioFocusChange.Gain)
        assertFalse(events.contains("resume"))
    }
}
