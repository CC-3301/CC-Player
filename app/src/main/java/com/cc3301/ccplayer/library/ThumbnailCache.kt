package com.cc3301.ccplayer.library

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 落盘的封面失败记录：失败时间与已试次数。重启后据此按退避时间跳过。 */
data class CachedFailure(val failedAtMillis: Long, val attempts: Int)

/** 命中的缓存条目：图片文件（无图与失败条目为 null）、记录的时长、时长是否已知、是否标记为无图。 */
data class CachedThumbnail(
    val file: File?,
    val durationMillis: Long?,
    val durationKnown: Boolean,
    /** 元数据来自上一次运行的无图记录；本次运行仍需重新确认是否有图。 */
    val noImage: Boolean = false,
    /** 元数据来自上一次运行的失败记录；[file] 为 null，重启后按退避时间跳过。 */
    val failure: CachedFailure? = null,
)

/**
 * 缩略图磁盘缓存：条目放在 [directory] 下，总字节数超过 [maxBytes] 时先删最近最少使用的条目。
 * 命中时把文件的最后修改时间写成当前时间，作为「最近使用」的记录。
 *
 * 每个条目带一份元数据，记时长、是否无图与失败记录（失败时间与已试次数），格式为若干行 `键=值`：
 * `duration` 为 `unknown`（时长未知）、`null`（确定没有时长）或毫秒数；
 * `no-image` 标记无图条目；`failed-at` 与 `failed-attempts` 成对出现，标记抽帧失败的条目。
 * 无图与失败记录都只占元数据、不计入字节上限，按条数一起淘汰。
 * 缓存不设有效期：命中的条目一直有效，条目只能被容量上限淘汰或按缓存键失配。
 * 磁盘读写与启动清理都在 IO 调度执行。
 */
class ThumbnailCache(
    private val directory: File,
    private val maxBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxMetadataEntries: Int = MAX_METADATA_ENTRIES,
) {

    /** 同一目录的实例共用锁，避免多实例同时替换同名文件。 */
    private val locks = directoryLocks.computeIfAbsent(directory.absolutePath) { DirectoryLocks() }

    /** 启动清理只做一次；首次访问时在 IO 调度执行，不在构造时扫目录。 */
    @Volatile
    private var cleaned = false

    /** 取出该缓存键对应的条目；没有缓存时返回 null。 */
    suspend fun entry(location: String): CachedThumbnail? = withContext(ioDispatcher) {
        ensureCleanup()
        synchronized(locks.entry) { readEntry(location) }
    }

    /** 取出该缓存键对应的缓存文件；没有缓存或条目只记了元数据时返回 null。 */
    suspend fun file(location: String): File? = entry(location)?.file

    /** 写入该缓存键对应的缓存文件与元数据，并清掉超限的旧条目。成功结果顺带清掉失败记录。 */
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

    /** 记一条抽帧失败：失败时间与已试次数，重启后据此按退避时间跳过。 */
    suspend fun saveFailure(location: String, failedAtMillis: Long, attempts: Int) = withContext(ioDispatcher) {
        ensureCleanup()
        writeFailure(location, failedAtMillis, attempts)
    }

    /**
     * 缓存命中后补写时长；条目不存在时不写，避免留下孤儿元数据。
     * 元数据文件的修改时间是它的最近使用记录，补写时长不能把它刷新成刚用过。
     */
    suspend fun writeDuration(location: String, durationMillis: Long?) = withContext(ioDispatcher) {
        ensureCleanup()
        synchronized(locks.entry) {
            val image = File(directory, cacheFileName(location))
            val metadata = metadataFile(location)
            val existing = metadata.takeIf { it.isFile }?.readMetadata()
            val noImage = !image.isFile
            if (noImage && existing?.noImage != true) return@synchronized
            val lastModified = metadata.lastModified().takeIf { it > 0 } ?: clock()
            writeMetadata(
                metadata = metadata,
                durationMillis = durationMillis,
                durationKnown = true,
                noImage = noImage,
                failure = null,
                lastModifiedMillis = lastModified,
            )
        }
    }

    private fun readEntry(location: String): CachedThumbnail? {
        val cached = File(directory, cacheFileName(location))
        val metadataFile = metadataFile(location).takeIf { it.isFile }
        val metadata = metadataFile?.readMetadata()
        val hasImage = cached.isFile
        if (!hasImage && metadata?.noImage != true && metadata?.failure == null) return null
        if (hasImage) cached.setLastModified(clock()) else metadataFile?.setLastModified(clock())
        return CachedThumbnail(
            file = if (hasImage) cached else null,
            durationMillis = metadata?.durationMillis,
            durationKnown = metadata?.durationKnown == true,
            noImage = !hasImage && metadata?.noImage == true,
            failure = if (hasImage) null else metadata?.failure,
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
        synchronized(locks.entry) {
            var tempImage: File? = null
            var tempMetadata: File? = null
            try {
                tempImage = temporaryFile(target.name)
                tempMetadata = temporaryFile(metadata.name)
                tempImage.writeBytes(bytes)
                tempMetadata.writeText(
                    encodeMetadata(durationMillis, durationKnown, noImage = false, failure = null),
                )
                // 先换图片再换元数据：元数据出现时图片已经存在，孤儿清理不会误删新元数据。
                replaceFile(tempImage, target)
                replaceFile(tempMetadata, metadata)
            } finally {
                // 任一步失败都清掉临时文件，崩溃遗留的临时文件由启动清理兜底。
                tempImage?.delete()
                tempMetadata?.delete()
            }
            val writtenAt = clock()
            target.setLastModified(writtenAt)
            metadata.setLastModified(writtenAt)
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
        synchronized(locks.entry) {
            val writtenAt = clock()
            image.delete()
            writeMetadata(
                metadata = metadata,
                durationMillis = durationMillis,
                durationKnown = durationKnown,
                noImage = true,
                failure = null,
                lastModifiedMillis = writtenAt,
            )
        }
        evictBeyondLimit(keep = null)
        // 只有新写入元数据条目时才需要按条数清理，普通图片保存不扫元数据。
        evictMetadataEntries()
    }

    private fun writeFailure(location: String, failedAtMillis: Long, attempts: Int) {
        directory.mkdirs()
        val metadata = metadataFile(location)
        synchronized(locks.entry) {
            val writtenAt = clock()
            // 失败不写「无图」，只记元数据；已缓存的图片原样保留。
            writeMetadata(
                metadata = metadata,
                durationMillis = null,
                durationKnown = false,
                noImage = false,
                failure = CachedFailure(failedAtMillis = failedAtMillis, attempts = attempts),
                lastModifiedMillis = writtenAt,
            )
        }
        evictMetadataEntries()
    }

    private fun writeMetadata(
        metadata: File,
        durationMillis: Long?,
        durationKnown: Boolean,
        noImage: Boolean,
        failure: CachedFailure?,
        lastModifiedMillis: Long,
    ) {
        val temp = temporaryFile(metadata.name)
        try {
            temp.writeText(encodeMetadata(durationMillis, durationKnown, noImage, failure))
            replaceFile(temp, metadata)
            metadata.setLastModified(lastModifiedMillis)
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
                val length = entry.length()
                val deleted = synchronized(locks.entry) {
                    // 删除前重读修改时间：期间被命中或重写的条目留到下次淘汰。
                    if (entry.lastModified() >= scanStartedAt) {
                        false
                    } else if (!entry.isFile || !entry.delete()) {
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

    /** 无图条目与失败记录只占元数据，不计入字节上限，按条数一起淘汰。 */
    private fun evictMetadataEntries() {
        synchronized(locks.entry) {
            val entries = directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(METADATA_SUFFIX) }
                .filter { metadata -> !File(directory, metadata.name.removeSuffix(METADATA_SUFFIX)).isFile }
            if (entries.size <= maxMetadataEntries) return@synchronized
            var count = entries.size
            for (entry in entries.sortedBy { it.lastModified() }) {
                if (count <= maxMetadataEntries) break
                if (entry.delete()) count--
            }
        }
    }

    /** 上次进程中断遗留的临时文件没有意义，启动时一并清掉。 */
    private fun deleteTemporaryFiles() {
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }
            .forEach { it.delete() }
    }

    /** 图片被外部删除后留下的元数据没有意义，一并清掉；无图与失败记录按各自的标记保留。 */
    private fun deleteOrphanMetadata() {
        synchronized(locks.entry) {
            val images = directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(IMAGE_SUFFIX) }
                .mapTo(HashSet()) { it.name }
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(METADATA_SUFFIX) }
                .filter { metadata ->
                    if (metadata.name.removeSuffix(METADATA_SUFFIX) in images) return@filter false
                    val recorded = metadata.readMetadata()
                    recorded?.noImage != true && recorded?.failure == null
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
    val durationMillis: Long?,
    val durationKnown: Boolean,
    val noImage: Boolean,
    val failure: CachedFailure?,
)

private fun File.readMetadata(): Metadata? {
    val lines = runCatching { readLines() }.getOrNull() ?: return null
    val values = lines.mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
    }.toMap()
    val durationLine = values[META_DURATION] ?: return null
    val durationMillis = durationLine.toLongOrNull()
    val failedAt = values[META_FAILED_AT]?.toLongOrNull()
    val attempts = values[META_FAILED_ATTEMPTS]?.toIntOrNull()
    return Metadata(
        durationMillis = durationMillis,
        // 时长行缺失或无法解析时按未知处理，命中后重新读取。
        durationKnown = durationMillis != null || durationLine == DURATION_NONE,
        noImage = values[META_NO_IMAGE] == TRUE_VALUE,
        failure = if (failedAt != null && attempts != null) {
            CachedFailure(failedAtMillis = failedAt, attempts = attempts)
        } else {
            null
        },
    )
}

private fun encodeMetadata(
    durationMillis: Long?,
    durationKnown: Boolean,
    noImage: Boolean,
    failure: CachedFailure?,
): String {
    val durationLine = when {
        !durationKnown -> DURATION_UNKNOWN
        durationMillis != null -> durationMillis.toString()
        else -> DURATION_NONE
    }
    val lines = mutableListOf(META_DURATION + "=" + durationLine)
    if (noImage) lines += META_NO_IMAGE + "=" + TRUE_VALUE
    if (failure != null) {
        lines += META_FAILED_AT + "=" + failure.failedAtMillis
        lines += META_FAILED_ATTEMPTS + "=" + failure.attempts
    }
    return lines.joinToString("\n")
}

/** 同目录改名替换：目标已存在时直接覆盖；源文件与目标同在一个缓存目录，不做跨卷复制。 */
private fun replaceFile(source: File, target: File) {
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

/** 缓存键到文件名的映射：用 SHA-256 避免不同缓存键撞到同一个缓存文件。 */
private fun cacheFileName(location: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(location.toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) } + IMAGE_SUFFIX
}

private const val IMAGE_SUFFIX = ".img"
private const val METADATA_SUFFIX = ".meta"
private const val TEMP_SUFFIX = ".tmp"
private const val META_DURATION = "duration"
private const val META_NO_IMAGE = "no-image"
private const val META_FAILED_AT = "failed-at"
private const val META_FAILED_ATTEMPTS = "failed-attempts"
private const val TRUE_VALUE = "true"
private const val DURATION_NONE = "null"
private const val DURATION_UNKNOWN = "unknown"

/** 只占元数据的条目（无图与失败记录）的数量上限；它们不计字节，需要单独控制增长。 */
private const val MAX_METADATA_ENTRIES = 2_048
