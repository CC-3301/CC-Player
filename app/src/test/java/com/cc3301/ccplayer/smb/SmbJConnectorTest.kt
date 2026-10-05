package com.cc3301.ccplayer.smb

import com.hierynomus.mssmb2.SMB2Dialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
