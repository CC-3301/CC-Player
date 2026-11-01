package com.cc3301.ccplayer.playback

import android.graphics.Bitmap

/** 屏幕上停着的是哪一帧；画面事实只有这一个枚举。 */
enum class ScreenFrame {
    /** 屏幕上没有可沿用的画面：首次打开还没出帧、Surface 重建、片尾黑底。 */
    None,

    /** 切集前那次播放的画面；目标画面接管前一直留在屏上。 */
    Origin,

    /** 当前加载自己的画面，已确认接管视频输出。 */
    Target,
}

/**
 * 屏幕上现在该显示什么：界面只读这个派生结果，不再自行组合加载阶段、黑底与视频轨。
 *
 * [showsPicture] 为假时铺黑底；[aspectRatio] 是视频区与朝向解析该用的比例；
 * [heldFrame] 是回前台先盖在画面上的缓存帧，与 [showsPicture] 无关。
 */
data class SurfacePresentation(
    val showsPicture: Boolean = false,
    val aspectRatio: Double? = null,
    val heldFrame: Bitmap? = null,
)

/**
 * 唯一的派生规则。
 *
 * 有画面：屏上有帧（[ScreenFrame.Origin] 或 [ScreenFrame.Target]）、当前集有视频轨，且不在片尾黑底期。
 * 没有画面（铺黑底）：当前集没有视频轨；片尾自动恢复的黑底期；屏上没有可沿用的帧。
 *
 * 比例随屏上那一帧取：原片画面用切集起点的原片比例（未知就是未知，不回退读引擎当前值，
 * 否则会被新文件上报的比例带偏），当前画面用引擎上报的比例，没有帧时没有比例。
 */
internal fun deriveSurfacePresentation(
    screenFrame: ScreenFrame,
    targetHasVideo: Boolean,
    endBlackHold: Boolean,
    originAspectRatio: Double?,
    targetAspectRatio: Double?,
    heldFrame: Bitmap?,
): SurfacePresentation {
    val showsPicture = when {
        !targetHasVideo -> false
        endBlackHold -> false
        else -> screenFrame != ScreenFrame.None
    }
    val aspectRatio = when (screenFrame) {
        ScreenFrame.Target -> targetAspectRatio
        ScreenFrame.Origin -> originAspectRatio
        ScreenFrame.None -> null
    }
    return SurfacePresentation(
        showsPicture = showsPicture,
        aspectRatio = aspectRatio,
        heldFrame = heldFrame,
    )
}
