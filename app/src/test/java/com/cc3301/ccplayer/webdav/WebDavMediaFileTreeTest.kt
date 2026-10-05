package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.DirectoryMediaLibrary
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.FakeBookmarkStore
import com.cc3301.ccplayer.network.FakeCredentialStore
import com.cc3301.ccplayer.network.NetworkSourceRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavMediaFileTreeTest {

    private val transport = FakeWebDavTransport()
    private val sources = NetworkSourceRepository(FakeBookmarkStore(), FakeCredentialStore())
    private val tree = WebDavMediaFileTree(transport, WebDavLocator(sources))
    private val library = DirectoryMediaLibrary(tree)

    private val root = "https://dav.example.com/remote.php/dav/files/cc"

    private fun directory(name: String, location: String) =
        MediaFile(name = name, location = location, isDirectory = true, source = MediaSource.Network)

    @Test
    fun `目录子项映射成网络源条目`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        transport.addDirectory(
            root,
            webDavDirectory("剧集"),
            webDavFile("电影.mkv", size = 3_000_000, lastModified = 1_700_000_000_000),
        )

        val children = tree.children(directory("cc", root))

        assertEquals(
            listOf(
                MediaFile(name = "剧集", location = "$root/剧集", size = 0, lastModified = 0, isDirectory = true, source = MediaSource.Network),
                MediaFile(name = "电影.mkv", location = "$root/电影.mkv", size = 3_000_000, lastModified = 1_700_000_000_000, source = MediaSource.Network),
            ),
            children,
        )
    }

    @Test
    fun `深层目录的子项位置带上级路径`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        transport.addDirectory("$root/剧集", webDavFile("第01集.mkv"))

        val children = tree.children(directory("剧集", "$root/剧集"))

        assertEquals(listOf("$root/剧集/第01集.mkv"), children.map { it.location })
    }

    @Test
    fun `书签带起始路径时源内路径随之偏移`() = runTest {
        sources.add(url = "$root/剧集", username = "cc", password = "secret")
        transport.addDirectory("$root/剧集", webDavFile("第01集.mkv"))

        val children = tree.children(directory("剧集", "$root/剧集"))

        assertEquals(listOf("$root/剧集/第01集.mkv"), children.map { it.location })
    }

    @Test
    fun `读目录用书签里的用户名、密码与地址`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")

        tree.children(directory("cc", root))

        assertEquals(listOf("cc" to "secret"), transport.logins)
        assertEquals(listOf(root), transport.listings)
    }

    @Test
    fun `连接失败时抛出来源失败原因`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        transport.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")

        val result = library.list(directory("cc", root))

        assertTrue(result.isFailure)
        assertEquals(MediaFailure.Unreachable, (result.exceptionOrNull() as MediaSourceException).failure)
    }

    @Test
    fun `位置不属于任何书签时读目录失败`() = runTest {
        val result = library.list(directory("cc", root))

        assertTrue(result.isFailure)
        assertEquals(MediaFailure.NotFound, (result.exceptionOrNull() as MediaSourceException).failure)
    }

    @Test
    fun `非媒体文件被过滤而子目录保留`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        transport.addDirectory(
            root,
            webDavDirectory("剧集"),
            webDavFile("电影.mkv"),
            webDavFile("封面.jpg"),
            webDavFile("字幕.srt"),
        )

        val entries = library.list(directory("cc", root)).getOrThrow()

        assertEquals(listOf("剧集", "电影.mkv"), entries.map { it.name })
    }

    @Test
    fun `解析源根得到书签指向的目录`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")

        assertEquals(directory("dav.example.com/remote.php/dav/files/cc", root), tree.resolve(root))
    }

    @Test
    fun `解析子目录得到带名字的目录条目`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")

        val resolved = tree.resolve("$root/剧集/第二季")

        assertEquals("第二季", resolved?.name)
        assertEquals(true, resolved?.isDirectory)
    }

    @Test
    fun `位置不属于任何书签时解析返回空`() = runTest {
        assertNull(tree.resolve(root))
        assertNull(tree.resolve("content://root/媒体"))
    }

    @Test
    fun `服务器连不上时根目录仍然可见，读目录才报失败`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        transport.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")

        val resolved = tree.resolve(root)
        val result = library.list(resolved!!)

        assertEquals("dav.example.com/remote.php/dav/files/cc", resolved.name)
        assertTrue(result.isFailure)
        assertEquals(MediaFailure.Unreachable, (result.exceptionOrNull() as MediaSourceException).failure)
    }
}
