package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.streaming.ByteSource

/** ID3v2 标签体积上限；系统实现超过 3 MB 就整段丢弃，这里放宽到 16 MB。 */
internal const val ID3_MAX_TAG_BYTES = 16 * 1024 * 1024

/** 标签头长度。 */
private const val ID3_HEADER_BYTES = 10

/** 标签头 flag：全局 unsynchronisation。 */
private const val ID3_FLAG_UNSYNCHRONISATION = 0x80

/** 标签头 flag：扩展头。 */
private const val ID3_FLAG_EXTENDED_HEADER = 0x40

/** 帧 flag（v2.2 / v2.3）：压缩。 */
private const val FRAME_FLAG_COMPRESSED_V23 = 0x80

/** 帧 flag（v2.2 / v2.3）：加密。 */
private const val FRAME_FLAG_ENCRYPTED_V23 = 0x40

/** 帧 flag（v2.4）：压缩。 */
private const val FRAME_FLAG_COMPRESSED_V24 = 0x08

/** 帧 flag（v2.4）：加密。 */
private const val FRAME_FLAG_ENCRYPTED_V24 = 0x04

/** 帧 flag（v2.4）：逐帧 unsynchronisation。 */
private const val FRAME_FLAG_UNSYNCHRONISATION_V24 = 0x02

/** 帧 flag（v2.4）：帧体开头带 4 字节 data length indicator。 */
private const val FRAME_FLAG_DATA_LENGTH_V24 = 0x01

/** 图片类型：其他；正面封面缺失时退回这一张。 */
private const val PICTURE_TYPE_OTHER = 0x00

/** 图片类型：正面封面。 */
private const val PICTURE_TYPE_FRONT_COVER = 0x03

/** APIC 帧的最小长度：编码 1 字节、MIME 结束符、类型 1 字节、空描述结束符。 */
private const val MIN_APIC_BYTES = 5

/** 一次 ID3v2 封面查找的结果。 */
internal sealed interface Id3CoverResult {

    /** 取到封面帧的原始图片字节（JPEG / PNG）。 */
    data class Picture(val bytes: ByteArray) : Id3CoverResult

    /** 标签可读，但没有可用的封面帧；按「确认无图」处理。 */
    data class Missing(val reason: Id3CoverMiss) : Id3CoverResult

    /** 读取失败；按抽取失败处理，可重试。 */
    data class Failed(val reason: Id3CoverFailure) : Id3CoverResult
}

/** 确定没有可用封面的原因。 */
internal enum class Id3CoverMiss {
    /** 文件不是以 ID3v2 标签开头，没有可解析的内嵌图。 */
    NotId3,

    /** 标签版本不认识。 */
    UnsupportedVersion,

    /** 标签体积超过解析上限。 */
    TagTooLarge,

    /** 标签里没有 APIC / PIC 帧。 */
    NoPicture,

    /** 只有压缩或加密的封面帧。 */
    CompressedPicture,
}

/** 解析失败的原因；这类结果按抽取失败处理，不写入「无图」缓存。 */
internal enum class Id3CoverFailure {
    /** 读取字节失败。 */
    ReadFailed,
}

/** 可按位置随机读的字节源；解析只读标签段，不读整个文件。 */
internal interface CoverByteSource {

    val size: Long

    /** 从 [position] 起读最多 [length] 字节到 [buffer] 的 [offset]；读到末尾返回 0。 */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** 把网络随机读句柄接到封面解析上。 */
internal class ByteSourceCoverSource(private val source: ByteSource) : CoverByteSource {

    override val size: Long get() = source.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        source.readAt(position, buffer, offset, length)
}

/**
 * 解析 ID3v2 的封面帧：只读标签头与帧头，遇到 APIC / PIC 才读帧体。
 *
 * 支持 v2.2 / v2.3 / v2.4、全局与逐帧 unsynchronisation、扩展头、syncsafe 长度、多图取舍；
 * 压缩帧与加密帧跳过。任何读取异常都按 [Id3CoverFailure.ReadFailed] 收尾，不向上抛。
 */
internal fun readId3Cover(source: CoverByteSource): Id3CoverResult = try {
    readId3CoverOrThrow(source)
} catch (failure: Exception) {
    Id3CoverResult.Failed(Id3CoverFailure.ReadFailed)
}

private fun readId3CoverOrThrow(source: CoverByteSource): Id3CoverResult {
    val header = source.readFully(0, ID3_HEADER_BYTES)
        ?: return Id3CoverResult.Failed(Id3CoverFailure.ReadFailed)
    if (!isId3Header(header)) return Id3CoverResult.Missing(Id3CoverMiss.NotId3)
    val major = header[3].toInt() and 0xFF
    if (major !in 2..4) return Id3CoverResult.Missing(Id3CoverMiss.UnsupportedVersion)
    val flags = header[5].toInt() and 0xFF
    val tagSize = syncSafe(header, 6)
    if (tagSize <= 0) return Id3CoverResult.Missing(Id3CoverMiss.NoPicture)
    if (tagSize > ID3_MAX_TAG_BYTES) return Id3CoverResult.Missing(Id3CoverMiss.TagTooLarge)
    val tagEnd = ID3_HEADER_BYTES.toLong() + tagSize
    // 全局 unsynchronisation 动过帧头本身，只能先整段还原再按帧解析；
    // v2.4 的 unsynchronisation 是逐帧的，整段标志不参与。
    if (major <= 3 && flags and ID3_FLAG_UNSYNCHRONISATION != 0) {
        val body = source.readFully(ID3_HEADER_BYTES.toLong(), tagSize)
            ?: return Id3CoverResult.Failed(Id3CoverFailure.ReadFailed)
        // 原地还原，不再复制一份同样大的缓冲区；标签上限 16 MB，峰值就是这一份。
        val restored = removeUnsynchronisationInPlace(body)
        return parseFrames(ByteArrayCoverSource(body, restored.toLong()), major, flags)
    }
    return parseFrames(SlicedCoverSource(source, ID3_HEADER_BYTES.toLong(), tagEnd), major, flags)
}

private fun parseFrames(body: CoverByteSource, major: Int, headerFlags: Int): Id3CoverResult {
    val frameHeaderSize = if (major == 2) 6 else 10
    val idLength = if (major == 2) 3 else 4
    var position = extendedHeaderSize(body, major, headerFlags)
    var compressedPicture = false
    val pictures = ArrayList<ApicPicture>()
    while (position + frameHeaderSize <= body.size) {
        val header = body.readFully(position, frameHeaderSize) ?: break
        // 帧 id 全零即进入 padding，后面没有帧了。
        if (header.all { byte -> byte == 0.toByte() }) break
        if (!isFrameId(header, idLength)) break
        val frameId = String(header, 0, idLength, Charsets.ISO_8859_1)
        val size = frameSize(header, major)
        if (size <= 0) break
        // 帧长越过标签末尾说明标签被截断或损坏；已经取到封面就以它为准，
        // 还没取到才按读取失败处理，不能当成“确认无图”。
        if (position + frameHeaderSize + size > body.size) {
            if (pictures.isEmpty()) return Id3CoverResult.Failed(Id3CoverFailure.ReadFailed)
            break
        }
        val flags = frameFlags(header, major)
        if (frameId == pictureFrameId(major)) {
            if (flags.compressed || flags.encrypted) {
                compressedPicture = true
            } else {
                val frame = body.readFully(position + frameHeaderSize, size.toInt())
                    ?: return Id3CoverResult.Failed(Id3CoverFailure.ReadFailed)
                val data = if (flags.unsynchronised) removeUnsynchronisation(frame) else frame
                // data length indicator 是帧体开头的 4 字节 syncsafe，跳过它再解析 APIC。
                val picture = if (flags.dataLengthIndicator) {
                    if (data.size <= 4) null else data.copyOfRange(4, data.size)
                } else {
                    data
                }
                picture?.let { bytes -> parseApic(bytes, major)?.let { found -> pictures += found } }
            }
        }
        position += frameHeaderSize + size
    }
    val best = choosePicture(pictures)
    return when {
        best != null -> Id3CoverResult.Picture(best.bytes)
        compressedPicture -> Id3CoverResult.Missing(Id3CoverMiss.CompressedPicture)
        else -> Id3CoverResult.Missing(Id3CoverMiss.NoPicture)
    }
}

/**
 * 扩展头占用的字节数（含长度字段自己）。
 *
 * v2.3 规范：长度字段的值不含它自己的 4 字节（6 或 10 字节描述的是后面的内容），所以要再加 4。
 * v2.4 规范：长度字段是整个扩展头的长度（含自身 4 字节）且为 syncsafe，直接按该值跳过。
 */
private fun extendedHeaderSize(body: CoverByteSource, major: Int, headerFlags: Int): Long {
    if (headerFlags and ID3_FLAG_EXTENDED_HEADER == 0) return 0
    return when (major) {
        2 -> 0
        3 -> body.readFully(0, 4)?.let { bytes -> 4L + plainInt(bytes, 0, 4) } ?: 0
        else -> body.readFully(0, 4)?.let { bytes -> syncSafe(bytes, 0).toLong() } ?: 0
    }
}

private fun frameSize(header: ByteArray, major: Int): Long = when (major) {
    2 -> plainInt(header, 3, 3)
    3 -> plainInt(header, 4, 4)
    else -> syncSafe(header, 4).toLong()
}

private data class FrameFlags(
    val compressed: Boolean,
    val encrypted: Boolean,
    val unsynchronised: Boolean,
    val dataLengthIndicator: Boolean = false,
)

private fun frameFlags(header: ByteArray, major: Int): FrameFlags {
    // v2.2 的帧头只有 id 与长度，没有 flag 字节。
    if (major == 2) return FrameFlags(compressed = false, encrypted = false, unsynchronised = false)
    val low = header[header.size - 1].toInt() and 0xFF
    return when (major) {
        3 -> FrameFlags(
            compressed = low and FRAME_FLAG_COMPRESSED_V23 != 0,
            encrypted = low and FRAME_FLAG_ENCRYPTED_V23 != 0,
            unsynchronised = false,
        )

        else -> FrameFlags(
            compressed = low and FRAME_FLAG_COMPRESSED_V24 != 0,
            encrypted = low and FRAME_FLAG_ENCRYPTED_V24 != 0,
            unsynchronised = low and FRAME_FLAG_UNSYNCHRONISATION_V24 != 0,
            dataLengthIndicator = low and FRAME_FLAG_DATA_LENGTH_V24 != 0,
        )
    }
}

private data class ApicPicture(val type: Int, val bytes: ByteArray)

/**
 * 解析 APIC（v2.2 为 PIC）帧体：编码 1 字节，MIME（v2.2 是 3 字节格式），图片类型 1 字节，
 * 描述（按编码决定结束符），其余是图片数据。
 */
private fun parseApic(frame: ByteArray, major: Int): ApicPicture? {
    if (frame.size < MIN_APIC_BYTES) return null
    val encoding = frame[0].toInt() and 0xFF
    var position = 1
    position = if (major == 2) {
        position + 3
    } else {
        skipTerminated(frame, position, utf16 = false)
    }
    if (position >= frame.size) return null
    val type = frame[position].toInt() and 0xFF
    position += 1
    position = skipTerminated(frame, position, utf16 = encoding == 1 || encoding == 2)
    if (position >= frame.size) return null
    // 图片数据可能为空；空数据不算封面。
    val bytes = frame.copyOfRange(position, frame.size)
    return if (bytes.isEmpty()) null else ApicPicture(type, bytes)
}

/** 跳过以 0 结尾的字符串；UTF-16 的结束符是两个 0 字节，按 2 字节对齐查找。 */
private fun skipTerminated(frame: ByteArray, start: Int, utf16: Boolean): Int {
    var position = start
    if (utf16) {
        while (position + 1 < frame.size) {
            if (frame[position] == 0.toByte() && frame[position + 1] == 0.toByte()) return position + 2
            position += 2
        }
        return frame.size
    }
    while (position < frame.size && frame[position] != 0.toByte()) position++
    return if (position < frame.size) position + 1 else frame.size
}

/** 多图取舍：正面封面优先，其次「其他」，最后取第一张。 */
private fun choosePicture(pictures: List<ApicPicture>): ApicPicture? =
    pictures.firstOrNull { it.type == PICTURE_TYPE_FRONT_COVER }
        ?: pictures.firstOrNull { it.type == PICTURE_TYPE_OTHER }
        ?: pictures.firstOrNull()

/** unsynchronisation 的还原：返回还原后的新数组，原数组保持不变。 */
private fun removeUnsynchronisation(bytes: ByteArray): ByteArray {
    if (bytes.isEmpty()) return bytes
    val restored = bytes.copyOf()
    val length = removeUnsynchronisationInPlace(restored)
    return if (length == restored.size) restored else restored.copyOf(length)
}

/** 原地还原：读位置永远不落后于写位置，可以直接在同一个数组上压缩。返回还原后的长度。 */
private fun removeUnsynchronisationInPlace(bytes: ByteArray): Int {
    var written = 0
    var index = 0
    while (index < bytes.size) {
        val value = bytes[index]
        bytes[written++] = value
        if (value == 0xFF.toByte() && index + 1 < bytes.size && bytes[index + 1] == 0.toByte()) {
            index += 2
        } else {
            index++
        }
    }
    return written
}

private fun isId3Header(header: ByteArray): Boolean =
    header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()

private fun isFrameId(header: ByteArray, length: Int): Boolean =
    (0 until length).all { index ->
        val char = header[index].toInt() and 0xFF
        char in 0x41..0x5A || char in 0x30..0x39
    }

private fun pictureFrameId(major: Int): String = if (major == 2) "PIC" else "APIC"

/** syncsafe 长度：每字节只取低 7 位。 */
private fun syncSafe(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0x7F) shl 21) or
        ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
        ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
        (bytes[offset + 3].toInt() and 0x7F)

/** 普通大端整数，用于 v2.2 帧长、v2.3 帧长与扩展头长度。 */
private fun plainInt(bytes: ByteArray, offset: Int, length: Int): Long {
    var value = 0L
    repeat(length) { index -> value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF) }
    return value
}

private fun CoverByteSource.readFully(position: Long, length: Int): ByteArray? {
    if (position < 0 || length <= 0 || position + length > size) return null
    val bytes = ByteArray(length)
    var read = 0
    while (read < length) {
        val count = readAt(position + read, bytes, read, length - read)
        if (count <= 0) return null
        read += count
    }
    return bytes
}

/** 把字节数组包成字节源；原地还原后数组尾部留着旧字节，用显式长度截断。 */
private class ByteArrayCoverSource(
    private val bytes: ByteArray,
    override val size: Long = bytes.size.toLong(),
) : CoverByteSource {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= size) return 0
        val count = minOf(length, (size - position).toInt())
        if (count <= 0) return 0
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }
}

/** 只看标签段的一段字节源；位置相对标签体起点，越界不越过标签末尾。 */
private class SlicedCoverSource(
    private val source: CoverByteSource,
    private val start: Long,
    private val end: Long,
) : CoverByteSource {

    override val size: Long get() = end - start

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        source.readAt(start + position, buffer, offset, length)
}
