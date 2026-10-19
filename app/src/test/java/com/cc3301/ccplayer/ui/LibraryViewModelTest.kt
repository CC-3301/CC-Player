package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.DirectoryMediaLibrary
import com.cc3301.ccplayer.library.FakeMediaFileTree
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.testDirectory
import com.cc3301.ccplayer.library.testDocument
import com.cc3301.ccplayer.library.testVideo
import com.cc3301.ccplayer.network.FakeBookmarkStore
import com.cc3301.ccplayer.network.FakeCredentialStore
import com.cc3301.ccplayer.network.NetworkBookmark
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.network.NetworkSourceVerifier
import com.cc3301.ccplayer.network.SourceProtocol
import com.cc3301.ccplayer.settings.FakePreferencesDataStore
import com.cc3301.ccplayer.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 记录校验调用并可按需失败的连通性校验。 */
private class RecordingVerifier : NetworkSourceVerifier {

    var failure: MediaSourceException? = null
    val attempts = mutableListOf<Pair<String, String>>()

    override suspend fun verify(url: String, username: String, password: String) {
        attempts += url to username
        failure?.let { throw it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val tree = FakeMediaFileTree()
    private val settings = SettingsRepository(FakePreferencesDataStore())
    private val store = FakeBookmarkStore()
    private val credentials = FakeCredentialStore()
    private val verifier = RecordingVerifier()
    private val sources = NetworkSourceRepository(store, credentials)

    private val localRoot = testDirectory("媒体", "content://root/媒体")
    private val smbRoot = testDirectory("media", "smb://nas/media")
    private val webDavRoot = testDirectory("cc", "https://dav.example.com/dav/files/cc")

    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = LibraryViewModel(DirectoryMediaLibrary(tree), tree, settings, sources, verifier)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `本地连接源显示文件夹名且不可编辑`() = runTest {
        tree.addDirectory(localRoot)

        viewModel.addLocalRoot(localRoot.location)
        advanceUntilIdle()

        val source = viewModel.sourcesState.value.local.single()
        assertEquals("媒体", source.name)
        assertEquals(localRoot.location, source.location)
        assertFalse(source.editable)
    }

    @Test
    fun `删除本地连接源只移除记录`() = runTest {
        tree.addDirectory(localRoot)
        viewModel.addLocalRoot(localRoot.location)
        advanceUntilIdle()

        viewModel.removeSource(viewModel.sourcesState.value.local.single())
        advanceUntilIdle()

        assertEquals(emptyList<ConnectionSource>(), viewModel.sourcesState.value.local)
        assertEquals(emptyList<String>(), settings.rootLocations.first())
    }

    @Test
    fun `本地连接源不打开编辑表单`() = runTest {
        tree.addDirectory(localRoot)
        viewModel.addLocalRoot(localRoot.location)
        advanceUntilIdle()

        viewModel.openEditSourceForm(viewModel.sourcesState.value.local.single())
        advanceUntilIdle()

        assertNull(viewModel.sourcesState.value.form)
    }

    @Test
    fun `添加 SMB 源：默认端口、名称回退、保存后立即出现在列表`() = runTest {
        tree.addDirectory(smbRoot)

        openSmbForm(name = "", address = "nas", path = "media", username = "cc", password = "secret")
        advanceUntilIdle()

        assertEquals(listOf("smb://nas/media" to "cc"), verifier.attempts)
        val source = viewModel.sourcesState.value.smb.single()
        assertEquals("nas", source.name)
        assertEquals("smb://nas/media", source.location)
        assertEquals(445, com.cc3301.ccplayer.smb.SmbLocation.parse(source.location)?.port)
        assertNull(viewModel.sourcesState.value.form)
    }

    @Test
    fun `添加 SMB 源保留填写的名称`() = runTest {
        tree.addDirectory(smbRoot)

        openSmbForm(name = "书房 NAS", address = "nas", path = "media")
        advanceUntilIdle()

        assertEquals("书房 NAS", viewModel.sourcesState.value.smb.single().name)
    }

    @Test
    fun `SMB 路径为空时源的根是服务器地址`() = runTest {
        tree.addDirectory(testDirectory("nas", "smb://nas"))

        openSmbForm(name = "NAS", address = "nas", path = "")
        advanceUntilIdle()

        assertEquals(listOf("smb://nas" to ""), verifier.attempts)
        assertEquals("smb://nas", viewModel.sourcesState.value.smb.single().location)
    }

    @Test
    fun `添加 WebDAV 源：起始目录拼进源地址`() = runTest {
        tree.addDirectory(webDavRoot)

        openWebDavForm(name = "", address = "https://dav.example.com", path = "/dav/files/cc/")
        advanceUntilIdle()

        assertEquals(listOf("https://dav.example.com/dav/files/cc" to ""), verifier.attempts)
        val source = viewModel.sourcesState.value.webDav.single()
        assertEquals("https://dav.example.com", source.name)
        assertEquals("https://dav.example.com/dav/files/cc", source.location)
    }

    @Test
    fun `WebDAV 地址不以 http 或 https 开头时保留表单并标错`() = runTest {
        openWebDavForm(address = "dav.example.com/dav")

        advanceUntilIdle()

        assertEquals(emptyList<String>(), verifier.attempts)
        assertEquals(emptyList<NetworkBookmark>(), store.current())
        assertTrue(viewModel.sourcesState.value.form?.addressInvalid == true)
        assertEquals(SourceProtocol.WebDav, viewModel.sourcesState.value.form?.protocol)
    }

    @Test
    fun `校验失败时不保存并保留表单与原因`() = runTest {
        verifier.failure = MediaSourceException(MediaFailure.Authentication, "拒绝登录")

        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()

        assertEquals(emptyList<NetworkBookmark>(), store.current())
        assertEquals(MediaFailure.Authentication, viewModel.sourcesState.value.form?.failure)
        assertFalse(viewModel.sourcesState.value.form?.saving == true)
    }

    @Test
    fun `编辑表单带出已保存字段与密码`() = runTest {
        tree.addDirectory(smbRoot)
        openSmbForm(name = "NAS", address = "nas", path = "media", username = "cc", password = "secret")
        advanceUntilIdle()

        viewModel.openEditSourceForm(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()

        val form = viewModel.sourcesState.value.form
        assertEquals(SourceProtocol.Smb, form?.protocol)
        assertTrue(form?.editing == true)
        assertEquals("NAS", form?.name)
        assertEquals("nas", form?.address)
        assertEquals("media", form?.path)
        assertEquals("cc", form?.username)
        assertEquals("secret", form?.password)
    }

    @Test
    fun `编辑保存后列表立即更新`() = runTest {
        tree.addDirectory(smbRoot)
        openSmbForm(name = "NAS", address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openEditSourceForm(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()

        viewModel.updateFormField(NetworkSourceFieldKind.Name, "书房")
        viewModel.saveNetworkSourceForm()
        advanceUntilIdle()

        assertEquals("书房", viewModel.sourcesState.value.smb.single().name)
        assertNull(viewModel.sourcesState.value.form)
    }

    @Test
    fun `删除网络源后列表与书签都移除`() = runTest {
        tree.addDirectory(smbRoot)
        openSmbForm(address = "nas", path = "media", password = "secret")
        advanceUntilIdle()

        viewModel.removeSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()

        assertEquals(emptyList<ConnectionSource>(), viewModel.sourcesState.value.smb)
        assertEquals(emptyList<NetworkBookmark>(), store.current())
        assertNull(sources.password("smb://nas/media"))
    }

    @Test
    fun `点击连接源进入库页面，根目录为连接源的根`() = runTest {
        tree.addDirectory(smbRoot, testVideo("电影.mkv", "smb://nas/media/电影.mkv"))
        openSmbForm(name = "NAS", address = "nas", path = "media")
        advanceUntilIdle()

        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()

        assertEquals(listOf("NAS"), viewModel.state.value.stack.map { it.name })
        assertEquals("smb://nas/media", viewModel.state.value.currentDirectory?.location)
        assertEquals(listOf("电影.mkv"), viewModel.state.value.entries.map { it.name })
    }

    @Test
    fun `进入子目录后返回上一级`() = runTest {
        val series = testDirectory("剧集", "smb://nas/media/剧集")
        tree.addDirectory(smbRoot, series)
        tree.addDirectory(series, testVideo("第01集.mkv", "smb://nas/media/剧集/第01集.mkv"))
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()

        viewModel.enter(series)
        advanceUntilIdle()
        assertEquals(listOf("第01集.mkv"), viewModel.state.value.entries.map { it.name })

        viewModel.navigateUp()
        advanceUntilIdle()
        assertEquals("smb://nas/media", viewModel.state.value.currentDirectory?.location)
        assertEquals(listOf("剧集"), viewModel.state.value.entries.map { it.name })
    }

    @Test
    fun `停在连接源根目录时返回不动作`() = runTest {
        tree.addDirectory(smbRoot)
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()

        viewModel.navigateUp()
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.stack.size)
    }

    @Test
    fun `表单关掉后不再显示`() = runTest {
        viewModel.openNewSourceForm(SourceProtocol.WebDav)

        viewModel.dismissNetworkSourceForm()
        advanceUntilIdle()

        assertNull(viewModel.sourcesState.value.form)
    }

    @Test
    fun `离开源列表时清空表单`() = runTest {
        viewModel.openNewSourceForm(SourceProtocol.WebDav)

        viewModel.clearNetworkSourceForm()

        assertNull(viewModel.sourcesState.value.form)
    }

    @Test
    fun `本地授权失效的源仍然列出并可删除`() = runTest {
        settings.addRoot("content://tree/lost")
        advanceUntilIdle()

        val source = viewModel.sourcesState.value.local.single()
        assertEquals("lost", source.name)
        assertFalse(source.editable)

        viewModel.removeSource(source)
        advanceUntilIdle()

        assertEquals(emptyList<ConnectionSource>(), viewModel.sourcesState.value.local)
        assertEquals(emptyList<String>(), settings.rootLocations.first())
    }

    @Test
    fun `编辑成已存在地址时保留表单并拒绝保存`() = runTest {
        tree.addDirectory(smbRoot)
        tree.addDirectory(testDirectory("media", "smb://nas2/media"))
        openSmbForm(name = "书房", address = "nas", path = "media")
        advanceUntilIdle()
        openSmbForm(name = "客厅", address = "nas2", path = "media")
        advanceUntilIdle()
        viewModel.openEditSourceForm(viewModel.sourcesState.value.smb.first { it.name == "书房" })
        advanceUntilIdle()

        viewModel.updateFormField(NetworkSourceFieldKind.Address, "nas2")
        viewModel.saveNetworkSourceForm()
        advanceUntilIdle()

        assertTrue(viewModel.sourcesState.value.form?.duplicateAddress == true)
        assertEquals(listOf("书房", "客厅"), viewModel.sourcesState.value.smb.map { it.name })
        assertEquals(2, store.current().size)
    }

    @Test
    fun `网络目录读不到时状态带来源失败原因`() = runTest {
        val series = testDirectory("剧集", "smb://nas/media/剧集")
        tree.addDirectory(smbRoot, series)
        tree.markFailing(series.location, MediaSourceException(MediaFailure.Unreachable, "断线"))

        viewModel.enter(series)
        advanceUntilIdle()

        assertEquals(MediaFailure.Unreachable, viewModel.state.value.failure)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun `本地目录读取失败没有来源原因时归为未知`() = runTest {
        val local = testDirectory("媒体", "content://root/媒体")
        tree.addDirectory(local)
        tree.markUnreadable(local.location)

        viewModel.enter(local)
        advanceUntilIdle()

        assertEquals(MediaFailure.Unknown, viewModel.state.value.failure)
    }

    @Test
    fun `当前目录的原始条目含被媒体列表过滤掉的字幕文件`() = runTest {
        val local = testDirectory("媒体", "content://root/媒体")
        tree.addDirectory(
            local,
            testVideo("ep01.mkv", "content://root/媒体/ep01.mkv"),
            testDocument("ep01.chs.ass", "content://root/媒体/ep01.chs.ass"),
        )

        viewModel.enter(local)
        advanceUntilIdle()

        assertEquals(listOf("ep01.mkv", "ep01.chs.ass"), viewModel.directoryEntries().map { it.name })
        assertEquals(listOf("ep01.mkv"), viewModel.state.value.entries.map { it.name })
    }

    @Test
    fun `停在根目录或目录读不出来时原始条目为空`() = runTest {
        assertEquals(emptyList<MediaFile>(), viewModel.directoryEntries())

        val local = testDirectory("媒体", "content://root/媒体")
        tree.addDirectory(local).markUnreadable(local.location)
        viewModel.enter(local)
        advanceUntilIdle()

        assertEquals(emptyList<MediaFile>(), viewModel.directoryEntries())
    }

    private fun openSmbForm(
        name: String = "",
        address: String,
        path: String = "",
        username: String = "",
        password: String = "",
    ) {
        viewModel.openNewSourceForm(SourceProtocol.Smb)
        fillForm(name, address, path, username, password)
        viewModel.saveNetworkSourceForm()
    }

    private fun openWebDavForm(
        name: String = "",
        address: String,
        path: String = "",
        username: String = "",
        password: String = "",
    ) {
        viewModel.openNewSourceForm(SourceProtocol.WebDav)
        fillForm(name, address, path, username, password)
        viewModel.saveNetworkSourceForm()
    }

    private fun fillForm(name: String, address: String, path: String, username: String, password: String) {
        viewModel.updateFormField(NetworkSourceFieldKind.Name, name)
        viewModel.updateFormField(NetworkSourceFieldKind.Address, address)
        viewModel.updateFormField(NetworkSourceFieldKind.Path, path)
        viewModel.updateFormField(NetworkSourceFieldKind.Username, username)
        viewModel.updateFormField(NetworkSourceFieldKind.Password, password)
    }
}
