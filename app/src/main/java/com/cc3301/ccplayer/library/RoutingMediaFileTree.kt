package com.cc3301.ccplayer.library

/**
 * 位置按方案分派给各自的文件树：本地 SAF 走 `content`，网络源走各自的协议方案。
 */
class RoutingMediaFileTree(private val treesByScheme: Map<String, MediaFileTree>) : MediaFileTree {

    override suspend fun resolve(location: String): MediaFile? = treeFor(location)?.resolve(location)

    override suspend fun children(directory: MediaFile): List<MediaFile> {
        val tree = treeFor(directory.location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有能处理这个位置的文件树：${directory.location}")
        return tree.children(directory)
    }

    private fun treeFor(location: String): MediaFileTree? =
        treesByScheme[location.substringBefore(SCHEME_SEPARATOR, missingDelimiterValue = "")]

    companion object {
        /** 本地 SAF 位置的方案名。 */
        const val SAF_SCHEME = "content"

        private const val SCHEME_SEPARATOR = "://"
    }
}
