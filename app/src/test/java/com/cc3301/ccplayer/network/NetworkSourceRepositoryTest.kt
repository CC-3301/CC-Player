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

    private val nas = NetworkBookmark(
        protocol = SourceProtocol.Smb,
        name = "NAS",
        address = "192.168.1.10",
        path = "media",
        username = "cc",
    )
    private val dav = NetworkBookmark(
        protocol = SourceProtocol.WebDav,
        name = "",
        address = "https://dav.example.com",
        path = "dav/files/cc",
        username = "cc",
    )

    @Test
    fun `添加后书签出现在流里`() = runTest {
        sources.add(nas, password = "secret")

        assertEquals(listOf(nas), sources.bookmarks.first())
    }

    @Test
    fun `名称空时显示服务器地址`() {
        assertEquals("NAS", nas.displayName)
        assertEquals("https://dav.example.com", dav.displayName)
    }

    @Test
    fun `书签地址由协议、服务器地址与路径拼成`() {
        assertEquals("smb://192.168.1.10/media", nas.url)
        assertEquals("https://dav.example.com/dav/files/cc", dav.url)
    }

    @Test
    fun `同一地址重复添加只保留一条并更新名称`() = runTest {
        sources.add(nas, password = "secret")
        sources.add(nas.copy(name = "书房"), password = "secret2")

        assertEquals(listOf(nas.copy(name = "书房")), store.current())
    }

    @Test
    fun `密码按地址读写`() = runTest {
        sources.add(nas, password = "secret")

        assertEquals("secret", sources.password(nas.url))
        assertNull(sources.password("smb://192.168.1.10/other"))
    }

    @Test
    fun `密码不落在书签存储里`() = runTest {
        sources.add(nas, password = "secret")

        assertFalse(store.saved.toString().contains("secret"))
        assertEquals("secret", credentials.get(nas.url))
    }

    @Test
    fun `编辑改成新地址时旧凭据被移除`() = runTest {
        sources.add(nas, password = "secret")
        val renamed = nas.copy(address = "nas2")

        sources.update(previousUrl = nas.url, bookmark = renamed, password = "secret2")

        assertEquals(listOf(renamed), store.current())
        assertNull(sources.password(nas.url))
        assertEquals("secret2", sources.password(renamed.url))
    }

    @Test
    fun `编辑不换地址时只更新字段与密码`() = runTest {
        sources.add(nas, password = "secret")

        sources.update(previousUrl = nas.url, bookmark = nas.copy(name = "书房"), password = "new")

        assertEquals(listOf(nas.copy(name = "书房")), store.current())
        assertEquals("new", sources.password(nas.url))
    }

    @Test
    fun `删除书签同时清掉密码`() = runTest {
        sources.add(nas, password = "secret")

        sources.remove(nas.url)

        assertEquals(emptyList<NetworkBookmark>(), store.current())
        assertNull(sources.password(nas.url))
    }

    @Test
    fun `按位置找书签：子路径归属同一条，取最长匹配`() = runTest {
        val seriesRoot = nas.copy(path = "media/剧集")
        sources.add(nas, password = "secret")
        sources.add(seriesRoot, password = "secret")

        assertEquals(nas.url, sources.find("smb://192.168.1.10/media/movie.mkv")?.url)
        assertEquals(seriesRoot.url, sources.find("${seriesRoot.url}/第01集.mkv")?.url)
        assertEquals(nas.url, sources.find(nas.url)?.url)
        assertNull(sources.find("smb://192.168.1.10/media2/movie.mkv"))
    }
}
