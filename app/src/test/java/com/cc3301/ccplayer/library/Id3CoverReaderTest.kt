package com.cc3301.ccplayer.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** ID3v2 封面解析：只解 APIC / PIC 帧，不解完整标签。 */
class Id3CoverReaderTest {

    @Test
    fun `ID3v2_3 的正面封面可读`() {
        val picture = byteArrayOf(1, 2, 3, 4)
        val tag = tag(major = 3, frames = listOf(apicFrame(picture, type = 3)))

        val result = readId3Cover(ByteSourceOf(tag))

        assertArrayEquals(picture, (result as Id3CoverResult.Picture).bytes)
    }

    @Test
    fun `超过系统三兆上限的大标签仍能取到封面`() {
        val picture = ByteArray(4 * 1024 * 1024) { index -> (index % 251).toByte() }
        val tag = tag(major = 3, frames = listOf(apicFrame(picture, type = 3)))

        val result = readId3Cover(ByteSourceOf(tag))

        assertArrayEquals(picture, (result as Id3CoverResult.Picture).bytes)
    }

    @Test
    fun `多张 APIC 取正面封面`() {
        val other = byteArrayOf(9, 9)
        val front = byteArrayOf(7, 7, 7)
        val tag = tag(
            major = 3,
            frames = listOf(
                apicFrame(other, type = 0),
                apicFrame(front, type = 3),
            ),
        )

        val result = readId3Cover(ByteSourceOf(tag))

        assertArrayEquals(front, (result as Id3CoverResult.Picture).bytes)
    }

    @Test
    fun `全局 unsynchronisation 还原后再解析`() {
        val picture = byteArrayOf(0xFF.toByte(), 0x00, 0x11, 0xFF.toByte(), 0xE0.toByte())
        val unsynchronised = addUnsynchronisation(apicFrame(picture, type = 3))
        val tag = id3Header(major = 3, flags = 0x80, tagSize = unsynchronised.size) + unsynchronised

        val result = readId3Cover(ByteSourceOf(tag))

        assertArrayEquals(picture, (result as Id3CoverResult.Picture).bytes)
    }

    @Test
    fun `压缩帧跳过并记为压缩封面`() {
        val compressed = frameHeader("APIC", ByteArray(8), flags = 0x80, major = 3)
        val tag = tag(major = 3, frames = listOf(compressed))

        val result = readId3Cover(ByteSourceOf(tag))

        assertEquals(Id3CoverMiss.CompressedPicture, (result as Id3CoverResult.Missing).reason)
    }

    @Test
    fun `压缩帧之后仍能取到未压缩封面`() {
        val picture = byteArrayOf(5, 6)
        val compressed = frameHeader("APIC", ByteArray(8), flags = 0x80, major = 3)
        val tag = tag(major = 3, frames = listOf(compressed, apicFrame(picture, type = 3)))

        val result = readId3Cover(ByteSourceOf(tag))

        assertArrayEquals(picture, (result as Id3CoverResult.Picture).bytes)
    }

    @Test
    fun `非 ID3 开头按无封面处理`() {
        val tag = "RIFF".toByteArray() + ByteArray(32)

        val result = readId3Cover(ByteSourceOf(tag))

        assertEquals(Id3CoverMiss.NotId3, (result as Id3CoverResult.Missing).reason)
    }

    @Test
    fun `标签体积超过解析上限按无封面处理`() {
        val tag = id3Header(major = 3, flags = 0, tagSize = ID3_MAX_TAG_BYTES + 1) + ByteArray(16)

        val result = readId3Cover(ByteSourceOf(tag))

        assertEquals(Id3CoverMiss.TagTooLarge, (result as Id3CoverResult.Missing).reason)
    }

    @Test
    fun `帧长越过标签末尾按读取失败处理`() {
        // 帧头声明 100 字节，标签体只剩 4 字节：属于截断，不能当成“确认无图”。
        val truncated = frameHeader("APIC", ByteArray(100), flags = 0, major = 3).copyOf(14)
        val tag = id3Header(major = 3, flags = 0, tagSize = truncated.size) + truncated

        val result = readId3Cover(ByteSourceOf(tag))

        assertEquals(Id3CoverFailure.ReadFailed, (result as Id3CoverResult.Failed).reason)
    }

    @Test
    fun `v2_2 的 PIC 帧可读`() {
        val picture = byteArrayOf(3, 2, 1)
        val body = byteArrayOf(0) +
            "JPG".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(3) +
            byteArrayOf(0) +
            picture
        val tag = tag(major = 2, frames = listOf(frameHeader("PIC", body, flags = 0, major = 2)))

        val result = readId3Cover(ByteSourceOf(tag))

        assertArrayEquals(picture, (result as Id3CoverResult.Picture).bytes)
    }

    @Test
    fun `v2_4 的 syncsafe 帧长可读`() {
        // 帧长超过 127 才能看出 syncsafe 与普通四字节整数的差别。
        val picture = ByteArray(200) { index -> index.toByte() }
        val tag = tag(major = 4, frames = listOf(apicFrame(picture, type = 3, major = 4)))

        val result = readId3Cover(ByteSourceOf(tag))

        assertArrayEquals(picture, (result as Id3CoverResult.Picture).bytes)
    }

    @Test
    fun `v2_4 的 data length indicator 跳过后再解析`() {
        val picture = ByteArray(200) { index -> index.toByte() }
        val apicBody = apicBody(picture, type = 3)
        // data length indicator 是帧体开头的 4 字节 syncsafe，值等于去掉它之后的帧体长度。
        val frameData = syncSafeBytes(apicBody.size) + apicBody
        val frame = frameHeader("APIC", frameData, flags = 0x01, major = 4)
        val tag = tag(major = 4, frames = listOf(frame))

        val result = readId3Cover(ByteSourceOf(tag))

        assertArrayEquals(picture, (result as Id3CoverResult.Picture).bytes)
    }

    @Test
    fun `扩展头之后仍能找到封面帧`() {
        val picture = byteArrayOf(8, 8)
        // v2.3 扩展头：长度字段 6 已包含它自己的 4 字节，后面跟 2 字节 flag。
        val extended = byteArrayOf(0, 0, 0, 6, 0, 0) + apicFrame(picture, type = 3)
        val tag = id3Header(major = 3, flags = 0x40, tagSize = extended.size) + extended

        val result = readId3Cover(ByteSourceOf(tag))

        assertArrayEquals(picture, (result as Id3CoverResult.Picture).bytes)
    }

    @Test
    fun `读取失败按解析失败收尾`() {
        val result = readId3Cover(object : CoverByteSource {
            override val size: Long = 100

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = 0
        })

        assertEquals(Id3CoverFailure.ReadFailed, (result as Id3CoverResult.Failed).reason)
    }

    private fun tag(major: Int, frames: List<ByteArray>): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
        return id3Header(major = major, flags = 0, tagSize = body.size) + body
    }

    private fun id3Header(major: Int, flags: Int, tagSize: Int): ByteArray =
        byteArrayOf(
            'I'.code.toByte(),
            'D'.code.toByte(),
            '3'.code.toByte(),
            major.toByte(),
            0,
            flags.toByte(),
        ) + syncSafeBytes(tagSize)

    private fun syncSafeBytes(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    /** 拉丁编码、JPEG、空描述的 APIC 帧体。 */
    private fun apicBody(picture: ByteArray, type: Int): ByteArray =
        byteArrayOf(0) +
            "image/jpeg".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) +
            byteArrayOf(type.toByte()) +
            byteArrayOf(0) +
            picture

    private fun apicFrame(picture: ByteArray, type: Int, major: Int = 3): ByteArray =
        frameHeader("APIC", apicBody(picture, type), flags = 0, major = major)

    /** 帧头加帧体；v2.2 的帧头只有 id 与 3 字节长度，没有 flag 字节。 */
    private fun frameHeader(id: String, body: ByteArray, flags: Int, major: Int): ByteArray {
        val idBytes = id.toByteArray(Charsets.ISO_8859_1)
        if (major == 2) {
            val size = byteArrayOf(
                ((body.size shr 16) and 0xFF).toByte(),
                ((body.size shr 8) and 0xFF).toByte(),
                (body.size and 0xFF).toByte(),
            )
            return idBytes + size + body
        }
        val size = if (major == 3) {
            byteArrayOf(
                ((body.size shr 24) and 0xFF).toByte(),
                ((body.size shr 16) and 0xFF).toByte(),
                ((body.size shr 8) and 0xFF).toByte(),
                (body.size and 0xFF).toByte(),
            )
        } else {
            syncSafeBytes(body.size)
        }
        return idBytes + size + byteArrayOf(0, flags.toByte()) + body
    }

    /** 按规范在每个 0xFF 之后插入 0x00。 */
    private fun addUnsynchronisation(bytes: ByteArray): ByteArray {
        val output = ByteArray(bytes.size + bytes.count { byte -> byte == 0xFF.toByte() })
        var written = 0
        bytes.forEach { byte ->
            output[written++] = byte
            if (byte == 0xFF.toByte()) output[written++] = 0
        }
        return output
    }

    /** 字节数组包成的随机读源；越界读取按读到末尾处理。 */
    private class ByteSourceOf(private val bytes: ByteArray) : CoverByteSource {

        override val size: Long get() = bytes.size.toLong()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position < 0 || position >= bytes.size) return 0
            val count = minOf(length, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }
    }
}
