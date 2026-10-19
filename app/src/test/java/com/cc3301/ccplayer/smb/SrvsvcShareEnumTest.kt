package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaSourceException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SrvsvcShareEnumTest {

    @Test
    fun `绑定请求是 srvsvc 的 DCE RPC bind`() {
        val expected = hex(
            "05000b03100000004800000001000000b810b810000000000100000000000100" +
                "c84f324b7016d30112785a47bf6ee18803000000045d888aeb1cc9119fe808002b10486002000000",
        )

        assertArrayEquals(expected, SrvsvcShareEnum.bindRequest(callId = 1))
    }

    @Test
    fun `共享枚举请求是 NetrShareEnum 层级 1`() {
        val expected = hex(
            "050000031000000038000000020000002000000000000f00" +
                "000000000100000001000000000002000000000000000000ffffffff00000000",
        )

        assertArrayEquals(expected, SrvsvcShareEnum.shareEnumRequest(callId = 2))
    }

    @Test
    fun `解析级别 1 应答里的共享名`() {
        val stub = hex(
            "01000000010000009f5c000002000000889d0000020000007ec800000000000071120000886e0000" +
                "00000080071000000500000000000000050000006d006500640069006100abab0000000000000000" +
                "0000000004000000000000000400000049005000430024000a000000000000000a00000052006500" +
                "6d006f00740065002000490050004300020000000000000000000000",
        )

        assertEquals(listOf("media", "IPC$"), SrvsvcShareEnum.parseShareNames(stub))
    }

    @Test
    fun `解析完整应答 PDU`() {
        val stub = hex(
            "01000000010000009f5c000002000000889d0000020000007ec800000000000071120000886e0000" +
                "00000080071000000500000000000000050000006d006500640069006100abab0000000000000000" +
                "0000000004000000000000000400000049005000430024000a000000000000000a00000052006500" +
                "6d006f00740065002000490050004300020000000000000000000000",
        )

        assertEquals(listOf("media", "IPC$"), SrvsvcShareEnum.parseShares(responseOf(stub)))
    }

    @Test
    fun `没有共享时返回空列表`() {
        val stub = hex("0100000001000000000200000000000000000000000000000000000000000000")

        assertEquals(emptyList<String>(), SrvsvcShareEnum.parseShareNames(stub))
    }

    @Test
    fun `RPC 层错误应答抛出失败原因`() {
        val fault = hex("05000003100000001000000002000000")
        val response = ByteArray(24)
        fault.copyInto(response)

        assertThrows(MediaSourceException::class.java) { SrvsvcShareEnum.parseShares(response) }
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun responseOf(stub: ByteArray): ByteArray {
        val length = 24 + stub.size
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(5, 0, 2, 3, 0x10, 0, 0, 0))
        buffer.putShort(length.toShort())
        buffer.putShort(0)
        buffer.putInt(2)
        buffer.putInt(stub.size)
        buffer.putShort(0)
        buffer.put(0)
        buffer.put(0)
        buffer.put(stub)
        return buffer.array()
    }
}
