package com.cc3301.ccplayer.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.messages.SMB2Echo
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.smb.SMBBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    // 存活确认报文契约：生产 ping 用同一条 SMBJ 构造，传参链只有在真实服务器上才能观察。
    @Test
    fun `存活确认报文带方言序列化为 68 字节`() {
        // Echo 报文是 64 字节报文头加 4 字节消息体；报文头不带方言时序列化会抛空指针。
        val buffer = SMBBuffer()

        SMB2Echo(SMB2Dialect.SMB_3_1_1).write(buffer)

        assertEquals(68, buffer.getCompactData().size)
    }

    @Test
    fun `报文头的信用额度随方言变化，不是写死值`() {
        // 报文头第 6、7 字节是信用额度：SMB 2.0.2 保留为 0，更高方言按请求写出 1。
        assertEquals(0, creditChargeOf(SMB2Dialect.SMB_2_0_2))
        assertEquals(1, creditChargeOf(SMB2Dialect.SMB_3_1_1))
    }

    @Test
    fun `没有方言的存活确认报文序列化必抛空指针`() {
        // 对照证据：报文构造漏掉方言时，序列化取方言序号就抛空指针。
        val failure = assertThrows(NullPointerException::class.java) {
            SMB2Echo().write(SMBBuffer())
        }

        assertTrue(failure.message.orEmpty().contains("SMB2Dialect"))
    }

    /** 序列化后报文头里的信用额度（第 6、7 字节，小端）。 */
    private fun creditChargeOf(dialect: SMB2Dialect): Int {
        val buffer = SMBBuffer()
        SMB2Echo(dialect).write(buffer)
        val header = buffer.getCompactData()
        return (header[6].toInt() and 0xFF) or ((header[7].toInt() and 0xFF) shl 8)
    }
}
