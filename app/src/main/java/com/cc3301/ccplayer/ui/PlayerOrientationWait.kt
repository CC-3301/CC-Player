package com.cc3301.ccplayer.ui

import kotlinx.coroutines.delay

/** 首次摆位等屏幕转向视频方向的上限：到点无论方向是否到位都撤黑，不允许一直黑着。 */
const val PLAYER_ORIENTATION_WAIT_LIMIT_MILLIS = 2_000L

/**
 * 撤黑前等屏幕方向转到位，最多等到 [PLAYER_ORIENTATION_WAIT_LIMIT_MILLIS]。
 * 方向先到位时调用方取消本协程；这里只负责到点放行。
 */
suspend fun runOrientationWait() {
    delay(PLAYER_ORIENTATION_WAIT_LIMIT_MILLIS)
}
