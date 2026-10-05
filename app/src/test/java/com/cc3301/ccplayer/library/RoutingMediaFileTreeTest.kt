package com.cc3301.ccplayer.library

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingMediaFileTreeTest {

    private val local = FakeMediaFileTree()
    private val network = FakeMediaFileTree()
    private val tree = RoutingMediaFileTree(mapOf("content" to local, "smb" to network))

    @Test
    fun `content 位置走本地文件树`() = runTest {
        val directory = testDirectory("媒体", "content://root/媒体")
        local.addDirectory(directory, testVideo("电影.mkv", "content://root/媒体/电影.mkv"))

        assertEquals(listOf("电影.mkv"), tree.children(directory).map { it.name })
    }

    @Test
    fun `smb 位置走网络文件树`() = runTest {
        val directory = testDirectory("media", "smb://nas/media")
        network.addDirectory(directory, testVideo("电影.mkv", "smb://nas/media/电影.mkv"))
        local.addDirectory(directory, testVideo("不该出现.mkv", "smb://nas/media/不该出现.mkv"))

        assertEquals(listOf("电影.mkv"), tree.children(directory).map { it.name })
    }

    @Test
    fun `解析按方案分派`() = runTest {
        val localRoot = testDirectory("媒体", "content://root/媒体")
        val networkRoot = testDirectory("media", "smb://nas/media")
        local.addDirectory(localRoot)
        network.addDirectory(networkRoot)

        assertEquals(localRoot, tree.resolve(localRoot.location))
        assertEquals(networkRoot, tree.resolve(networkRoot.location))
    }

    @Test
    fun `未知方案解析为空`() = runTest {
        assertNull(tree.resolve("ftp://nas/media"))
        assertNull(tree.resolve("root/媒体"))
    }

    @Test
    fun `未知方案读目录失败`() = runTest {
        assertTrue(runCatching { tree.children(testDirectory("media", "ftp://nas/media")) }.isFailure)
    }

    @Test
    fun `未知方案读目录给出找不到原因`() = runTest {
        val exception = runCatching { tree.children(testDirectory("media", "ftp://nas/media")) }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }
}
