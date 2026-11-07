package com.cc3301.ccplayer.playback

import java.util.concurrent.atomic.AtomicBoolean
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
internal class VideoOutputQueue(
    private val execute: (() -> Unit) -> Unit,
    /** 排队任务超过时限仍未执行的计时器；不注入时按没有超时处理。 */
    private val schedule: (delayMillis: Long, task: () -> Unit) -> Unit = { _, _ -> },
) {

    private val generation = AtomicInteger(0)

    /**
     * 已投递、尚未跑完的任务数；[attach] 只看它判断队列是否空闲，不等待。
     * 判断与当场挂载之间新投递的任务会与挂载并发——队列只承诺已投递任务的先后。
     */
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
     * 排队超过 [timeoutMillis] 仍未执行时按失败收尾并调用 [onTimeout]，迟到的任务不再挂载。
     * [stillCurrent] 在真正挂载之前都要过一次：当场挂那次在调用线程过，排队那次在视频输出线程过。
     * [onSkipped] 在跳过时对应的那条线程执行一次。
     */
    fun attach(
        stillCurrent: () -> Boolean,
        attach: () -> Unit,
        onSkipped: () -> Unit,
        timeoutMillis: Long = 0L,
        onTimeout: () -> Unit = {},
    ) {
        if (pending.get() == 0) {
            if (stillCurrent()) attach() else onSkipped()
            return
        }
        // 任务与超时只允许一个收尾：先到的赢，另一个不再动状态。
        val timedOut = AtomicBoolean(false)
        val settled = AtomicBoolean(false)
        val accepted = postAlways {
            if (timedOut.get()) return@postAlways
            if (stillCurrent() && settled.compareAndSet(false, true)) {
                attach()
            } else if (settled.compareAndSet(false, true)) {
                onSkipped()
            }
        }
        if (!accepted) {
            if (settled.compareAndSet(false, true)) onSkipped()
            return
        }
        if (timeoutMillis > 0) {
            schedule(timeoutMillis) {
                if (timedOut.compareAndSet(false, true) && settled.compareAndSet(false, true)) {
                    onTimeout()
                }
            }
        }
    }

    /** 下发 Surface 尺寸：与挂载投同一条队列，保证「先挂 Surface、再设尺寸、再开窗口」。 */
    fun postSurfaceSize(width: Int, height: Int, setProperty: (String, String) -> Unit) {
        postAlways {
            setProperty("android-surface-size", "${width}x$height")
        }
    }

    private fun submit(task: () -> Unit): Boolean {
        pending.incrementAndGet()
        // 执行器可能内联跑完再抛异常：任务与投递只结算一次，计数不能被扣成负数。
        val settled = AtomicBoolean(false)
        return runCatching {
            execute {
                try {
                    task()
                } finally {
                    settle(settled)
                }
            }
            true
        }.getOrElse {
            settle(settled)
            false
        }
    }

    private fun settle(settled: AtomicBoolean) {
        if (settled.compareAndSet(false, true)) pending.decrementAndGet()
    }
}
