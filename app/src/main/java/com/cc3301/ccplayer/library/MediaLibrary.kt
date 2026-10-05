package com.cc3301.ccplayer.library

/**
 * 媒体库接缝：列表界面只依赖本接口，文件树由实现注入。
 */
interface MediaLibrary {

    /** 把持久化的目录位置解析成目录条目；位置不可读时返回 null。 */
    suspend fun resolveDirectory(location: String): MediaFile?

    /** 列出目录下可播放的媒体文件与子目录，不递归；读取失败时返回失败。 */
    suspend fun list(directory: MediaFile): Result<List<MediaFile>>
}
