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
 *
 * 顺序承诺只覆盖同一线程内的投递：队列按投递顺序执行，但队列空闲时的当场挂载会与
 * 其它线程同时投递的任务并发，这部分先后不由本类保证。
 */
internal class VideoOutputQueue(
    private val execute: (() -> Unit) -> Unit,
    /** 排队任务超过时限仍未执行的计时器；返回是否排上。不注入时按没有超时处理。 */
    private val schedule: (delayMillis: Long, task: () -> Unit) -> Boolean = { _, _ -> false },
    /** 静默丢弃时的留痕出口：投递被拒、超时计时没排上都从这里记一行。 */
    private val onDiagnostic: (String) -> Unit = {},
) {

    private val generation = AtomicInteger(0)

    /**
     * 已投递、尚未结算的任务数；[attach] 只看它判断队列是否空闲，不等待。
     * 超时的排队任务在超时那一刻就结算，不等迟到的任务真正跑完。
     */
    private val pending = AtomicInteger(0)

    /** 一次投递的结算令牌：任务执行与超时先到先得，只扣一次计数。 */
    private class Ticket {
        private val settled = AtomicBoolean(false)

        fun settleOnce(): Boolean = settled.compareAndSet(false, true)
    }

    /** 投递一条参与代际作废的任务；队列已关时不再执行。 */
    fun postOutput(task: () -> Unit) {
        val current = generation.get()
        submit(Ticket()) { if (current == generation.get()) task() }
    }

    /** 投递一条不参与作废的任务；投递被拒时返回 false。 */
    fun postAlways(task: () -> Unit): Boolean = submit(Ticket()) { task() }

    /** 作废已投递、尚未开始的开输出；正在执行的开输出本来就排在分离之前，不受影响。 */
    fun invalidate() {
        generation.incrementAndGet()
    }

    /**
     * 挂载 Surface：队列没有在途任务时在调用线程当场挂；有在途任务就把挂载排到它们之后，
     * 不抢在排队中的分离之前，也不等它们跑完。
     * 排队超过 [timeoutMillis] 仍未执行时按失败收尾：当场结算排队计数、调用 [onTimeout]，
     * 迟到的任务不再挂载。
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
        // 任务与超时只允许一个收尾：先抢到的赢，另一个不再动状态。
        val claimed = AtomicBoolean(false)
        val ticket = Ticket()
        val accepted = submit(ticket) {
            if (!claimed.compareAndSet(false, true)) return@submit
            if (stillCurrent()) attach() else onSkipped()
        }
        if (!accepted) {
            onSkipped()
            return
        }
        if (timeoutMillis > 0) {
            val armed = schedule(timeoutMillis) {
                if (claimed.compareAndSet(false, true)) {
                    settle(ticket)
                    onTimeout()
                }
            }
            if (!armed) onDiagnostic("挂载: 超时计时没有排上，这次排队挂载没有超时保护")
        }
    }

    /** 下发 Surface 尺寸：与挂载投同一条队列，同一线程内保持「先挂载、再下尺寸、再开输出」。 */
    fun postSurfaceSize(width: Int, height: Int, setProperty: (String, String) -> Unit) {
        val accepted = postAlways {
            setProperty("android-surface-size", "${width}x$height")
        }
        if (!accepted) onDiagnostic("尺寸: 视频输出线程不再受理，尺寸没有下发")
    }

    private fun submit(ticket: Ticket, task: () -> Unit): Boolean {
        pending.incrementAndGet()
        return runCatching {
            execute {
                try {
                    task()
                } finally {
                    settle(ticket)
                }
            }
            true
        }.getOrElse {
            settle(ticket)
            false
        }
    }

    private fun settle(ticket: Ticket) {
        if (ticket.settleOnce()) pending.decrementAndGet()
    }
}
