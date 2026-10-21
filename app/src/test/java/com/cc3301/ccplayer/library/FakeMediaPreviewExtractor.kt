package com.cc3301.ccplayer.library

import kotlinx.coroutines.delay

/** 假取图：返回预先设定的图与时长，并记录被请求过的位置。 */
class FakeMediaPreviewExtractor(
    var imageBytes: ByteArray? = null,
    var durationMillis: Long? = null,
    /** 抽图时先等这么久，用来模拟慢网络。 */
    var imageDelayMillis: Long = 0,
    /** 抽图时抛出的异常，用来模拟网络失败。 */
    var imageFailure: Exception? = null,
    /** 读时长时先等这么久，用来模拟并发读取。 */
    var durationDelayMillis: Long = 0,
) : MediaPreviewExtractor {

    val imageRequests = mutableListOf<String>()
    val durationRequests = mutableListOf<String>()

    /** 网络视频解码内部读取时长的次数；这类读取不对外返回时长。 */
    var internalDurationReads = 0

    override suspend fun imageBytes(file: MediaFile): ByteArray? {
        imageRequests += file.location
        if (file.source == MediaSource.Network && mediaKindOf(file.name) == MediaKind.Video) {
            internalDurationReads++
        }
        if (imageDelayMillis > 0) delay(imageDelayMillis)
        imageFailure?.let { throw it }
        return imageBytes
    }

    override suspend fun durationMillis(file: MediaFile): Long? {
        durationRequests += file.location
        if (durationDelayMillis > 0) delay(durationDelayMillis)
        return durationMillis
    }
}
