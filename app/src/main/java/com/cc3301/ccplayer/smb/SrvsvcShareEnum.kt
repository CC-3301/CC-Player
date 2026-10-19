package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 经 srvsvc 命名管道枚举共享名：DCE/RPC 绑定后发 NetrShareEnum（级别 1）。
 *
 * SMBJ 没有共享枚举接口，这里只编解码本调用需要的最小报文。
 */
internal object SrvsvcShareEnum {

    /** 绑定请求。 */
    fun bindRequest(callId: Int = BIND_CALL_ID): ByteArray {
        val body = ByteBuffer.allocate(BIND_BODY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(MAX_FRAGMENT.toShort())
        body.putShort(MAX_FRAGMENT.toShort())
        body.putInt(0)
        body.put(1)
        body.put(byteArrayOf(0, 0, 0))
        body.putShort(0)
        body.putShort(1)
        body.put(SRVSVC_UUID)
        body.putInt(SRVSVC_VERSION)
        body.put(NDR_UUID)
        body.putInt(NDR_VERSION)
        return pdu(PDU_BIND, callId, body.array())
    }

    /** 绑定应答必须确实是 bind_ack。 */
    fun ensureBound(response: ByteArray) {
        if (response.size < HEADER_SIZE || response[PDU_TYPE_OFFSET].toInt() != PDU_BIND_ACK) {
            throw MediaSourceException(MediaFailure.Unknown, "SMB 共享枚举绑定失败")
        }
    }

    /** NetrShareEnum 请求：服务器名留空表示当前服务器，层级 1，返回全部共享。 */
    fun shareEnumRequest(callId: Int = ENUM_CALL_ID): ByteArray {
        val stub = ByteBuffer.allocate(ENUM_STUB_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        stub.putInt(0)
        stub.putInt(1)
        stub.putInt(1)
        stub.putInt(REFERENT)
        stub.putInt(0)
        stub.putInt(0)
        stub.putInt(PREFERRED_MAXIMUM_LENGTH)
        stub.putInt(0)

        val body = ByteBuffer.allocate(REQUEST_BODY_PREFIX + ENUM_STUB_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(ENUM_STUB_SIZE)
        body.putShort(0)
        body.putShort(OPNUM_SHARE_ENUM.toShort())
        body.put(stub.array())
        return pdu(PDU_REQUEST, callId, body.array())
    }

    /** 解析 NetrShareEnum 应答里的共享名。 */
    fun parseShares(response: ByteArray): List<String> {
        if (response.size < HEADER_SIZE) throw MediaSourceException(MediaFailure.Unknown, "SMB 共享枚举应答过短")
        when (response[PDU_TYPE_OFFSET].toInt()) {
            PDU_RESPONSE -> Unit
            PDU_FAULT -> throw MediaSourceException(MediaFailure.Unknown, "SMB 服务器拒绝了共享枚举")
            else -> throw MediaSourceException(MediaFailure.Unknown, "SMB 共享枚举应答类型不对")
        }
        val fragmentLength = u16(response, FRAGMENT_LENGTH_OFFSET)
        val authLength = u16(response, AUTH_LENGTH_OFFSET)
        val stubEnd = fragmentLength - authLength
        if (stubEnd < HEADER_SIZE + RESPONSE_BODY_PREFIX || stubEnd > response.size) {
            throw MediaSourceException(MediaFailure.Unknown, "SMB 共享枚举应答长度不对")
        }
        return parseShareNames(response.copyOfRange(HEADER_SIZE + RESPONSE_BODY_PREFIX, stubEnd))
    }

    /** 解析级别 1 的 NDR 结构：固定部分后是按顺序排的共享名与备注字符串。 */
    fun parseShareNames(stub: ByteArray): List<String> {
        if (stub.size < LEVEL_ONE_FIXED_SIZE) throw MediaSourceException(MediaFailure.Unknown, "SMB 共享枚举数据过短")
        val level = u32(stub, 0)
        val tag = u32(stub, 4)
        if (level != 1 || tag != 1) throw MediaSourceException(MediaFailure.Unknown, "SMB 共享枚举层级不是 1")
        val entriesRead = u32(stub, 12)
        val bufferReferent = u32(stub, 16)
        if (entriesRead == 0 || bufferReferent == 0) return emptyList()

        val maximumCount = u32(stub, 20)
        if (entriesRead !in 1..MAX_ENTRIES || maximumCount !in entriesRead..MAX_ENTRIES) {
            throw MediaSourceException(MediaFailure.Unknown, "SMB 共享枚举条目数不对")
        }

        val fixedEnd = LEVEL_ONE_FIXED_SIZE + maximumCount * SHARE_INFO_ONE_SIZE
        if (fixedEnd > stub.size) throw MediaSourceException(MediaFailure.Unknown, "SMB 共享枚举数据不完整")

        val nameReferents = IntArray(entriesRead) { index -> u32(stub, LEVEL_ONE_FIXED_SIZE + index * SHARE_INFO_ONE_SIZE) }
        val remarkReferents = IntArray(entriesRead) { index -> u32(stub, LEVEL_ONE_FIXED_SIZE + index * SHARE_INFO_ONE_SIZE + 8) }

        var cursor = fixedEnd
        val names = ArrayList<String>(entriesRead)
        for (index in 0 until entriesRead) {
            val name = if (nameReferents[index] != 0) {
                val read = readString(stub, cursor)
                cursor = read.nextOffset
                read.value
            } else {
                ""
            }
            if (remarkReferents[index] != 0) cursor = readString(stub, cursor).nextOffset
            names.add(name)
        }

        val errorCode = if (cursor + 8 <= stub.size) u32(stub, cursor + 8) else 0
        if (errorCode != 0 && names.isEmpty()) {
            throw MediaSourceException(MediaFailure.Unknown, "SMB 共享枚举失败：$errorCode")
        }
        return names
    }

    private data class ReadString(val value: String, val nextOffset: Int)

    /** NDR 宽字符串：最大计数、偏移、实际计数在前，字符随后；尾部补齐到 4 字节。 */
    private fun readString(data: ByteArray, offset: Int): ReadString {
        if (offset + STRING_HEADER_SIZE > data.size) throw MediaSourceException(MediaFailure.Unknown, "SMB 共享名数据不完整")
        val count = u32(data, offset + 8)
        val start = offset + STRING_HEADER_SIZE
        val byteCount = count * 2
        if (count > MAX_STRING_LENGTH || start + byteCount > data.size) {
            throw MediaSourceException(MediaFailure.Unknown, "SMB 共享名长度不对")
        }
        val value = String(data, start, byteCount, StandardCharsets.UTF_16LE).trimEnd('\u0000')
        val next = alignFour(start + byteCount)
        return ReadString(value, next)
    }

    private fun pdu(type: Int, callId: Int, body: ByteArray): ByteArray {
        val length = HEADER_SIZE + body.size
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(RPC_VERSION)
        buffer.put(RPC_VERSION_MINOR)
        buffer.put(type.toByte())
        buffer.put(FLAG_FIRST_LAST)
        buffer.putInt(DATA_REPRESENTATION)
        buffer.putShort(length.toShort())
        buffer.putShort(0)
        buffer.putInt(callId)
        buffer.put(body)
        return buffer.array()
    }

    private fun alignFour(offset: Int): Int = (offset + 3) and 3.inv()

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)

    private const val RPC_VERSION: Byte = 5
    private const val RPC_VERSION_MINOR: Byte = 0
    private const val PDU_REQUEST: Int = 0
    private const val PDU_RESPONSE: Int = 2
    private const val PDU_FAULT: Int = 3
    private const val PDU_BIND: Int = 11
    private const val PDU_BIND_ACK: Int = 12
    private const val FLAG_FIRST_LAST: Byte = 0x03
    private const val DATA_REPRESENTATION: Int = 0x10
    private const val HEADER_SIZE = 16
    private const val REQUEST_BODY_PREFIX = 8
    private const val RESPONSE_BODY_PREFIX = 8
    private const val FRAGMENT_LENGTH_OFFSET = 8
    private const val AUTH_LENGTH_OFFSET = 10
    private const val PDU_TYPE_OFFSET = 2
    private const val BIND_BODY_SIZE = 56
    private const val ENUM_STUB_SIZE = 32
    private const val LEVEL_ONE_FIXED_SIZE = 24
    private const val SHARE_INFO_ONE_SIZE = 12
    private const val STRING_HEADER_SIZE = 12
    private const val MAX_FRAGMENT = 4280
    private const val OPNUM_SHARE_ENUM = 15
    private const val BIND_CALL_ID = 1
    private const val ENUM_CALL_ID = 2
    private const val REFERENT = 0x00020000
    private const val PREFERRED_MAXIMUM_LENGTH = -1
    private const val MAX_ENTRIES = 10000
    private const val MAX_STRING_LENGTH = 4096
    private const val SRVSVC_VERSION = 3
    private const val NDR_VERSION = 2

    /** srvsvc 接口：4b324fc8-1670-01d3-1278-5a47bf6ee188 v3.0。 */
    private val SRVSVC_UUID = byteArrayOf(
        0xc8.toByte(), 0x4f, 0x32, 0x4b, 0x70, 0x16, 0xd3.toByte(), 0x01,
        0x12, 0x78, 0x5a, 0x47, 0xbf.toByte(), 0x6e, 0xe1.toByte(), 0x88.toByte(),
    )

    /** NDR 传输语法：8a885d04-1ceb-11c9-9fe8-08002b104860 v2.0。 */
    private val NDR_UUID = byteArrayOf(
        0x04, 0x5d, 0x88.toByte(), 0x8a.toByte(), 0xeb.toByte(), 0x1c, 0xc9.toByte(), 0x11,
        0x9f.toByte(), 0xe8.toByte(), 0x08, 0x00, 0x2b, 0x10, 0x48, 0x60,
    )
}
