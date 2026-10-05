package com.cc3301.ccplayer.library

/**
 * 目录访问接缝：一个来源（本地 SAF、之后的 SMB/WebDAV）各自实现，
 * [DirectoryMediaLibrary] 只在其上做列举规则。
 */
interface MediaFileTree {

    /** 读取某个位置自身的条目信息；位置不可解析或授权失效时返回 null。 */
    suspend fun resolve(location: String): MediaFile?

    /** 列出目录的直接子项；目录不可读时抛出异常。 */
    suspend fun children(directory: MediaFile): List<MediaFile>
}
