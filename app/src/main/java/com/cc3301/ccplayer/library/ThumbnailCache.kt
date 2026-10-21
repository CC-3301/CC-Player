package com.cc3301.ccplayer.library

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 命中的缓存条目：图片文件（无图条目为 null）、记录的时长，以及时长是否已知。 */
data class CachedThumbnail(
    val file: File?,
    val durationMillis: Long?,
    val durationKnown: Boolean,
)

/**
 * 缩略图磁盘缓存：条目放在 [directory] 下，总字节数超过 [maxBytes] 时先删最近最少使用的条目。
 * 命中时把文件的最后修改时间写成当前时间，作为「最近使用」的记录。
 *
 * 每个条目带一份元数据，第一行是写入时间，第二行是时长：
 * `unknown` 或缺失表示时长未知，`null` 表示确定没有时长，数字表示已知时长；第三行标记无图条目。
 * 写入时间用于有效期判断。磁盘读写与启动清理都在 IO 调度执行。
 */
class ThumbnailCache(
    private val directory: File,
    private val maxBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 同一目录的实例共用锁，避免多实例同时替换同名文件。 */
    private val locks = directoryLocks.computeIfAbsent(directory.absolutePath) { DirectoryLocks() }

    /** 启动清理只做一次；首次访问时在 IO 调度执行，不在构造时扫目录。 */
    @Volatile
    private var cleaned = false

    /** 取出该缓存键对应的条目；没有缓存或已超过有效期时返回 null。 */
    suspend fun entry(location: String, maxAgeMillis: Long? = null): CachedThumbnail? =
        withContext(ioDispatcher) {
            ensureCleanup()
            synchronized(locks.entry) { readEntry(location, maxAgeMillis) }
        }

    /** 取出该缓存键对应的缓存文件；没有缓存或已超过有效期时返回 null。 */
    suspend fun file(location: String, maxAgeMillis: Long? = null): File? =
        entry(location, maxAgeMillis)?.file

    /** 写入该缓存键对应的缓存文件与元数据，并清掉超限的旧条目。 */
    suspend fun save(
        location: String,
        bytes: ByteArray,
        durationMillis: Long? = null,
        durationKnown: Boolean = true,
    ): File = withContext(ioDispatcher) {
        ensureCleanup()
        writeImageEntry(location, bytes, durationMillis, durationKnown)
    }

    /** 没有可用预览图时只记录时长，避免每次重开文件重读。 */
    suspend fun saveWithoutImage(
        location: String,
        durationMillis: Long? = null,
        durationKnown: Boolean = true,
    ) = withContext(ioDispatcher) {
        ensureCleanup()
        writeMetadataOnly(location, durationMillis, durationKnown)
    }

    /**
     * 缓存命中后补写时长；条目不存在时不写，避免留下孤儿元数据。
     * 元数据缺失时沿用图片的修改时间，不把有效期窗口向后推。
     */
    suspend fun writeDuration(location: String, durationMillis: Long?) = withContext(ioDispatcher) {
        ensureCleanup()
        synchronized(locks.entry) {
            val image = File(directory, cacheFileName(location))
            val metadata = metadataFile(location)
            val existing = metadata.takeIf { it.isFile }?.readMetadata()
            val noImage = !image.isFile
            if (noImage && existing?.noImage != true) return@synchronized
            val writtenAt = existing?.writtenAt
                ?: image.lastModified().takeIf { it > 0 }
                ?: clock()
            writeMetadata(metadata, writtenAt, durationMillis, durationKnown = true, noImage)
        }
    }

    private fun readEntry(location: String, maxAgeMillis: Long?): CachedThumbnail? {
        val cached = File(directory, cacheFileName(location))
        val metadata = metadataFile(location).takeIf { it.isFile }?.readMetadata()
        val hasImage = cached.isFile
        if (!hasImage && metadata?.noImage != true) return null
        if (maxAgeMillis != null) {
            val writtenAt = metadata?.writtenAt ?: return null
            if (clock() - writtenAt > maxAgeMillis) return null
        }
        if (hasImage) cached.setLastModified(clock())
        return CachedThumbnail(
            file = if (hasImage) cached else null,
            durationMillis = metadata?.durationMillis,
            durationKnown = metadata?.durationKnown == true,
        )
    }

    private fun writeImageEntry(
        location: String,
        bytes: ByteArray,
        durationMillis: Long?,
        durationKnown: Boolean,
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
                tempMetadata.writeText(
                    encodeMetadata(writtenAt, durationMillis, durationKnown, noImage = false),
                )
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
        evictBeyondLimit(keep = target)
        return target
    }

    private fun writeMetadataOnly(
        location: String,
        durationMillis: Long?,
        durationKnown: Boolean,
    ) {
        directory.mkdirs()
        val metadata = metadataFile(location)
        val image = File(directory, cacheFileName(location))
        val writtenAt = clock()
        synchronized(locks.entry) {
            image.delete()
            writeMetadata(metadata, writtenAt, durationMillis, durationKnown, noImage = true)
        }
        evictBeyondLimit(keep = null)
    }

    private fun writeMetadata(
        metadata: File,
        writtenAt: Long,
        durationMillis: Long?,
        durationKnown: Boolean,
        noImage: Boolean,
    ) {
        val temp = temporaryFile(metadata.name)
        try {
            temp.writeText(encodeMetadata(writtenAt, durationMillis, durationKnown, noImage))
            replaceFile(temp, metadata)
        } finally {
            temp.delete()
        }
    }

    private fun ensureCleanup() {
        if (cleaned) return
        synchronized(locks.entry) {
            if (cleaned) return
            if (directory.isDirectory) {
                deleteTemporaryFiles()
                deleteOrphanMetadata()
            }
            cleaned = true
        }
    }

    /** 扫描与删除放在条目锁之外，避免拖住缓存命中与抽取落盘。 */
    private fun evictBeyondLimit(keep: File?) {
        synchronized(locks.eviction) {
            val scanStartedAt = clock()
            val entries = directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(IMAGE_SUFFIX) }
                .sortedBy { it.lastModified() }
            var totalBytes = entries.sumOf { it.length() }
            if (totalBytes > maxBytes) deleteOrphanMetadata()
            for (entry in entries) {
                if (totalBytes <= maxBytes) break
                if (entry == keep) continue
                // 与本次扫描同时写入的条目留到下次淘汰，避免删掉正在落盘的预览。
                if (entry.lastModified() >= scanStartedAt) continue
                val length = entry.length()
                val deleted = synchronized(locks.entry) {
                    // 删除时持条目锁，避免删掉另一写入者刚换好图片、尚未换元数据的条目。
                    if (!entry.isFile || !entry.delete()) {
                        false
                    } else {
                        File(directory, entry.name + METADATA_SUFFIX).delete()
                        true
                    }
                }
                if (deleted) totalBytes -= length
            }
        }
    }

    /** 上次进程中断遗留的临时文件没有意义，启动时一并清掉。 */
    private fun deleteTemporaryFiles() {
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }
            .forEach { it.delete() }
    }

    /** 图片被外部删除后留下的元数据没有意义，一并清掉；无图条目按标记保留。 */
    private fun deleteOrphanMetadata() {
        synchronized(locks.entry) {
            val images = directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(IMAGE_SUFFIX) }
                .mapTo(HashSet()) { it.name }
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(METADATA_SUFFIX) }
                .filter { metadata ->
                    metadata.name.removeSuffix(METADATA_SUFFIX) !in images &&
                        metadata.readMetadata()?.noImage != true
                }
                .forEach { metadata -> metadata.delete() }
        }
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
    val noImage: Boolean,
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
        noImage = lines.getOrNull(2) == NO_IMAGE_MARKER,
    )
}

private fun encodeMetadata(
    writtenAt: Long,
    durationMillis: Long?,
    durationKnown: Boolean,
    noImage: Boolean,
): String {
    val durationLine = when {
        !durationKnown -> DURATION_UNKNOWN
        durationMillis != null -> durationMillis.toString()
        else -> DURATION_NONE
    }
    val lines = mutableListOf(writtenAt.toString(), durationLine)
    if (noImage) lines += NO_IMAGE_MARKER
    return lines.joinToString("\n")
}

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
private const val DURATION_UNKNOWN = "unknown"
private const val NO_IMAGE_MARKER = "no-image"
