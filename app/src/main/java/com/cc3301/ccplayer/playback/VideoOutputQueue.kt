package com.cc3301.ccplayer.playback

import java.util.concurrent.atomic.AtomicInteger

/**
 * 视频输出投递队列：Surface 相关调用都在同一条线程上按投递顺序执行。
 *
 * 两类任务：
 * - [postOutput]：开输出、翻转，参与代际作废——拆挂之后到点的任务不再执行，vo 不会被重新打开；
 * - [postAlways]：分离这类必须执行的任务，不参与作废——销毁播放核心之前一定跑到，
 *   排在它后面的挂载也一定在它之后执行。
 */
internal class VideoOutputQueue(private val execute: (() -> Unit) -> Unit) {

    private val lock = Any()
    private val generation = AtomicInteger(0)

    /** 投递一条参与代际作废的任务。 */
    fun postOutput(task: () -> Unit) {
        val current = generation.get()
        submit { if (current == generation.get()) task() }
    }

    /** 投递一条不参与作废的任务：分离与它之后的挂载都走这条。 */
    fun postAlways(task: () -> Unit) {
        submit { task() }
    }

    /** 作废已投递的开输出；正在执行的任务不受影响。 */
    fun invalidate() {
        synchronized(lock) { generation.incrementAndGet() }
    }

    private fun submit(task: () -> Unit) {
        // 释放与投递之间仍可能关线程：这里只丢弃，不能把拒绝执行抛给调用方。
        runCatching { execute { synchronized(lock) { task() } } }
    }
}
