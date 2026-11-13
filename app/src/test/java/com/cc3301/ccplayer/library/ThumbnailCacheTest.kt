package com.cc3301.ccplayer.library

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
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
    fun `缓存不设有效期，长时间后仍然命中`() = runBlocking {
        now = 1_000L
        cache.save("content://root/a.mkv", ByteArray(4))

        now = 365L * 24 * 60 * 60 * 1000

        assertNotNull(cache.file("content://root/a.mkv"))
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
    fun `无法解析的元数据按时长未知处理`() = runBlocking {
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
    fun `时长行 null 表示确定无时长`() = runBlocking {
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
            val tick = AtomicLong(1_000L)
            val concurrent = ThumbnailCache(
                directory = sharedDirectory,
                maxBytes = 32,
                clock = { tick.getAndIncrement() },
            )
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
            // 上限只够一条，并发写入后必须确实发生过淘汰。
            assertTrue(sharedDirectory.imageCount() in 1 until keys.size)
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
    fun `首次并发访问完成启动清理`() = runBlocking {
        repeat(20) { index -> File(directory, "stale$index.tmp").writeText("x") }
        val orphan = File(directory, "orphan.img.meta")
        orphan.writeText("1")
        val concurrent = ThumbnailCache(directory = directory, maxBytes = 1 shl 20)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (1..32).map { index ->
            Thread {
                runBlocking {
                    try {
                        concurrent.entry("content://root/并发首次$index.mkv")
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            }
        }

        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertTrue(failures.isEmpty())
        assertEquals(0, directory.tempCount())
        assertTrue(!orphan.exists())
    }

    @Test
    fun `无图条目命中刷新后不被优先淘汰`() = runBlocking {
        var now = 1_000L
        val capped = ThumbnailCache(
            directory = directory,
            maxBytes = 1 shl 20,
            clock = { now },
            maxMetadataEntries = 2,
        )
        capped.saveWithoutImage("content://root/命中甲.mp3", durationMillis = 1)
        now += 10
        capped.saveWithoutImage("content://root/命中乙.mp3", durationMillis = 2)
        now += 10
        assertNotNull(capped.entry("content://root/命中甲.mp3"))
        now += 10

        capped.saveWithoutImage("content://root/命中丙.mp3", durationMillis = 3)

        assertNotNull(capped.entry("content://root/命中甲.mp3"))
        assertNull(capped.entry("content://root/命中乙.mp3"))
        assertNotNull(capped.entry("content://root/命中丙.mp3"))
    }

    @Test
    fun `补写时长不撤销命中刷新的 LRU 时间`() = runBlocking {
        var now = 1_000L
        val capped = ThumbnailCache(
            directory = directory,
            maxBytes = 1 shl 20,
            clock = { now },
            maxMetadataEntries = 2,
        )
        capped.saveWithoutImage("content://root/补写甲.mp3", durationKnown = false)
        now += 10
        capped.saveWithoutImage("content://root/补写乙.mp3", durationMillis = 2)
        now += 10
        assertNotNull(capped.entry("content://root/补写甲.mp3"))
        now += 5

        capped.writeDuration("content://root/补写甲.mp3", 187_000)
        now += 5
        capped.saveWithoutImage("content://root/补写丙.mp3", durationMillis = 3)

        val kept = capped.entry("content://root/补写甲.mp3")
        assertNotNull(kept)
        assertEquals(187_000L, kept!!.durationMillis)
        assertNull(capped.entry("content://root/补写乙.mp3"))
    }

    @Test
    fun `无图条目淘汰不误删图片元数据`() = runBlocking {
        var now = 1_000L
        val capped = ThumbnailCache(
            directory = directory,
            maxBytes = 1 shl 20,
            clock = { now },
            maxMetadataEntries = 2,
        )
        val imageKey = "content://root/共存图片.mkv"
        capped.save(imageKey, ByteArray(4), durationMillis = 9)
        now += 10
        capped.saveWithoutImage("content://root/共存甲.mp3", durationMillis = 1)
        now += 10
        capped.saveWithoutImage("content://root/共存乙.mp3", durationMillis = 2)
        now += 10
        capped.saveWithoutImage("content://root/共存丙.mp3", durationMillis = 3)

        val image = capped.entry(imageKey)
        assertNotNull(image)
        assertNotNull(image!!.file)
        assertEquals(9L, image.durationMillis)
        assertTrue(image.durationKnown)
        assertEquals(1, directory.imageCount())
        assertEquals(3, directory.metadataCount())
    }

    @Test
    fun `无图条目超过数量上限时淘汰最旧`() = runBlocking {
        var now = 1_000L
        val capped = ThumbnailCache(
            directory = directory,
            maxBytes = 1 shl 20,
            clock = { now },
            maxMetadataEntries = 3,
        )
        (1..5).forEach { index ->
            now += 10
            capped.saveWithoutImage("content://root/无图$index.mp3", durationMillis = index.toLong())
        }

        assertEquals(3, directory.metadataCount())
        assertNull(capped.entry("content://root/无图1.mp3"))
        assertNull(capped.entry("content://root/无图2.mp3"))
        assertNotNull(capped.entry("content://root/无图3.mp3"))
        assertNotNull(capped.entry("content://root/无图4.mp3"))
        assertNotNull(capped.entry("content://root/无图5.mp3"))
    }

    @Test
    fun `无图条目不占用图片字节上限`() = runBlocking {
        val capped = ThumbnailCache(
            directory = directory,
            maxBytes = 1,
            maxMetadataEntries = 100,
        )
        (1..10).forEach { index ->
            capped.saveWithoutImage("content://root/仅元数据$index.mp3", durationMillis = index.toLong())
        }

        assertEquals(10, directory.metadataCount())
        assertEquals(0, directory.imageCount())
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
    fun `补写时长不刷新元数据的最近使用时间`() = runBlocking {
        val key = "content://root/沿用时间.mkv"
        now = 900L
        cache.save(key, ByteArray(4))
        val image = cache.file(key)!!
        val metadata = File(image.parentFile, image.name + ".meta")
        metadata.setLastModified(1_000L)

        now = 1_200L
        cache.writeDuration(key, 187_000)

        assertEquals(1_000L, metadata.lastModified())
        assertEquals(187_000L, cache.entry(key)!!.durationMillis)
    }

    @Test
    fun `失败记录落盘后可读出失败时间与已试次数`() = runBlocking {
        cache.saveFailure("content://root/a.mkv", failedAtMillis = 5_000L, attempts = 2)

        val entry = cache.entry("content://root/a.mkv")

        assertNotNull(entry)
        assertNull(entry!!.file)
        assertTrue(!entry.noImage)
        assertEquals(5_000L, entry.failure!!.failedAtMillis)
        assertEquals(2, entry.failure!!.attempts)
    }

    @Test
    fun `失败记录在启动清理后保留`() = runBlocking {
        cache.saveFailure("content://root/a.mkv", failedAtMillis = 5_000L, attempts = 2)
        val reopened = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, clock = { now })

        assertNotNull(reopened.entry("content://root/a.mkv"))
    }

    @Test
    fun `成功封面覆盖失败记录`() = runBlocking {
        cache.saveFailure("content://root/a.mkv", failedAtMillis = 5_000L, attempts = 2)

        cache.save("content://root/a.mkv", ByteArray(4), durationMillis = 1_000)

        val entry = cache.entry("content://root/a.mkv")
        assertNotNull(entry!!.file)
        assertNull(entry.failure)
    }

    @Test
    fun `抽帧策略版本未变时启动清理保留既有条目`() = runBlocking {
        cache.save("content://root/a.mkv", ByteArray(4))

        val reopened = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, clock = { now })

        assertNotNull(reopened.entry("content://root/a.mkv"))
        assertEquals(1, directory.imageCount())
    }

    @Test
    fun `抽帧策略版本标记与当前版本不一致时清理旧版本的缓存文件`() = runBlocking {
        cache.save("content://root/a.mkv", ByteArray(4))
        cache.saveFailure("content://root/b.mkv", failedAtMillis = 5_000L, attempts = 2)
        File(directory, ".policy-version").writeText((THUMBNAIL_POLICY_VERSION - 1).toString())

        val upgraded = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, clock = { now })

        assertNull("旧版本的图片不再命中", upgraded.entry("content://root/a.mkv"))
        assertNull("旧版本的失败记录不再命中", upgraded.entry("content://root/b.mkv"))
        assertEquals("旧版本的图片文件被清掉", 0, directory.imageCount())
        assertEquals("旧版本的元数据被清掉", 0, directory.metadataCount())
    }

    @Test
    fun `版本标记缺失时按条目记下的版本清理，当前版本的文件保留`() = runBlocking {
        cache.save("content://root/a.mkv", ByteArray(4))
        cache.saveWithoutImage("content://root/b.mkv")
        cache.saveFailure("content://root/c.mkv", failedAtMillis = 5_000L, attempts = 2)
        // 旧版本写下的条目：文件名照旧，元数据里没有版本行。
        val retiredName = cacheName("content://root/d.mkv")
        File(directory, retiredName).writeBytes(ByteArray(2))
        File(directory, retiredName + ".meta").writeText("duration=unknown")
        File(directory, ".policy-version").delete()

        val reopened = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, clock = { now })

        assertNotNull("当前版本的图片保留", reopened.entry("content://root/a.mkv")?.file)
        assertNotNull("当前版本的无图记录保留", reopened.entry("content://root/b.mkv"))
        assertNotNull("当前版本的失败记录保留", reopened.entry("content://root/c.mkv"))
        assertNull("旧版本的条目不命中", reopened.entry("content://root/d.mkv"))
        assertEquals("只清掉旧版本的图片", 1, directory.imageCount())
        assertEquals("只清掉旧版本的元数据", 3, directory.metadataCount())
        assertEquals(
            "清理后补记当前版本",
            THUMBNAIL_POLICY_VERSION.toString(),
            File(directory, ".policy-version").readText(),
        )
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
