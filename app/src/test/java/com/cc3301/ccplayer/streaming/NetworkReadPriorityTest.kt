package com.cc3301.ccplayer.streaming

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReadPriorityTest {

    private val priority = NetworkReadPriority()

    @Test
    fun `缩略图读取等待播放读取完成`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val playbackStarted = CountDownLatch(1)
        val releasePlayback = CountDownLatch(1)
        val thumbnailStarted = CountDownLatch(1)

        val playback = priority.wrap(
            object : ByteSource {
                override val size: Long = 1
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    events += "播放开始"
                    playbackStarted.countDown()
                    releasePlayback.await()
                    events += "播放结束"
                    return 1
                }

                override fun close() = Unit
            },
            ReadKind.Playback,
        )
        val thumbnail = priority.wrap(
            object : ByteSource {
                override val size: Long = 1
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    events += "缩略图读取"
                    thumbnailStarted.countDown()
                    return 1
                }

                override fun close() = Unit
            },
            ReadKind.Thumbnail,
        )

        val playbackThread = Thread { playback.readAt(0, ByteArray(1), 0, 1) }
        playbackThread.start()
        assertTrue(playbackStarted.await(2, TimeUnit.SECONDS))

        val thumbnailThread = Thread { thumbnail.readAt(0, ByteArray(1), 0, 1) }
        thumbnailThread.start()
        // 播放读取仍持有优先级时，缩略图读取不能越过它。
        assertFalse(thumbnailStarted.await(100, TimeUnit.MILLISECONDS))

        releasePlayback.countDown()
        assertTrue(thumbnailStarted.await(2, TimeUnit.SECONDS))
        playbackThread.join(2_000)
        thumbnailThread.join(2_000)

        assertEquals(listOf("播放开始", "播放结束", "缩略图读取"), events.toList())
    }

    @Test
    fun `关闭缩略图字节源会唤醒等待中的读取`() {
        val playbackStarted = CountDownLatch(1)
        val releasePlayback = CountDownLatch(1)
        val thumbnailFinished = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)

        val playback = priority.wrap(
            object : ByteSource {
                override val size: Long = 1
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    playbackStarted.countDown()
                    releasePlayback.await()
                    return 1
                }

                override fun close() = Unit
            },
            ReadKind.Playback,
        )
        val thumbnail = priority.wrap(
            object : ByteSource {
                override val size: Long = 1
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = 1
                override fun close() = Unit
            },
            ReadKind.Thumbnail,
        )

        val playbackThread = Thread { playback.readAt(0, ByteArray(1), 0, 1) }
        playbackThread.start()
        assertTrue(playbackStarted.await(2, TimeUnit.SECONDS))

        val thumbnailThread = Thread {
            try {
                thumbnail.readAt(0, ByteArray(1), 0, 1)
            } catch (failureWhileCancelled: Throwable) {
                failure[0] = failureWhileCancelled
            } finally {
                thumbnailFinished.countDown()
            }
        }
        thumbnailThread.start()
        val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (priority.waitingThumbnailReadCount == 0 && System.nanoTime() < waitDeadline) {
            Thread.yield()
        }
        assertEquals(1, priority.waitingThumbnailReadCount)

        thumbnail.close()
        assertTrue(thumbnailFinished.await(2, TimeUnit.SECONDS))
        assertTrue(failure[0] is java.io.IOException)

        releasePlayback.countDown()
        playbackThread.join(2_000)
    }
}
