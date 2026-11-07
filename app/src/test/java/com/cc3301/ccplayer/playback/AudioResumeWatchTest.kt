package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频输出重建后「重新出声」的判据：连续前进才算，跳变与刷新不算，读不到足够多次才算没有音频。
 */
class AudioResumeWatchTest {

    @Test
    fun `连续两次前进才确认重新出声`() {
        val watch = AudioResumeWatch()

        assertEquals(AudioResumeWatch.Sample.Idle, watch.sample(10.0, 0))
        assertEquals(AudioResumeWatch.Sample.Pending, watch.sample(10.1, 50))
        assertEquals(AudioResumeWatch.Sample.Confirmed, watch.sample(10.2, 100))

        // 出声时刻取这段连续前进里第一次前进的那一刻，不是确认那一刻。
        assertEquals(50L, watch.resumedAtMillis)
    }

    @Test
    fun `单次跳变不算重新出声`() {
        val watch = AudioResumeWatch()

        watch.sample(10.0, 0)
        // 跳变只让采样前进一次，随后停住就打断连续。
        assertEquals(AudioResumeWatch.Sample.Pending, watch.sample(12.0, 50))
        assertEquals(AudioResumeWatch.Sample.Idle, watch.sample(12.0, 100))
        assertEquals(AudioResumeWatch.Sample.Idle, watch.sample(12.0, 150))
        assertNull(watch.resumedAtMillis)
    }

    @Test
    fun `位置中性刷新后的跳变不算出声`() {
        val watch = AudioResumeWatch()

        watch.sample(10.0, 0)
        watch.sample(10.1, 50)
        // seek 与刷新会让 audio-pts 整体跳走：基线清掉，跳变本身不能算连续前进。
        watch.reset()

        assertEquals(AudioResumeWatch.Sample.Idle, watch.sample(13.0, 100))
        assertEquals(AudioResumeWatch.Sample.Pending, watch.sample(13.1, 150))
        assertEquals(150L, watch.resumedAtMillis)
    }

    @Test
    fun `连续读不到音频位置才算没有音频输出`() {
        val watch = AudioResumeWatch()

        repeat(AudioResumeWatch.UNAVAILABLE_MISSES - 1) {
            assertEquals(AudioResumeWatch.Sample.Idle, watch.sample(null, it * 50L))
            assertFalse(watch.unavailable)
        }
        assertEquals(AudioResumeWatch.Sample.Unavailable, watch.sample(null, 300))
        assertTrue(watch.unavailable)
    }

    @Test
    fun `读不到音频位置会打断连续`() {
        val watch = AudioResumeWatch()

        watch.sample(10.0, 0)
        watch.sample(10.1, 50)
        assertEquals(AudioResumeWatch.Sample.Idle, watch.sample(null, 100))
        // 读不到之后先重建基线：下一条位置不能拿旧基线冒充前进。
        assertEquals(AudioResumeWatch.Sample.Idle, watch.sample(10.2, 150))
        assertEquals(AudioResumeWatch.Sample.Pending, watch.sample(10.3, 200))
    }
}
