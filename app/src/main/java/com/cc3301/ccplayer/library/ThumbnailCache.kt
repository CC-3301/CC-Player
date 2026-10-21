package com.cc3301.ccplayer.library

import java.io.File
import java.security.MessageDigest

/**
 * 缩略图磁盘缓存：条目放在 [directory] 下，总字节数超过 [maxBytes] 时先删最近最少使用的条目。
 * 命中时把文件的最后修改时间写成当前时间，作为「最近使用」的记录。
 *
 * 带有效期的条目另存写入时间，命中时按写入时间判断是否过期。
 */
class ThumbnailCache(
    private val directory: File,
    private val maxBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 取出该缓存键对应的缓存文件；没有缓存或已超过有效期时返回 null。 */
    fun file(location: String, maxAgeMillis: Long? = null): File? {
        val cached = File(directory, cacheFileName(location))
        if (!cached.isFile) return null
        if (maxAgeMillis != null) {
            val writtenAt = metadataFile(location).takeIf { it.isFile }
                ?.readText()
                ?.toLongOrNull()
                ?: return null
            if (clock() - writtenAt > maxAgeMillis) return null
        }
        cached.setLastModified(clock())
        return cached
    }

    /** 写入该缓存键对应的缓存文件，并清掉超限的旧条目。 */
    fun save(location: String, bytes: ByteArray, maxAgeMillis: Long? = null): File {
        directory.mkdirs()
        val target = File(directory, cacheFileName(location))
        target.writeBytes(bytes)
        target.setLastModified(clock())
        val metadata = metadataFile(location)
        if (maxAgeMillis != null) {
            metadata.writeText(clock().toString())
        } else {
            metadata.delete()
        }
        evictBeyondLimit(keep = target)
        return target
    }

    private fun evictBeyondLimit(keep: File) {
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

    private fun metadataFile(location: String): File =
        File(directory, cacheFileName(location) + METADATA_SUFFIX)
}

/** 缓存键到文件名的映射：用 SHA-256 避免不同缓存键撞到同一个缓存文件。 */
private fun cacheFileName(location: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(location.toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) } + IMAGE_SUFFIX
}

private const val IMAGE_SUFFIX = ".img"
private const val METADATA_SUFFIX = ".meta"
