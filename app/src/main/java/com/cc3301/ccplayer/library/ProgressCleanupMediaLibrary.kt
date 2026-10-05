package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.progress.PlaybackProgressRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * 列举目录后清掉已不存在的媒体的进度记录：记录只留给还在的媒体。
 *
 * 清理失败只影响记录，列表结果照常返回。
 */
class ProgressCleanupMediaLibrary(
    private val library: MediaLibrary,
    private val progress: PlaybackProgressRepository,
) : MediaLibrary {

    override suspend fun resolveDirectory(location: String): MediaFile? = library.resolveDirectory(location)

    override suspend fun list(directory: MediaFile): Result<List<MediaFile>> =
        library.list(directory).onSuccess { entries -> removeMissingUnder(directory, entries) }

    private suspend fun removeMissingUnder(directory: MediaFile, entries: List<MediaFile>) {
        try {
            progress.removeMissingUnder(directory.location, entries.map { it.location }.toSet())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Unit
        }
    }
}
