package com.cc3301.ccplayer.library

/** 假取图：返回预先设定的图与时长，并记录被请求过的位置。 */
class FakeMediaPreviewExtractor(
    var imageBytes: ByteArray? = null,
    var durationMillis: Long? = null,
) : MediaPreviewExtractor {

    val imageRequests = mutableListOf<String>()
    val durationRequests = mutableListOf<String>()

    override suspend fun imageBytes(file: MediaFile): ByteArray? {
        imageRequests += file.location
        return imageBytes
    }

    override suspend fun durationMillis(file: MediaFile): Long? {
        durationRequests += file.location
        return durationMillis
    }
}
