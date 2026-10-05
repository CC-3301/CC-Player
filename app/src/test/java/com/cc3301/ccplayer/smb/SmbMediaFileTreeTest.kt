package com.cc3301.ccplayer.smb

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

class SmbMediaFileTreeTest {

    private val connector = FakeSmbConnector()
    private val sources = NetworkSourceRepository(FakeBookmarkStore(), FakeCredentialStore())
    private val tree = SmbMediaFileTree(connector, SmbLocator(sources))
    private val library = DirectoryMediaLibrary(tree)

    private val root = "smb://nas/media"

    @Test
    fun `目录子项映射成网络源条目`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        connector.addDirectory(
            "media",
            "",
            directoryEntry("剧集"),
            fileEntry("电影.mkv", size = 3_000_000, lastModified = 1_700_000_000_000),
        )

        val children = tree.children(MediaFile(name = "media", location = root, isDirectory = true, source = MediaSource.Network))

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
        connector.addDirectory("media", "剧集", fileEntry("第01集.mkv"))

        val children = tree.children(MediaFile(name = "剧集", location = "$root/剧集", isDirectory = true, source = MediaSource.Network))

        assertEquals(listOf("$root/剧集/第01集.mkv"), children.map { it.location })
    }

    @Test
    fun `书签带起始路径时共享内路径随之偏移`() = runTest {
        sources.add(url = "$root/剧集", username = "cc", password = "secret")
        connector.addDirectory("media", "剧集", fileEntry("第01集.mkv"))

        val children = tree.children(MediaFile(name = "剧集", location = "$root/剧集", isDirectory = true, source = MediaSource.Network))

        assertEquals(listOf("$root/剧集/第01集.mkv"), children.map { it.location })
    }

    @Test
    fun `读目录用书签里的用户名与密码`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")

        tree.children(MediaFile(name = "media", location = root, isDirectory = true, source = MediaSource.Network))

        assertEquals(listOf("cc" to "secret"), connector.logins)
        assertEquals(listOf("media"), connector.shares)
    }

    @Test
    fun `连接失败时抛出来源失败原因`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        connector.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")

        val result = library.list(MediaFile(name = "media", location = root, isDirectory = true, source = MediaSource.Network))

        assertTrue(result.isFailure)
        assertEquals(MediaFailure.Unreachable, (result.exceptionOrNull() as MediaSourceException).failure)
    }

    @Test
    fun `位置不属于任何书签时读目录失败`() = runTest {
        val result = library.list(MediaFile(name = "media", location = root, isDirectory = true, source = MediaSource.Network))

        assertTrue(result.isFailure)
        assertEquals(MediaFailure.NotFound, (result.exceptionOrNull() as MediaSourceException).failure)
    }

    @Test
    fun `非媒体文件被过滤而子目录保留`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        connector.addDirectory(
            "media",
            "",
            directoryEntry("剧集"),
            fileEntry("电影.mkv"),
            fileEntry("封面.jpg"),
            fileEntry("字幕.srt"),
        )

        val entries = library.list(MediaFile(name = "media", location = root, isDirectory = true, source = MediaSource.Network)).getOrThrow()

        assertEquals(listOf("剧集", "电影.mkv"), entries.map { it.name })
    }

    @Test
    fun `解析共享根得到书签指向的目录`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")

        assertEquals(MediaFile(name = "media", location = root, isDirectory = true, source = MediaSource.Network), tree.resolve(root))
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
    fun `共享连不上时根目录仍然可见，读目录才报失败`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        connector.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")

        val resolved = tree.resolve(root)
        val result = library.list(resolved!!)

        assertEquals("media", resolved.name)
        assertTrue(result.isFailure)
        assertEquals(MediaFailure.Unreachable, (result.exceptionOrNull() as MediaSourceException).failure)
    }
}
