package com.cc3301.ccplayer.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 原生播放核心的创建与销毁接力。
 *
 * 释放时先登记一次收尾并发布完成信号，再启动收尾线程；完成信号只在销毁返回后发出。
 * 创建方等到信号才创建；等到上限仍没有信号就放弃本次创建，不与销毁并发。
 * 进程内只应有一个播放核心：正常路径下同一时刻最多一次待收尾登记。
 */
internal class NativeCoreLifecycle {

    private val lock = Any()

    private var pending: CountDownLatch? = null

    /** 创建前等待已登记的收尾完成；超时返回 false，调用方放弃本次创建。 */
    fun beginCreate(timeoutMillis: Long): Boolean {
        val waiting = synchronized(lock) { pending } ?: return true
        return try {
            waiting.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 登记一次收尾：完成信号在收尾线程启动之前就可以被创建方看到。 */
    fun registerTeardown(): CountDownLatch = synchronized(lock) {
        CountDownLatch(1).also { pending = it }
    }

    /** 收尾结束：放行等待中的创建方。 */
    fun finishTeardown(teardown: CountDownLatch) {
        synchronized(lock) { if (pending === teardown) pending = null }
        teardown.countDown()
    }
}
