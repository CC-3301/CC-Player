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
    /** 读时长时抛出的异常，用来模拟元数据读取失败。 */
    var durationFailure: Exception? = null,
) : MediaPreviewExtractor {

    val imageRequests = mutableListOf<String>()
    val imageWidths = mutableListOf<Int>()
    val durationRequests = mutableListOf<String>()

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        imageRequests += file.location
        imageWidths += targetWidth
        if (imageDelayMillis > 0) delay(imageDelayMillis)
        imageFailure?.let { throw it }
        return imageBytes
    }

    /** 测试用准备：解码时才调用 [imageBytes]，不新开真实句柄。 */
    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation =
        NetworkPreviewPreparation(
            decodeBlock = { targetWidth -> imageBytes(file, targetWidth) },
            closeBlock = {},
        )

    override suspend fun durationMillis(file: MediaFile): Long? {
        durationRequests += file.location
        if (durationDelayMillis > 0) delay(durationDelayMillis)
        durationFailure?.let { throw it }
        return durationMillis
    }
}
