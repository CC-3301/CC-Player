package com.cc3301.ccplayer.library

import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun `没有缓存时返回空`() = runBlocking {
        assertNull(cache.file("content://root/电影.mkv"))
    }

    @Test
    fun `写入后能取回同一份内容`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        val saved = cache.save("content://root/电影.mkv", bytes)

        assertTrue(saved.isFile)
        assertNotNull(cache.file("content://root/电影.mkv"))
        assertArrayEquals(bytes, cache.file("content://root/电影.mkv")!!.readBytes())
    }

    @Test
    fun `不同位置各用一个缓存文件`() = runBlocking {
        val first = cache.save("content://root/a.mkv", ByteArray(4))
        val second = cache.save("content://root/b.mkv", ByteArray(4))

        assertNotEquals(first.name, second.name)
        assertEquals(2, directory.imageCount())
    }

    @Test
    fun `超过上限时先删最近最少使用的条目`() = runBlocking {
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
    fun `单条就超过上限时保留刚写入的条目`() = runBlocking {
        cache.save("content://root/a.mkv", ByteArray(20))

        assertNotNull(cache.file("content://root/a.mkv"))
    }

    @Test
    fun `带有效期的条目从写入起算`() = runBlocking {
        now = 1_000L
        cache.save("content://root/a.mkv", ByteArray(4))

        now = 1_499L
        assertNotNull(cache.file("content://root/a.mkv", maxAgeMillis = 500))

        now = 1_501L
        assertNull(cache.file("content://root/a.mkv", maxAgeMillis = 500))
    }

    @Test
    fun `元数据记录时长`() = runBlocking {
        cache.save("content://root/a.mkv", ByteArray(4), durationMillis = 187_000)

        val entry = cache.entry("content://root/a.mkv")

        assertNotNull(entry)
        assertEquals(187_000L, entry!!.durationMillis)
        assertTrue(entry.durationKnown)
    }

    @Test
    fun `淘汰条目时一并删除元数据`() = runBlocking {
        cache.save("content://root/a.mkv", ByteArray(8))
        now = 2_000L
        cache.save("content://root/b.mkv", ByteArray(8))

        assertEquals(1, directory.imageCount())
        assertEquals(1, directory.metadataCount())
    }

    @Test
    fun `首次访问时清理没有对应图片的孤儿元数据`() = runBlocking {
        val orphan = File(directory, "orphan.img.meta")
        orphan.writeText("1")
        val created = ThumbnailCache(directory = directory, maxBytes = 10, clock = { now })

        created.entry("content://root/不存在.mkv")

        assertTrue(!orphan.exists())
    }

    @Test
    fun `旧格式单行元数据视为时长未知`() = runBlocking {
        val key = "content://root/a.mkv"
        cache.save(key, ByteArray(4), durationMillis = 187_000)
        val image = cache.file(key)!!
        File(image.parentFile, image.name + ".meta").writeText("1000")

        val entry = cache.entry(key)

        assertNotNull(entry)
        assertNull(entry!!.durationMillis)
        assertTrue(!entry.durationKnown)
    }

    @Test
    fun `元数据第二行 null 表示确定无时长`() = runBlocking {
        cache.save("content://root/a.mkv", ByteArray(4), durationMillis = null)

        val entry = cache.entry("content://root/a.mkv")

        assertNotNull(entry)
        assertNull(entry!!.durationMillis)
        assertTrue(entry.durationKnown)
    }

    @Test
    fun `回写时长后命中直接复用`() = runBlocking {
        val key = "content://root/a.mkv"
        cache.save(key, ByteArray(4), durationMillis = null)

        cache.writeDuration(key, 187_000)

        val entry = cache.entry(key)
        assertEquals(187_000L, entry!!.durationMillis)
        assertTrue(entry.durationKnown)
    }

    @Test
    fun `图片不存在时回写时长不产生孤儿元数据`() = runBlocking {
        cache.writeDuration("content://root/没有图片.mkv", 187_000)

        assertEquals(0, directory.metadataCount())
        assertEquals(0, directory.tempCount())
    }

    @Test
    fun `并发写入不同键都保留图片与元数据`() = runBlocking {
        val concurrentDirectory = Files.createTempDirectory("thumbnail-concurrent").toFile()
        try {
            val concurrent = ThumbnailCache(directory = concurrentDirectory, maxBytes = 1 shl 20)
            val keys = (1..50).map { index -> "content://root/$index.mkv" }
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val threads = keys.map { key ->
                Thread {
                    runBlocking {
                        try {
                            concurrent.save(key, ByteArray(4), durationMillis = 1)
                        } catch (failure: Throwable) {
                            failures += failure
                        }
                    }
                }
            }

            threads.forEach(Thread::start)
            threads.forEach(Thread::join)

            assertTrue(failures.isEmpty())
            assertEquals(50, concurrentDirectory.imageCount())
            assertEquals(50, concurrentDirectory.metadataCount())
            keys.forEach { key -> assertNotNull(concurrent.entry(key)) }
        } finally {
            concurrentDirectory.deleteRecursively()
        }
    }

    @Test
    fun `再次写入同一位置覆盖旧内容`() = runBlocking {
        cache.save("content://root/a.mkv", ByteArray(4))

        val overwritten = cache.save("content://root/a.mkv", byteArrayOf(1, 2, 3))

        assertEquals(1, directory.imageCount())
        assertEquals(3L, overwritten.length())
    }

    @Test
    fun `首次访问时清理中断遗留的临时文件`() = runBlocking {
        val leftover = File(directory, "stale.img.123.tmp")
        leftover.writeText("半张图")
        val created = ThumbnailCache(directory = directory, maxBytes = 10, clock = { now })

        created.entry("content://root/不存在.mkv")

        assertTrue(!leftover.exists())
    }

    @Test
    fun `同一键并发写入不残留临时文件`() = runBlocking {
        val concurrentDirectory = Files.createTempDirectory("thumbnail-same-key").toFile()
        try {
            val concurrent = ThumbnailCache(directory = concurrentDirectory, maxBytes = 1 shl 20)
            val key = "content://root/同键.mkv"
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val threads = (1..50).map { index ->
                Thread {
                    runBlocking {
                        try {
                            val marker = index.toByte()
                            concurrent.save(key, ByteArray(1_024) { marker }, durationMillis = index.toLong())
                        } catch (failure: Throwable) {
                            failures += failure
                        }
                    }
                }
            }

            threads.forEach(Thread::start)
            threads.forEach(Thread::join)

            assertTrue(failures.isEmpty())
            assertEquals(1, concurrentDirectory.imageCount())
            assertEquals(1, concurrentDirectory.metadataCount())
            assertEquals(0, concurrentDirectory.tempCount())
            val entry = concurrent.entry(key)
            assertNotNull(entry)
            assertEquals(1_024L, entry!!.file!!.length())
            assertTrue(entry.durationKnown)
            // 图片与时长必须来自同一次写入。
            assertEquals(entry.file!!.readBytes()[0].toLong(), entry.durationMillis)
        } finally {
            concurrentDirectory.deleteRecursively()
        }
    }

    @Test
    fun `多实例并发写同一目录不互相踩踏`() = runBlocking {
        val sharedDirectory = Files.createTempDirectory("thumbnail-shared").toFile()
        try {
            val first = ThumbnailCache(directory = sharedDirectory, maxBytes = 1 shl 20)
            val second = ThumbnailCache(directory = sharedDirectory, maxBytes = 1 shl 20)
            val keys = (1..40).map { index -> "content://root/多实例$index.mkv" }
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val threads = keys.mapIndexed { index, key ->
                val cache = if (index % 2 == 0) first else second
                val marker = (index + 1).toByte()
                Thread {
                    runBlocking {
                        try {
                            cache.save(key, ByteArray(8) { marker }, durationMillis = (index + 1).toLong())
                        } catch (failure: Throwable) {
                            failures += failure
                        }
                    }
                }
            }

            threads.forEach(Thread::start)
            threads.forEach(Thread::join)

            assertTrue(failures.isEmpty())
            assertEquals(40, sharedDirectory.imageCount())
            assertEquals(40, sharedDirectory.metadataCount())
            assertEquals(0, sharedDirectory.tempCount())
            keys.forEach { key ->
                val entry = first.entry(key)
                assertNotNull(entry)
                assertEquals(entry!!.file!!.readBytes()[0].toLong(), entry.durationMillis)
            }
        } finally {
            sharedDirectory.deleteRecursively()
        }
    }

    @Test
    fun `多实例并发写同一键后条目完整`() = runBlocking {
        val sharedDirectory = Files.createTempDirectory("thumbnail-shared-key").toFile()
        try {
            val first = ThumbnailCache(directory = sharedDirectory, maxBytes = 1 shl 20)
            val second = ThumbnailCache(directory = sharedDirectory, maxBytes = 1 shl 20)
            val key = "content://root/共享同键.mkv"
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val threads = (1..40).map { index ->
                val cache = if (index % 2 == 0) first else second
                Thread {
                    runBlocking {
                        try {
                            val marker = index.toByte()
                            cache.save(key, ByteArray(16) { marker }, durationMillis = index.toLong())
                        } catch (failure: Throwable) {
                            failures += failure
                        }
                    }
                }
            }

            threads.forEach(Thread::start)
            threads.forEach(Thread::join)

            assertTrue(failures.isEmpty())
            assertEquals(1, sharedDirectory.imageCount())
            assertEquals(1, sharedDirectory.metadataCount())
            assertEquals(0, sharedDirectory.tempCount())
            val entry = first.entry(key)
            assertNotNull(entry)
            assertNotNull(second.entry(key))
            assertTrue(entry!!.durationKnown)
            assertEquals(entry.file!!.readBytes()[0].toLong(), entry.durationMillis)
        } finally {
            sharedDirectory.deleteRecursively()
        }
    }

    @Test
    fun `并发保存与淘汰保持图片与时长配对`() = runBlocking {
        val sharedDirectory = Files.createTempDirectory("thumbnail-evict-race").toFile()
        try {
            val concurrent = ThumbnailCache(directory = sharedDirectory, maxBytes = 32)
            val keys = (1..30).map { index -> "content://root/并发淘汰$index.mkv" }
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val threads = keys.mapIndexed { index, key ->
                val marker = (index + 1).toByte()
                Thread {
                    runBlocking {
                        try {
                            concurrent.save(key, ByteArray(32) { marker }, durationMillis = (index + 1).toLong())
                        } catch (failure: Throwable) {
                            failures += failure
                        }
                    }
                }
            }

            threads.forEach(Thread::start)
            threads.forEach(Thread::join)

            assertTrue(failures.isEmpty())
            assertEquals(0, sharedDirectory.tempCount())
            keys.forEach { key ->
                concurrent.entry(key)?.let { entry ->
                    val image = entry.file ?: return@let
                    assertEquals(image.readBytes()[0].toLong(), entry.durationMillis)
                }
            }
            assertEquals(sharedDirectory.imageCount(), sharedDirectory.metadataCount())
        } finally {
            sharedDirectory.deleteRecursively()
        }
    }

    @Test
    fun `替换失败时清理临时文件`() = runBlocking {
        val key = "content://root/写入失败.mkv"
        val blocked = File(directory, cacheName(key))
        assertTrue(blocked.mkdirs())
        File(blocked, "占位").writeText("x")

        try {
            cache.save(key, ByteArray(4))
            fail("缓存写入应失败")
        } catch (expected: java.io.IOException) {
            // 预期失败路径。
        }

        assertEquals(0, directory.tempCount())
    }

    @Test
    fun `无图条目记录时长且再次访问仍可命中`() = runBlocking {
        val key = "content://root/无图.mp3"
        cache.saveWithoutImage(key, durationMillis = 187_000)

        val entry = cache.entry(key)

        assertNotNull(entry)
        assertNull(entry!!.file)
        assertEquals(187_000L, entry.durationMillis)
        assertTrue(entry.durationKnown)
    }

    @Test
    fun `无图条目在启动清理后保留`() = runBlocking {
        val key = "content://root/无图启动.mp3"
        cache.saveWithoutImage(key, durationMillis = 187_000)

        val recreated = ThumbnailCache(directory = directory, maxBytes = 10, clock = { now })
        val entry = recreated.entry(key)

        assertNotNull(entry)
        assertNull(entry!!.file)
        assertEquals(187_000L, entry.durationMillis)
    }

    @Test
    fun `无图条目补写时长保留无图标记`() = runBlocking {
        val key = "content://root/无图补写.mp3"
        cache.saveWithoutImage(key, durationKnown = false)

        cache.writeDuration(key, 187_000)

        val entry = cache.entry(key)
        assertNotNull(entry)
        assertNull(entry!!.file)
        assertEquals(187_000L, entry.durationMillis)
        assertTrue(entry.durationKnown)
    }

    @Test
    fun `补写时长沿用图片修改时间不续期`() = runBlocking {
        val key = "content://root/沿用时间.mkv"
        now = 900L
        cache.save(key, ByteArray(4))
        val image = cache.file(key)!!
        File(image.parentFile, image.name + ".meta").delete()
        image.setLastModified(1_000L)
        now = 1_200L

        cache.writeDuration(key, 187_000)

        now = 1_400L
        assertNotNull(cache.entry(key, maxAgeMillis = 500))
        now = 1_501L
        assertNull(cache.entry(key, maxAgeMillis = 500))
    }

    private fun File.imageCount(): Int =
        listFiles().orEmpty().count { file -> file.isFile && file.name.endsWith(".img") }

    private fun File.metadataCount(): Int =
        listFiles().orEmpty().count { file -> file.isFile && file.name.endsWith(".meta") }

    private fun File.tempCount(): Int =
        listFiles().orEmpty().count { file -> file.isFile && file.name.endsWith(".tmp") }

    private fun cacheName(location: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(location.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) } + ".img"
    }
}
