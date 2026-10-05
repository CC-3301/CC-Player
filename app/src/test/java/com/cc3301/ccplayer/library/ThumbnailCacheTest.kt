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
        assertEquals(2, directory.listFiles()!!.size)
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
    fun `再次写入同一位置覆盖旧内容`() {
        cache.save("content://root/a.mkv", ByteArray(4))

        val overwritten = cache.save("content://root/a.mkv", byteArrayOf(1, 2, 3))

        assertEquals(1, directory.listFiles()!!.size)
        assertEquals(3L, overwritten.length())
    }
}
