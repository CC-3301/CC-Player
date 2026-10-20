package com.cc3301.ccplayer.ui

import kotlin.math.roundToInt

/** 设备媒体音量的读写，取值 0~[maxVolume] 档。 */
interface VolumeControl {
    val maxVolume: Int

    fun read(): Int

    fun write(volume: Int)
}

/**
 * 上下拖动调音量的换算与写入：手指上滑为增大，滑过整屏高度等于满量程，
 * 结果夹取到 0~[VolumeControl.maxVolume]。
 *
 * 拖动过程按累计比例推进：音量档位取整后回读会停在原档，逐次累积才能把小幅拖动走满。
 */
class PlayerLevelControls(private val volume: VolumeControl) {

    private var volumeFraction: Float? = null

    /** 拖动音量，返回新音量占满档的比例 0~1，供界面提示。 */
    fun dragVolume(dragAmount: Float, viewHeight: Int): Float {
        val max = volume.maxVolume
        if (max <= 0) return 0f
        val from = volumeFraction ?: (volume.read().toFloat() / max).coerceIn(0f, 1f)
        val next = advance(from, dragAmount, viewHeight)
        volumeFraction = next
        volume.write((next * max).roundToInt().coerceIn(0, max))
        return next
    }
}

/** 手指上滑（[dragAmount] 为负）为增大；滑过整屏高度等于满量程；结果夹取到 0~1。 */
private fun advance(fraction: Float, dragAmount: Float, viewHeight: Int): Float {
    if (viewHeight <= 0) return fraction.coerceIn(0f, 1f)
    return (fraction - dragAmount / viewHeight).coerceIn(0f, 1f)
}
