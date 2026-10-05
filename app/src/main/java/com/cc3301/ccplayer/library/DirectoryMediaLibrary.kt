package com.cc3301.ccplayer.library

import kotlin.coroutines.cancellation.CancellationException

/**
 * 「目录即列表」：当前目录的直接子项就是列表内容，子目录折叠成一项。
 */
class DirectoryMediaLibrary(private val tree: MediaFileTree) : MediaLibrary {

    override suspend fun resolveDirectory(location: String): MediaFile? = tree.resolve(location)

    override suspend fun list(directory: MediaFile): Result<List<MediaFile>> = try {
        Result.success(tree.children(directory).filter { it.isDirectory || mediaKindOf(it.name) != null })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
