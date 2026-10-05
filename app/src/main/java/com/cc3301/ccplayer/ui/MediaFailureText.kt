package com.cc3301.ccplayer.ui

import androidx.annotation.StringRes
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.library.MediaFailure

/** 来源失败原因对应的提示文案；界面与播放起播失败共用。 */
@StringRes
fun mediaFailureText(failure: MediaFailure): Int = when (failure) {
    MediaFailure.Unreachable -> R.string.library_network_unreachable
    MediaFailure.Authentication -> R.string.library_network_authentication
    MediaFailure.NotFound -> R.string.library_network_not_found
    MediaFailure.Unknown -> R.string.library_load_failed
}
