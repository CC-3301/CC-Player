package com.cc3301.ccplayer.playback

/** 屏幕上停着的是哪一帧；画面事实只有这一个枚举。 */
enum class ScreenFrame {
    /** 屏幕上没有可沿用的画面：首次打开还没出帧、Surface 重建、目标接管后仍无帧。 */
    None,

    /** 切集前那次播放的画面；目标画面接管前一直留在屏上。 */
    Origin,

    /** 当前加载自己的画面，已确认接管视频输出。 */
    Target,
}

/**
 * 屏幕上现在该显示什么：界面只读这个派生结果，不再自行组合加载阶段、黑底与视频轨。
 *
 * [showsPicture] 为假时铺黑底；[aspectRatio] 是朝向解析与「比例未知期间铺黑」该用的比例。
 * [quietBlack] 为真表示这次黑屏不显示缓冲提示：只有后台切集与播放态回前台撤帧这两类黑屏。
 */
data class SurfacePresentation(
    val showsPicture: Boolean = false,
    val aspectRatio: Double? = null,
    val quietBlack: Boolean = false,
)

/**
 * 唯一的派生规则。
 *
 * 有画面：屏上有帧（[ScreenFrame.Origin] 或 [ScreenFrame.Target]）、当前集有视频轨，且没有挂起黑底。
 * 没有画面（铺黑底）：当前集没有视频轨；[holdBlack] 挂起；屏上没有可沿用的帧。
 *
 * [holdBlack] 是控制层已经判定好的显式事实（片尾黑底、无帧兜底），这里不再跨阶段现算。
 *
 * 比例随屏上那一帧取：原片画面用切集起点的原片比例（未知就是未知，不回退读引擎当前值，
 * 否则会被新文件上报的比例带偏），当前画面用引擎上报的比例，没有帧时没有比例。
 */
internal fun deriveSurfacePresentation(
    screenFrame: ScreenFrame,
    targetHasVideo: Boolean,
    holdBlack: Boolean,
    originAspectRatio: Double?,
    targetAspectRatio: Double?,
    quietBlack: Boolean = false,
): SurfacePresentation {
    val showsPicture = when {
        !targetHasVideo -> false
        holdBlack -> false
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
        quietBlack = quietBlack && !showsPicture,
    )
}
