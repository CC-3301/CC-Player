package com.cc3301.ccplayer.network

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkSourceRepositoryTest {

    private val store = FakeBookmarkStore()
    private val credentials = FakeCredentialStore()
    private val sources = NetworkSourceRepository(store, credentials)

    private val nas = "smb://192.168.1.10/media"

    @Test
    fun `添加后书签与地址都出现在流里`() = runTest {
        sources.add(url = nas, username = "cc", password = "secret")

        assertEquals(listOf(NetworkBookmark(url = nas, username = "cc")), sources.bookmarks.first())
        assertEquals(listOf(nas), sources.locations.first())
    }

    @Test
    fun `同一地址重复添加只保留一条并更新用户名`() = runTest {
        sources.add(url = nas, username = "cc", password = "secret")
        sources.add(url = nas, username = "other", password = "secret2")

        assertEquals(listOf(NetworkBookmark(url = nas, username = "other")), store.current())
    }

    @Test
    fun `密码按地址读写`() = runTest {
        sources.add(url = nas, username = "cc", password = "secret")

        assertEquals("secret", sources.password(nas))
        assertNull(sources.password("smb://192.168.1.10/other"))
    }

    @Test
    fun `密码不落在书签存储里`() = runTest {
        sources.add(url = nas, username = "cc", password = "secret")

        assertFalse(store.saved.toString().contains("secret"))
        assertEquals("secret", credentials.get(nas))
    }

    @Test
    fun `删除书签同时清掉密码`() = runTest {
        sources.add(url = nas, username = "cc", password = "secret")

        sources.remove(nas)

        assertEquals(emptyList<NetworkBookmark>(), store.current())
        assertNull(sources.password(nas))
    }

    @Test
    fun `按位置找书签：子路径归属同一条，取最长匹配`() = runTest {
        val seriesRoot = "smb://192.168.1.10/media/剧集"
        sources.add(url = nas, username = "cc", password = "secret")
        sources.add(url = seriesRoot, username = "cc", password = "secret")

        assertEquals(nas, sources.find("smb://192.168.1.10/media/movie.mkv")?.url)
        assertEquals(seriesRoot, sources.find("$seriesRoot/第01集.mkv")?.url)
        assertEquals(nas, sources.find(nas)?.url)
        assertNull(sources.find("smb://192.168.1.10/media2/movie.mkv"))
    }
}
