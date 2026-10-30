package com.cc3301.ccplayer.streaming

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** 网络读取类型：播放读取优先于缩略图读取。 */
enum class ReadKind { Playback, Thumbnail }

/**
 * 网络读取优先级：播放读取进行时，缩略图读取等待；播放读取结束后再放行。
 *
 * [wrap] 返回的字节源在关闭时会唤醒等待中的缩略图读取，保证取消能落到传输层。
 */
class NetworkReadPriority {

    private val lock = ReentrantLock()
    private val playbackIdle = lock.newCondition()

    /** 正在进行的播放读取数；缩略图读取至少要让出这些读取。 */
    private var playbackReads = 0

    /** 把 [source] 包装成按 [kind] 排队的字节源。 */
    fun wrap(source: ByteSource, kind: ReadKind): ByteSource = PriorityByteSource(source, this, kind)

    internal fun <T> playback(block: () -> T): T {
        lock.lock()
        try {
            playbackReads++
        } finally {
            lock.unlock()
        }
        try {
            return block()
        } finally {
            lock.lock()
            try {
                playbackReads--
                if (playbackReads == 0) playbackIdle.signalAll()
            } finally {
                lock.unlock()
            }
        }
    }

    internal fun <T> thumbnail(closed: () -> Boolean, block: () -> T): T {
        lock.lockInterruptibly()
        try {
            while (playbackReads > 0 && !closed()) playbackIdle.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("缩略图读取被中断", interrupted)
        } finally {
            lock.unlock()
        }
        if (closed()) throw IOException("缩略图读取已取消")
        return block()
    }

    /** 关闭字节源时唤醒等待中的缩略图读取；被唤醒后按已关闭处理。 */
    internal fun wakeWaiters() {
        lock.lock()
        try {
            playbackIdle.signalAll()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** 进程内播放与缩略图共用的读取优先级。 */
        val shared = NetworkReadPriority()
    }
}

/**
 * 按优先级读的字节源包装：只改变读取排队，不改变读取位置与长度。
 * 关闭标记先于底层关闭生效，避免等待中的读取在关闭后继续进入底层。
 */
private class PriorityByteSource(
    private val source: ByteSource,
    private val priority: NetworkReadPriority,
    private val kind: ReadKind,
) : ByteSource {

    private val closed = AtomicBoolean(false)

    override val size: Long get() = source.size

    override val maxReadBytes: Int get() = source.maxReadBytes

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed.get()) return 0
        return when (kind) {
            ReadKind.Playback -> priority.playback { source.readAt(position, buffer, offset, length) }
            ReadKind.Thumbnail -> priority.thumbnail({ closed.get() }) {
                source.readAt(position, buffer, offset, length)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        priority.wakeWaiters()
        runCatching { source.close() }
    }
}
