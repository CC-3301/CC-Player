package com.cc3301.ccplayer.library

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCacheTest {

    private val directory: File = Files.createTempDirectory("thumbnail-cache").toFile()
    private var now = 1_000L
    private val cache = ThumbnailCache(directory = directory, maxBytes = 10, clock = { now })

    @After
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun `没有缓存时返回空`() {
        assertNull(cache.file("content://root/电影.mkv"))
    }

    @Test
    fun `写入后能取回同一份内容`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        val saved = cache.save("content://root/电影.mkv", bytes)

        assertTrue(saved.isFile)
        assertNotNull(cache.file("content://root/电影.mkv"))
        assertArrayEquals(bytes, cache.file("content://root/电影.mkv")!!.readBytes())
    }

    @Test
    fun `不同位置各用一个缓存文件`() {
        val first = cache.save("content://root/a.mkv", ByteArray(4))
        val second = cache.save("content://root/b.mkv", ByteArray(4))

        assertNotEquals(first.name, second.name)
        assertEquals(2, imageFileCount())
    }

    @Test
    fun `超过上限时先删最近最少使用的条目`() {
        cache.save("content://root/a.mkv", ByteArray(5))
        now = 2_000L
        cache.save("content://root/b.mkv", ByteArray(5))
        now = 3_000L
        cache.file("content://root/a.mkv")
        now = 4_000L

        cache.save("content://root/c.mkv", ByteArray(5))

        assertNull(cache.file("content://root/b.mkv"))
        assertNotNull(cache.file("content://root/a.mkv"))
        assertNotNull(cache.file("content://root/c.mkv"))
    }

    @Test
    fun `单条就超过上限时保留刚写入的条目`() {
        cache.save("content://root/a.mkv", ByteArray(20))

        assertNotNull(cache.file("content://root/a.mkv"))
    }

    @Test
    fun `带有效期的条目从写入起算`() {
        now = 1_000L
        cache.save("content://root/a.mkv", ByteArray(4))

        now = 1_499L
        assertNotNull(cache.file("content://root/a.mkv", maxAgeMillis = 500))

        now = 1_501L
        assertNull(cache.file("content://root/a.mkv", maxAgeMillis = 500))
    }

    @Test
    fun `元数据记录时长`() {
        cache.save("content://root/a.mkv", ByteArray(4), durationMillis = 187_000)

        val entry = cache.entry("content://root/a.mkv")

        assertNotNull(entry)
        assertEquals(187_000L, entry!!.durationMillis)
        assertTrue(entry.durationKnown)
    }

    @Test
    fun `淘汰条目时一并删除元数据`() {
        cache.save("content://root/a.mkv", ByteArray(8))
        now = 2_000L
        cache.save("content://root/b.mkv", ByteArray(8))

        assertEquals(1, imageFileCount())
        assertEquals(1, metadataFileCount())
    }

    @Test
    fun `启动时清理没有对应图片的孤儿元数据`() {
        val orphan = File(directory, "orphan.img.meta")
        orphan.writeText("1")

        ThumbnailCache(directory = directory, maxBytes = 10, clock = { now })

        assertTrue(!orphan.exists())
    }

    @Test
    fun `旧格式单行元数据视为时长未知`() {
        cache.save("content://root/a.mkv", ByteArray(4), durationMillis = 187_000)
        metadataFile().writeText("1000")

        val entry = cache.entry("content://root/a.mkv")

        assertNotNull(entry)
        assertNull(entry!!.durationMillis)
        assertTrue(!entry.durationKnown)
    }

    @Test
    fun `元数据第二行 null 表示确定无时长`() {
        cache.save("content://root/a.mkv", ByteArray(4), durationMillis = null)

        val entry = cache.entry("content://root/a.mkv")

        assertNotNull(entry)
        assertNull(entry!!.durationMillis)
        assertTrue(entry.durationKnown)
    }

    @Test
    fun `回写时长后命中直接复用`() {
        cache.save("content://root/a.mkv", ByteArray(4), durationMillis = null)

        cache.writeDuration("content://root/a.mkv", 187_000)

        val entry = cache.entry("content://root/a.mkv")
        assertEquals(187_000L, entry!!.durationMillis)
        assertTrue(entry.durationKnown)
    }

    @Test
    fun `并发写入不同键都保留图片与元数据`() {
        val concurrentDirectory = Files.createTempDirectory("thumbnail-concurrent").toFile()
        try {
            val concurrent = ThumbnailCache(directory = concurrentDirectory, maxBytes = 1 shl 20)
            val keys = (1..50).map { index -> "content://root/$index.mkv" }
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val threads = keys.map { key ->
                Thread {
                    try {
                        concurrent.save(key, ByteArray(4), durationMillis = 1)
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            }

            threads.forEach(Thread::start)
            threads.forEach(Thread::join)

            assertTrue(failures.isEmpty())
            assertEquals(50, concurrentDirectory.listFiles().orEmpty().count { it.name.endsWith(".img") })
            assertEquals(50, concurrentDirectory.listFiles().orEmpty().count { it.name.endsWith(".meta") })
            keys.forEach { key -> assertNotNull(concurrent.entry(key)) }
        } finally {
            concurrentDirectory.deleteRecursively()
        }
    }

    @Test
    fun `再次写入同一位置覆盖旧内容`() {
        cache.save("content://root/a.mkv", ByteArray(4))

        val overwritten = cache.save("content://root/a.mkv", byteArrayOf(1, 2, 3))

        assertEquals(1, imageFileCount())
        assertEquals(3L, overwritten.length())
    }

    private fun imageFileCount(): Int =
        directory.listFiles().orEmpty().count { file -> file.name.endsWith(".img") }

    private fun metadataFileCount(): Int =
        directory.listFiles().orEmpty().count { file -> file.name.endsWith(".meta") }

    private fun metadataFile(): File =
        directory.listFiles().orEmpty().first { file -> file.name.endsWith(".meta") }
}
