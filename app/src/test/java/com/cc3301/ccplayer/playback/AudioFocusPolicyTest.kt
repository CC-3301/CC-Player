package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音频焦点规则：暂降压音量、短暂丢失暂停后继续、永久丢失保持暂停。 */
class AudioFocusPolicyTest {

    private val events = mutableListOf<String>()
    private var playing = true
    private var ducked: Boolean? = null
    private var duckChanges = 0

    private val policy = AudioFocusPolicy(
        pausePlayback = {
            events += "pause"
            playing = false
        },
        resumePlayback = {
            events += "resume"
            playing = true
        },
        setDucked = {
            ducked = it
            duckChanges++
        },
        isPlaying = { playing },
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
    fun `永久丢失保持暂停，拿到焦点也不继续`() {
        policy.onFocusChange(AudioFocusChange.Loss)
        assertEquals(listOf("pause"), events)

        policy.onFocusChange(AudioFocusChange.Gain)
        assertEquals(listOf("pause"), events)
    }

    @Test
    fun `暂降后叠加强制丢失会还原音量`() {
        policy.onFocusChange(AudioFocusChange.TransientDuck)
        policy.onFocusChange(AudioFocusChange.Loss)

        assertEquals(false, ducked)
        assertEquals(listOf("pause"), events)
    }
}
