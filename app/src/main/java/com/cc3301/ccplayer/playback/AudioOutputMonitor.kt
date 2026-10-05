package com.cc3301.ccplayer.playback

/**
 * 音频输出中断接缝：耳机拔出、蓝牙断连等系统广播经此上报，测试用假实现驱动。
 */
interface AudioOutputMonitor {

    /** 开始监听；每次输出中断回调 [onOutputLost]。 */
    fun start(onOutputLost: () -> Unit)

    fun stop()
}
