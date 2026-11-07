package com.cc3301.ccplayer.playback

import java.util.concurrent.atomic.AtomicInteger

/**
 * 视频输出投递队列：除「当场挂载」外，Surface 相关调用都在同一条视频输出线程上按投递顺序执行，
 * 队列自己不加锁；当场挂载由 [attach] 在调用线程直接执行。
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

    /** 已投递、尚未跑完的任务数；挂载据此决定当场执行还是排队，调用线程不等待。 */
    private val pending = AtomicInteger(0)

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

    /**
     * 挂载 Surface：队列没有在途任务时在调用线程当场挂；有在途任务就把挂载排到它们之后，
     * 不抢在排队中的分离之前，也不等它们跑完。
     * [stillCurrent] 在真正挂载之前都要过一次：当场挂那次在调用线程过，排队那次在视频输出线程过。
     * 返回 false 有两种含义——已排到队列之后，或校验不过被跳过（[onSkipped] 在对应的那条线程执行）。
     */
    fun attach(
        stillCurrent: () -> Boolean,
        attach: () -> Unit,
        onSkipped: () -> Unit,
    ): Boolean {
        if (pending.get() == 0) {
            // 检查与挂载之间可能已经释放：挂之前再确认一次，别对着已销毁的核心挂载。
            if (!stillCurrent()) {
                onSkipped()
                return false
            }
            attach()
            return true
        }
        val accepted = postAlways {
            if (stillCurrent()) attach() else onSkipped()
        }
        if (!accepted) onSkipped()
        return false
    }

    private fun submit(task: () -> Unit): Boolean {
        pending.incrementAndGet()
        return runCatching {
            execute {
                try {
                    task()
                } finally {
                    pending.decrementAndGet()
                }
            }
            true
        }.getOrElse {
            pending.decrementAndGet()
            false
        }
    }
}
