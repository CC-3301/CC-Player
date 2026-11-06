package com.cc3301.ccplayer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 焦点登记状态：短暂丢失后不能凭旧状态放行，GAIN 后才能恢复。 */
class AudioFocusRegistrationTest {

    @Test
    fun `短暂丢失后不再持有焦点，GAIN 后恢复`() {
        val registration = AudioFocusRegistration()
        registration.onRequest(resultGranted = true, resultAccepted = true)
        assertTrue(registration.isGranted)

        registration.onTransientLoss()
        // 仍登记着（等 GAIN），但此刻不能出声。
        assertTrue(registration.isRegistered)
        assertFalse(registration.isGranted)

        registration.onGain()
        assertTrue(registration.isGranted)
    }

    @Test
    fun `永久丢失后不再持有焦点`() {
        val registration = AudioFocusRegistration()
        registration.onRequest(resultGranted = true, resultAccepted = true)

        registration.onLoss()

        assertFalse(registration.isGranted)
    }

    @Test
    fun `申请失败不留登记，之后可以重试`() {
        val registration = AudioFocusRegistration()
        registration.onRequest(resultGranted = false, resultAccepted = false)

        assertFalse(registration.isRegistered)
        assertFalse(registration.isGranted)

        registration.onRequest(resultGranted = true, resultAccepted = true)
        assertTrue(registration.isGranted)
    }

    @Test
    fun `延迟授予在 GAIN 之前不算拿到`() {
        val registration = AudioFocusRegistration()
        registration.onRequest(resultGranted = false, resultAccepted = true)

        assertTrue(registration.isRegistered)
        assertFalse(registration.isGranted)

        registration.onGain()
        assertTrue(registration.isGranted)
    }

    @Test
    fun `放弃焦点清掉登记与授予`() {
        val registration = AudioFocusRegistration()
        registration.onRequest(resultGranted = true, resultAccepted = true)

        registration.abandon()

        assertFalse(registration.isRegistered)
        assertFalse(registration.isGranted)
    }
}
