package com.cc3301.ccplayer.library

/**
 * 假文件树：用「位置 → 直接子项」的映射代替 SAF，供 [DirectoryMediaLibrary] 的测试注入。
 */
class FakeMediaFileTree : MediaFileTree {

    private val directories = mutableMapOf<String, MediaFile>()
    private val childrenByLocation = mutableMapOf<String, List<MediaFile>>()
    private val unreadableLocations = mutableSetOf<String>()
    private val failuresByLocation = mutableMapOf<String, Exception>()

    /** 登记一个目录及其直接子项。 */
    fun addDirectory(directory: MediaFile, vararg children: MediaFile): FakeMediaFileTree {
        directories[directory.location] = directory
        childrenByLocation[directory.location] = children.toList()
        return this
    }

    /** 让该位置的读取抛异常，模拟授权失效或读取失败。 */
    fun markUnreadable(location: String): FakeMediaFileTree {
        unreadableLocations += location
        return this
    }

    /** 让该位置的读取抛指定异常，模拟网络源读取失败。 */
    fun markFailing(location: String, failure: Exception): FakeMediaFileTree {
        failuresByLocation[location] = failure
        return this
    }

    override suspend fun resolve(location: String): MediaFile? = directories[location]

    override suspend fun children(directory: MediaFile): List<MediaFile> {
        failuresByLocation[directory.location]?.let { throw it }
        if (directory.location in unreadableLocations) {
            throw SecurityException("无读取授权：${directory.location}")
        }
        return childrenByLocation[directory.location].orEmpty()
    }
}

fun testDirectory(name: String, location: String): MediaFile =
    MediaFile(name = name, location = location, isDirectory = true)

fun testVideo(name: String, location: String): MediaFile =
    MediaFile(name = name, location = location, size = 3_000_000, lastModified = 1_700_000_000_000)

fun testAudio(name: String, location: String): MediaFile =
    MediaFile(name = name, location = location, size = 4_000_000, lastModified = 1_700_000_000_000)

fun testDocument(name: String, location: String): MediaFile =
    MediaFile(name = name, location = location, size = 512, lastModified = 1_700_000_000_000)
