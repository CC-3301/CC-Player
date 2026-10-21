package com.cc3301.ccplayer.library

import java.io.File
import java.security.MessageDigest

/** 命中的缓存条目：图片文件、写入时记录的时长，以及时长是否已知。 */
data class CachedThumbnail(
    val file: File,
    val durationMillis: Long?,
    val durationKnown: Boolean,
)

/**
 * 缩略图磁盘缓存：条目放在 [directory] 下，总字节数超过 [maxBytes] 时先删最近最少使用的条目。
 * 命中时把文件的最后修改时间写成当前时间，作为「最近使用」的记录。
 *
 * 每个条目带一份元数据，记录写入时间与时长；写入时间用于有效期判断。
 */
class ThumbnailCache(
    private val directory: File,
    private val maxBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 取出该缓存键对应的条目；没有缓存或已超过有效期时返回 null。 */
    fun entry(location: String, maxAgeMillis: Long? = null): CachedThumbnail? {
        val cached = File(directory, cacheFileName(location))
        if (!cached.isFile) return null
        val metadata = metadataFile(location).takeIf { it.isFile }?.readMetadata()
        if (maxAgeMillis != null) {
            val writtenAt = metadata?.writtenAt ?: return null
            if (clock() - writtenAt > maxAgeMillis) return null
        }
        cached.setLastModified(clock())
        return CachedThumbnail(
            file = cached,
            durationMillis = metadata?.durationMillis,
            durationKnown = metadata != null,
        )
    }

    /** 取出该缓存键对应的缓存文件；没有缓存或已超过有效期时返回 null。 */
    fun file(location: String, maxAgeMillis: Long? = null): File? =
        entry(location, maxAgeMillis)?.file

    /** 写入该缓存键对应的缓存文件与元数据，并清掉超限的旧条目。 */
    fun save(
        location: String,
        bytes: ByteArray,
        durationMillis: Long? = null,
    ): File {
        directory.mkdirs()
        val target = File(directory, cacheFileName(location))
        // 先写元数据再写图片：图片存在时元数据已完整，避免读到半份元数据。
        metadataFile(location).writeText("${clock()}\n${durationMillis ?: ""}")
        target.writeBytes(bytes)
        target.setLastModified(clock())
        evictBeyondLimit(keep = target)
        return target
    }

    private fun evictBeyondLimit(keep: File) {
        deleteOrphanMetadata()
        val entries = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(IMAGE_SUFFIX) }
            .sortedBy { it.lastModified() }
        var totalBytes = entries.sumOf { it.length() }
        for (entry in entries) {
            if (totalBytes <= maxBytes) break
            if (entry == keep) continue
            val length = entry.length()
            if (entry.delete()) {
                File(directory, entry.name + METADATA_SUFFIX).delete()
                totalBytes -= length
            }
        }
    }

    /** 图片被外部删除后留下的元数据没有意义，一并清掉。 */
    private fun deleteOrphanMetadata() {
        val images = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(IMAGE_SUFFIX) }
            .mapTo(HashSet()) { it.name }
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(METADATA_SUFFIX) }
            .filter { metadata -> metadata.name.removeSuffix(METADATA_SUFFIX) !in images }
            .forEach { metadata -> metadata.delete() }
    }

    private fun metadataFile(location: String): File =
        File(directory, cacheFileName(location) + METADATA_SUFFIX)
}

private data class Metadata(val writtenAt: Long, val durationMillis: Long?)

private fun File.readMetadata(): Metadata? {
    val lines = runCatching { readLines() }.getOrNull() ?: return null
    val writtenAt = lines.getOrNull(0)?.toLongOrNull() ?: return null
    return Metadata(writtenAt, lines.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull())
}

/** 缓存键到文件名的映射：用 SHA-256 避免不同缓存键撞到同一个缓存文件。 */
private fun cacheFileName(location: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(location.toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) } + IMAGE_SUFFIX
}

private const val IMAGE_SUFFIX = ".img"
private const val METADATA_SUFFIX = ".meta"
