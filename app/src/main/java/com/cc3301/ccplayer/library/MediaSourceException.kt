package com.cc3301.ccplayer.library

import java.io.IOException

/** 媒体源读取失败的原因；界面据此选提示文案。 */
enum class MediaFailure {
    /** 连不上服务器。 */
    Unreachable,

    /** 服务器拒绝登录。 */
    Authentication,

    /** 源上找不到这个位置。 */
    NotFound,

    /** 其它读取失败。 */
    Unknown,
}

/** 媒体源读取失败，带可判定的原因与原始异常。 */
class MediaSourceException(
    val failure: MediaFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
