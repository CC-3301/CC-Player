package com.cc3301.ccplayer.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbJConnectorTest {

    @Test
    fun `只协商 SMB2 与 SMB3 方言`() {
        val config = SmbJConnector.smb2OnlyConfig()

        assertEquals(
            setOf(
                SMB2Dialect.SMB_2_0_2,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_1_1,
            ),
            config.supportedDialects,
        )
        assertFalse(SMB2Dialect.UNKNOWN in config.supportedDialects)
    }

    @Test
    fun `关掉 SMB1 多协议协商`() {
        assertFalse(SmbJConnector.smb2OnlyConfig().isUseMultiProtocolNegotiate)
    }

    @Test
    fun `建连超时由 socket 工厂给出`() {
        // smbj 的 withTimeout 管不到建连，建连超时只能由 socket 工厂带出去；3 秒是构造参数。
        assertTrue(SmbJConnector.smb2OnlyConfig().socketFactory is ProxySocketFactory)
    }

    @Test
    fun `socket 空闲不短于闲置回收窗口，单次读另有上限`() {
        val config = SmbJConnector.smb2OnlyConfig()

        assertEquals(150_000, config.soTimeout)
        assertEquals(8_000L, config.readTimeout)
        assertEquals(8_000L, config.writeTimeout)
        assertEquals(8_000L, config.transactTimeout)
    }
}
