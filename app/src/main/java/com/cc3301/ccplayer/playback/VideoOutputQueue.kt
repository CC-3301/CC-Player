package com.cc3301.ccplayer.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 视频输出投递队列：Surface 相关调用都在同一条线程上按投递顺序执行，队列自己不加锁。
 *
 * 两类任务：
 * - [postOutput]：开输出、翻转，参与代际作废——投递之后被作废就不再执行；
 * - [postAlways]：分离这类必须执行的任务，不参与作废——销毁播放核心之前一定跑到。
 *
 * 作废只对尚未开始的任务有意义：单线程执行器下已经开始执行的任务必然排在分离之前，
 * 所以 [invalidate] 只做一次原子递增，不等任何人。
 */
internal class VideoOutputQueue(private val execute: (() -> Unit) -> Unit) {

    private val generation = AtomicInteger(0)

    /** 投递一条参与代际作废的任务；队列已关时不再执行。 */
    fun postOutput(task: () -> Unit) {
        val current = generation.get()
        submit { if (current == generation.get()) task() }
    }

    /** 投递一条不参与作废的任务；投递被拒时返回 false。 */
    fun postAlways(task: () -> Unit): Boolean = submit { task() }

    /** 作废已投递、尚未开始的开输出；正在执行的开输出本来就排在分离之前，不受影响。 */
    fun invalidate() {
        generation.incrementAndGet()
    }

    /** 等已投递的任务跑完；返回是否在 [timeoutMillis] 内排空。 */
    fun awaitIdle(timeoutMillis: Long): Boolean {
        val idle = CountDownLatch(1)
        if (!submit { idle.countDown() }) return false
        return runCatching {
            idle.await(timeoutMillis, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
    }

    /**
     * 挂载 Surface：队列跑空时当场挂；等不到跑空就把挂载排到已投递任务之后，
     * 不抢在排队中的分离之前。[stillCurrent] 在那条排队的任务执行时再确认一次。
     * 返回是否当场挂上；没能执行的分支交给 [onSkipped] 留痕。
     */
    fun attach(
        awaitMillis: Long,
        stillCurrent: () -> Boolean,
        attach: () -> Unit,
        onSkipped: () -> Unit,
    ): Boolean {
        if (awaitIdle(awaitMillis)) {
            attach()
            return true
        }
        val accepted = postAlways {
            if (stillCurrent()) attach() else onSkipped()
        }
        if (!accepted) onSkipped()
        return false
    }

    private fun submit(task: () -> Unit): Boolean =
        runCatching {
            execute { task() }
            true
        }.getOrDefault(false)
}
