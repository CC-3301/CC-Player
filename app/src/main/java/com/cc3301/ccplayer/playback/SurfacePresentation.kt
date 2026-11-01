package com.cc3301.ccplayer.playback

/**
 * 屏幕上现在该显示什么：是否已有可显示的画面，以及视频区该用哪个宽高比。
 *
 * [showsPicture] 为假时界面铺黑底；[aspectRatio] 是当前该用的比例，
 * 切集期间沿用原片比例，目标画面接管后换成新文件比例。
 */
data class SurfacePresentation(
    val showsPicture: Boolean = false,
    val aspectRatio: Double? = null,
)
