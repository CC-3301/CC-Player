package com.cc3301.ccplayer.library

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 命中的缓存条目：图片文件、记录的时长，以及时长是否已知。 */
data class CachedThumbnail(
    val file: File,
    val durationMillis: Long?,
    val durationKnown: Boolean,
)

/**
 * 缩略图磁盘缓存：条目放在 [directory] 下，总字节数超过 [maxBytes] 时先删最近最少使用的条目。
 * 命中时把文件的最后修改时间写成当前时间，作为「最近使用」的记录。
 *
 * 每个条目带一份元数据，第一行是写入时间，第二行是时长：
 * 缺失表示时长未知，`null` 表示确定没有时长，数字表示已知时长。写入时间用于有效期判断。
 */
class ThumbnailCache(
    private val directory: File,
    private val maxBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 同一目录的实例共用锁，避免多实例同时替换同名文件。 */
    private val locks = directoryLocks.computeIfAbsent(directory.absolutePath) { DirectoryLocks() }

    init {
        // 启动时清一次上次进程遗留的临时文件与孤儿元数据；保存过程中不做全目录清理。
        if (directory.isDirectory) {
            synchronized(locks.entry) {
                deleteTemporaryFiles()
                deleteOrphanMetadata()
            }
        }
    }

    /** 取出该缓存键对应的条目；没有缓存或已超过有效期时返回 null。 */
    fun entry(location: String, maxAgeMillis: Long? = null): CachedThumbnail? = synchronized(locks.entry) {
        val cached = File(directory, cacheFileName(location))
        if (!cached.isFile) return@synchronized null
        val metadata = metadataFile(location).takeIf { it.isFile }?.readMetadata()
        if (maxAgeMillis != null) {
            val writtenAt = metadata?.writtenAt ?: return@synchronized null
            if (clock() - writtenAt > maxAgeMillis) return@synchronized null
        }
        cached.setLastModified(clock())
        CachedThumbnail(
            file = cached,
            durationMillis = metadata?.durationMillis,
            durationKnown = metadata?.durationKnown == true,
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
        val metadata = metadataFile(location)
        val writtenAt = clock()
        synchronized(locks.entry) {
            var tempImage: File? = null
            var tempMetadata: File? = null
            try {
                tempImage = temporaryFile(target.name)
                tempMetadata = temporaryFile(metadata.name)
                tempImage.writeBytes(bytes)
                tempMetadata.writeText(encodeMetadata(writtenAt, durationMillis))
                // 先换图片再换元数据：元数据出现时图片已经存在，孤儿清理不会误删新元数据。
                replaceFile(tempImage, target)
                replaceFile(tempMetadata, metadata)
            } finally {
                // 任一步失败都清掉临时文件，崩溃遗留的临时文件由启动清理兜底。
                tempImage?.delete()
                tempMetadata?.delete()
            }
            target.setLastModified(writtenAt)
        }
        evictBeyondLimit(target)
        return target
    }

    /**
     * 缓存命中后补写时长；图片不存在时不写，避免留下孤儿元数据。
     * 第二次读取失败时不调用，保持“未知”以便下次重试。
     */
    suspend fun writeDuration(location: String, durationMillis: Long?) = withContext(Dispatchers.IO) {
        synchronized(locks.entry) {
            val image = File(directory, cacheFileName(location))
            if (!image.isFile) return@synchronized
            val metadata = metadataFile(location)
            val writtenAt = metadata.takeIf { it.isFile }?.readMetadata()?.writtenAt ?: clock()
            val temp = temporaryFile(metadata.name)
            try {
                temp.writeText(encodeMetadata(writtenAt, durationMillis))
                replaceFile(temp, metadata)
            } finally {
                temp.delete()
            }
        }
    }

    /** 扫描与删除放在条目锁之外，避免拖住缓存命中与抽取落盘。 */
    private fun evictBeyondLimit(keep: File) {
        synchronized(locks.eviction) {
            val entries = directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(IMAGE_SUFFIX) }
                .sortedBy { it.lastModified() }
            var totalBytes = entries.sumOf { it.length() }
            if (totalBytes > maxBytes) deleteOrphanMetadata()
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
    }

    /** 上次进程中断遗留的临时文件没有意义，启动时一并清掉。 */
    private fun deleteTemporaryFiles() {
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }
            .forEach { it.delete() }
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

    private fun temporaryFile(baseName: String): File =
        File.createTempFile("$baseName.", TEMP_SUFFIX, directory)

    private fun metadataFile(location: String): File =
        File(directory, cacheFileName(location) + METADATA_SUFFIX)
}

/** 同一目录的条目锁与淘汰锁；进程内多实例共用。 */
private class DirectoryLocks {
    val entry = Any()
    val eviction = Any()
}

private val directoryLocks = ConcurrentHashMap<String, DirectoryLocks>()

private data class Metadata(
    val writtenAt: Long,
    val durationMillis: Long?,
    val durationKnown: Boolean,
)

private fun File.readMetadata(): Metadata? {
    val lines = runCatching { readLines() }.getOrNull() ?: return null
    val writtenAt = lines.getOrNull(0)?.toLongOrNull() ?: return null
    val durationLine = lines.getOrNull(1)
    val durationMillis = durationLine?.toLongOrNull()
    return Metadata(
        writtenAt = writtenAt,
        durationMillis = durationMillis,
        // 旧格式没有第二行，或第二行无法解析时，时长保持未知，命中后会重新读取。
        durationKnown = durationMillis != null || durationLine == DURATION_NONE,
    )
}

private fun encodeMetadata(writtenAt: Long, durationMillis: Long?): String =
    "$writtenAt\n${durationMillis?.toString() ?: DURATION_NONE}"

/** 先尝试原子改名；失败时复制到同目录唯一临时名再改名，避免复制中断留下半张图。 */
private fun replaceFile(source: File, target: File) {
    if (source.renameTo(target)) return
    val staged = File.createTempFile("${target.name}.", TEMP_SUFFIX, target.parentFile)
    try {
        source.copyTo(staged, overwrite = true)
        if (!staged.renameTo(target)) {
            throw IOException("无法替换缩略图缓存文件：${target.name}")
        }
        source.delete()
    } finally {
        staged.delete()
    }
}

/** 缓存键到文件名的映射：用 SHA-256 避免不同缓存键撞到同一个缓存文件。 */
private fun cacheFileName(location: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(location.toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) } + IMAGE_SUFFIX
}

private const val IMAGE_SUFFIX = ".img"
private const val METADATA_SUFFIX = ".meta"
private const val TEMP_SUFFIX = ".tmp"
private const val DURATION_NONE = "null"
