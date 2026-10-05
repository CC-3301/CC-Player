package com.cc3301.ccplayer.playback

/**
 * 当前媒体播完后的去向：
 * [Sequential] 顺序播完停止、[ListLoop] 列表循环（末尾回第一个）、[SingleLoop] 单集循环（重播当前）。
 */
enum class LoopMode { Sequential, ListLoop, SingleLoop }
