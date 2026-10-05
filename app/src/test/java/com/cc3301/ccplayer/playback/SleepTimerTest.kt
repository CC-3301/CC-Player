package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自定义定时时长的输入校验：只接受 1~1440 的整数分钟。
 */
class SleepTimerTest {

    @Test
    fun `自定义分钟数接受范围内的整数`() {
        assertEquals(1, customSleepMinutes("1"))
        assertEquals(45, customSleepMinutes("45"))
        assertEquals(1440, customSleepMinutes("1440"))
    }

    @Test
    fun `自定义分钟数忽略首尾空白`() {
        assertEquals(45, customSleepMinutes(" 45 "))
    }

    @Test
    fun `零不是合法时长`() {
        assertNull(customSleepMinutes("0"))
    }

    @Test
    fun `负数不是合法时长`() {
        assertNull(customSleepMinutes("-5"))
    }

    @Test
    fun `超过 1440 分钟不是合法时长`() {
        assertNull(customSleepMinutes("1441"))
    }

    @Test
    fun `空输入不是合法时长`() {
        assertNull(customSleepMinutes(""))
        assertNull(customSleepMinutes("   "))
    }

    @Test
    fun `非数字不是合法时长`() {
        assertNull(customSleepMinutes("半小时"))
        assertNull(customSleepMinutes("45 分钟"))
        assertNull(customSleepMinutes("1.5"))
        assertNull(customSleepMinutes("1e2"))
    }
}
