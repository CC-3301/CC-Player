package com.cc3301.ccplayer.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.DataStoreDirectoryPositionStore
import com.cc3301.ccplayer.library.DirectoryPosition
import com.cc3301.ccplayer.library.DirectoryPositionStore
import com.cc3301.ccplayer.library.DirectoryScrollTarget
import com.cc3301.ccplayer.library.DirectoryMediaLibrary
import com.cc3301.ccplayer.library.FakeMediaFileTree
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaLibrary
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.SortOrder
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
import com.cc3301.ccplayer.settings.ViewMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

/** 偏好读取被闸门拦住，用于观察目录先加载、偏好后到的时序。 */
private class GatedPreferencesDataStore(initial: Preferences) : DataStore<Preferences> {

    private val delegate = FakePreferencesDataStore(initial)

    val gate = CompletableDeferred<Unit>()

    override val data: Flow<Preferences> = flow {
        gate.await()
        emitAll(delegate.data)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        delegate.updateData(transform)
}

/** 记录校验调用并可按需失败的连通性校验。 */
private class RecordingVerifier : NetworkSourceVerifier {

    var failure: MediaSourceException? = null
    val attempts = mutableListOf<Pair<String, String>>()

    override suspend fun verify(url: String, username: String, password: String) {
        attempts += url to username
        failure?.let { throw it }
    }
}

/** 目录列举被闸门拦住，用于观察加载中的状态。 */
private class GatedMediaLibrary(private val delegate: MediaLibrary) : MediaLibrary {

    var gate = CompletableDeferred<Unit>()
    var completedLists = 0

    override suspend fun resolveDirectory(location: String): MediaFile? = delegate.resolveDirectory(location)

    override suspend fun list(directory: MediaFile): Result<List<MediaFile>> {
        gate.await()
        completedLists += 1
        return delegate.list(directory)
    }
}

/** 记录实际列举过的目录，用于确认恢复时不会重复列举当前目录。 */
private class CountingMediaLibrary(private val delegate: MediaLibrary) : MediaLibrary {

    val listCalls = mutableListOf<String>()

    override suspend fun resolveDirectory(location: String): MediaFile? = delegate.resolveDirectory(location)

    override suspend fun list(directory: MediaFile): Result<List<MediaFile>> {
        listCalls += directory.location
        return delegate.list(directory)
    }
}

/** 前若干次列举失败，之后交给真实文件树；用于观察恢复读取失败后的重试。 */
private class FailTimesMediaLibrary(
    private val delegate: MediaLibrary,
    private val failures: Int,
) : MediaLibrary {

    private var failed = 0

    override suspend fun resolveDirectory(location: String): MediaFile? = delegate.resolveDirectory(location)

    override suspend fun list(directory: MediaFile): Result<List<MediaFile>> {
        if (failed < failures) {
            failed += 1
            return Result.failure(MediaSourceException(MediaFailure.Unreachable, "断线"))
        }
        return delegate.list(directory)
    }
}

/** 位置记录读取失败。 */
private class FailingDirectoryPositionStore : DirectoryPositionStore {

    override suspend fun load(sourceLocation: String, relativePath: String): DirectoryPosition? =
        throw IllegalStateException("位置记录读不了")

    override suspend fun save(sourceLocation: String, relativePath: String, position: DirectoryPosition) = Unit

    override suspend fun clearSource(sourceLocation: String) = Unit
}

/** 清理位置记录被闸门拦住，用于观察删除顺序。 */
private class GatedDirectoryPositionStore : DirectoryPositionStore {

    val clearGate = CompletableDeferred<Unit>()

    override suspend fun load(sourceLocation: String, relativePath: String): DirectoryPosition? = null

    override suspend fun save(sourceLocation: String, relativePath: String, position: DirectoryPosition) = Unit

    override suspend fun clearSource(sourceLocation: String) {
        clearGate.await()
    }
}

/** 记录导航恢复日志的测试出口。 */
private class RecordingLibraryDiagnostics : DiagnosticsLog {
    override val enabled: Boolean = true
    val messages = mutableListOf<String>()

    override fun log(message: String) {
        messages += message
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
    private val positions = DataStoreDirectoryPositionStore(FakePreferencesDataStore())

    private val localRoot = testDirectory("媒体", "content://root/媒体")
    private val smbRoot = testDirectory("media", "smb://nas/media")
    private val webDavRoot = testDirectory("cc", "https://dav.example.com/dav/files/cc")

    private val savedState = SavedStateHandle()
    private val snapshots = InMemoryNavigationSnapshotStore()

    private lateinit var navigation: NavigationViewModel

    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        navigation = NavigationViewModel(savedState)
        viewModel = createViewModel()
    }

    private fun createViewModel(
        library: MediaLibrary = DirectoryMediaLibrary(tree),
        positionStore: DirectoryPositionStore = positions,
        navigation: NavigationViewModel = this.navigation,
        settings: SettingsRepository = this.settings,
        snapshots: NavigationSnapshotStore = this.snapshots,
        diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    ): LibraryViewModel = LibraryViewModel(
        library = library,
        files = tree,
        settings = settings,
        sources = sources,
        verifier = verifier,
        positions = positionStore,
        navigation = navigation,
        snapshots = snapshots,
        diagnostics = diagnostics,
    )

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

    @Test
    fun `重新进入同一目录时恢复第一个可见条目与偏移`() = runTest {
        tree.addDirectory(
            smbRoot,
            testVideo("a.mkv", "smb://nas/media/a.mkv"),
            testVideo("b.mkv", "smb://nas/media/b.mkv"),
        )
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()

        viewModel.savePosition("smb://nas/media", "", "smb://nas/media/b.mkv", 48)
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 1, offset = 48), viewModel.state.value.scrollTarget)
    }

    @Test
    fun `不同连接源与不同目录分别记录位置`() = runTest {
        val series = testDirectory("剧集", "smb://nas/media/剧集")
        tree.addDirectory(
            smbRoot,
            testVideo("a.mkv", "smb://nas/media/a.mkv"),
            series,
        )
        tree.addDirectory(series, testVideo("第01集.mkv", "smb://nas/media/剧集/第01集.mkv"))
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        val source = viewModel.sourcesState.value.smb.single()
        viewModel.openSource(source)
        advanceUntilIdle()

        viewModel.savePosition("smb://nas/media", "", "smb://nas/media/a.mkv", 20)
        viewModel.enter(series)
        advanceUntilIdle()
        viewModel.savePosition("smb://nas/media", "剧集", "smb://nas/media/剧集/第01集.mkv", 72)
        advanceUntilIdle()

        viewModel.openSource(source)
        advanceUntilIdle()
        assertEquals(DirectoryScrollTarget(index = 1, offset = 20), viewModel.state.value.scrollTarget)

        viewModel.enter(series)
        advanceUntilIdle()
        assertEquals(DirectoryScrollTarget(index = 0, offset = 72), viewModel.state.value.scrollTarget)
    }

    @Test
    fun `关闭位置记忆后进入目录从顶部开始`() = runTest {
        tree.addDirectory(smbRoot, testVideo("b.mkv", "smb://nas/media/b.mkv"))
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        val source = viewModel.sourcesState.value.smb.single()
        viewModel.openSource(source)
        advanceUntilIdle()
        viewModel.savePosition("smb://nas/media", "", "smb://nas/media/b.mkv", 64)
        advanceUntilIdle()

        settings.setAutoRestorePosition(false)
        advanceUntilIdle()
        viewModel.openSource(source)
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), viewModel.state.value.scrollTarget)
    }

    @Test
    fun `记录条目不存在时停在末尾`() = runTest {
        tree.addDirectory(
            smbRoot,
            testVideo("a.mkv", "smb://nas/media/a.mkv"),
            testVideo("b.mkv", "smb://nas/media/b.mkv"),
        )
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        val source = viewModel.sourcesState.value.smb.single()
        viewModel.openSource(source)
        advanceUntilIdle()
        positions.save(smbRoot.location, "", DirectoryPosition("smb://nas/media/已删除.mkv", 16))
        advanceUntilIdle()

        viewModel.openSource(source)
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 1, offset = 0), viewModel.state.value.scrollTarget)
    }

    @Test
    fun `切换排序回到顶部且不删除位置记录`() = runTest {
        tree.addDirectory(smbRoot, testVideo("a.mkv", "smb://nas/media/a.mkv"))
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()
        viewModel.savePosition("smb://nas/media", "", "smb://nas/media/a.mkv", 24)
        advanceUntilIdle()

        settings.setSortOrder(SortOrder.NameDescending)
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), viewModel.state.value.scrollTarget)
        assertEquals(
            "smb://nas/media/a.mkv",
            positions.load("smb://nas/media", "")?.entryLocation,
        )
    }

    @Test
    fun `关闭位置记忆期间浏览继续更新位置记录`() = runTest {
        tree.addDirectory(
            smbRoot,
            testVideo("a.mkv", "smb://nas/media/a.mkv"),
            testVideo("b.mkv", "smb://nas/media/b.mkv"),
        )
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()
        viewModel.savePosition("smb://nas/media", "", "smb://nas/media/a.mkv", 20)
        advanceUntilIdle()

        settings.setAutoRestorePosition(false)
        advanceUntilIdle()
        viewModel.savePosition("smb://nas/media", "", "smb://nas/media/b.mkv", 60)
        advanceUntilIdle()

        val saved = positions.load("smb://nas/media", "")
        assertEquals("smb://nas/media/b.mkv", saved?.entryLocation)
        assertEquals(60, saved?.scrollOffset)
    }

    @Test
    fun `目录先加载且偏好后到关闭时按开关重算`() = runTest {
        val seedStore = FakePreferencesDataStore()
        SettingsRepository(seedStore).setAutoRestorePosition(false)
        val gatedStore = GatedPreferencesDataStore(seedStore.data.first())
        val gatedViewModel = createViewModel(
            settings = SettingsRepository(gatedStore),
            navigation = NavigationViewModel(),
        )
        tree.addDirectory(
            smbRoot,
            testVideo("a.mkv", "smb://nas/media/a.mkv"),
            testVideo("b.mkv", "smb://nas/media/b.mkv"),
        )
        positions.save(smbRoot.location, "", DirectoryPosition("smb://nas/media/b.mkv", 40))
        val source = ConnectionSource(SourceProtocol.Smb, "NAS", smbRoot.location, smbRoot)

        gatedViewModel.openSource(source)
        advanceUntilIdle()
        assertEquals(DirectoryScrollTarget(index = 1, offset = 40), gatedViewModel.state.value.scrollTarget)

        gatedStore.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), gatedViewModel.state.value.scrollTarget)
    }

    @Test
    fun `排序与视图偏好晚到时按新次序定位`() = runTest {
        val seedStore = FakePreferencesDataStore()
        val seedSettings = SettingsRepository(seedStore)
        seedSettings.setSortOrder(SortOrder.NameDescending)
        seedSettings.setView(ViewMode.Grid, 3)
        val gatedStore = GatedPreferencesDataStore(seedStore.data.first())
        val gatedViewModel = createViewModel(
            settings = SettingsRepository(gatedStore),
            navigation = NavigationViewModel(),
        )
        tree.addDirectory(
            smbRoot,
            testVideo("ep10.mkv", "smb://nas/media/ep10.mkv"),
            testVideo("ep2.mkv", "smb://nas/media/ep2.mkv"),
        )
        positions.save(smbRoot.location, "", DirectoryPosition("smb://nas/media/ep2.mkv", 8))
        val source = ConnectionSource(SourceProtocol.Smb, "NAS", smbRoot.location, smbRoot)

        gatedViewModel.openSource(source)
        advanceUntilIdle()
        assertEquals(DirectoryScrollTarget(index = 0, offset = 8), gatedViewModel.state.value.scrollTarget)

        gatedStore.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 1, offset = 8), gatedViewModel.state.value.scrollTarget)
    }

    @Test
    fun `空目录、读取失败或条目不在列表时不写位置`() = runTest {
        val emptyRoot = testDirectory("空", "smb://nas/empty")
        tree.addDirectory(emptyRoot)
        val emptyViewModel = createViewModel(navigation = NavigationViewModel())
        emptyViewModel.openSource(ConnectionSource(SourceProtocol.Smb, "空", emptyRoot.location, emptyRoot))
        advanceUntilIdle()
        emptyViewModel.savePosition(emptyRoot.location, "", "smb://nas/empty/x.mkv", 10)
        advanceUntilIdle()
        assertNull(positions.load(emptyRoot.location, ""))

        val failingRoot = testDirectory("失败", "smb://nas/failing")
        tree.addDirectory(failingRoot, testVideo("a.mkv", "smb://nas/failing/a.mkv"))
        tree.markUnreadable(failingRoot.location)
        val failingViewModel = createViewModel(navigation = NavigationViewModel())
        failingViewModel.openSource(ConnectionSource(SourceProtocol.Smb, "失败", failingRoot.location, failingRoot))
        advanceUntilIdle()
        failingViewModel.savePosition(failingRoot.location, "", "smb://nas/failing/a.mkv", 10)
        advanceUntilIdle()
        assertNull(positions.load(failingRoot.location, ""))

        val normalRoot = testDirectory("正常", "smb://nas/normal")
        tree.addDirectory(normalRoot, testVideo("a.mkv", "smb://nas/normal/a.mkv"))
        val normalViewModel = createViewModel(navigation = NavigationViewModel())
        normalViewModel.openSource(ConnectionSource(SourceProtocol.Smb, "正常", normalRoot.location, normalRoot))
        advanceUntilIdle()
        normalViewModel.savePosition(normalRoot.location, "", "smb://nas/normal/不存在.mkv", 10)
        advanceUntilIdle()
        assertNull(positions.load(normalRoot.location, ""))

        normalViewModel.savePosition(normalRoot.location, "", "smb://nas/normal/a.mkv", 10)
        advanceUntilIdle()
        assertEquals("smb://nas/normal/a.mkv", positions.load(normalRoot.location, "")?.entryLocation)
    }

    @Test
    fun `删除连接源清理其目录位置记录`() = runTest {
        tree.addDirectory(smbRoot, testVideo("a.mkv", "smb://nas/media/a.mkv"))
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        val source = viewModel.sourcesState.value.smb.single()
        viewModel.openSource(source)
        advanceUntilIdle()
        viewModel.savePosition("smb://nas/media", "", "smb://nas/media/a.mkv", 12)
        advanceUntilIdle()

        viewModel.removeSource(source)
        advanceUntilIdle()

        assertNull(positions.load("smb://nas/media", ""))
    }

    @Test
    fun `目录加载中不保存位置也不保留旧条目`() = runTest {
        val series = testDirectory("剧集", "smb://nas/media/剧集")
        tree.addDirectory(smbRoot, testVideo("a.mkv", "smb://nas/media/a.mkv"), series)
        tree.addDirectory(series, testVideo("第01集.mkv", "smb://nas/media/剧集/第01集.mkv"))
        val gated = GatedMediaLibrary(DirectoryMediaLibrary(tree))
        val gatedViewModel = LibraryViewModel(gated, tree, settings, sources, verifier, positions, navigation)
        val source = ConnectionSource(SourceProtocol.Smb, "NAS", smbRoot.location, smbRoot)

        gatedViewModel.openSource(source)
        advanceUntilIdle()

        assertTrue(gatedViewModel.state.value.loading)
        assertEquals(emptyList<MediaFile>(), gatedViewModel.state.value.entries)
        gatedViewModel.savePosition(smbRoot.location, "", "smb://nas/media/a.mkv", 10)
        advanceUntilIdle()
        assertNull(positions.load(smbRoot.location, ""))

        gated.gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(gatedViewModel.state.value.loading)
        assertTrue(gatedViewModel.state.value.entries.isNotEmpty())
    }

    @Test
    fun `进入子目录时旧条目立即清空`() = runTest {
        val series = testDirectory("剧集", "smb://nas/media/剧集")
        tree.addDirectory(smbRoot, series)
        tree.addDirectory(series, testVideo("第01集.mkv", "smb://nas/media/剧集/第01集.mkv"))
        val gated = GatedMediaLibrary(DirectoryMediaLibrary(tree))
        val gatedViewModel = LibraryViewModel(gated, tree, settings, sources, verifier, positions, navigation)
        val source = ConnectionSource(SourceProtocol.Smb, "NAS", smbRoot.location, smbRoot)
        gatedViewModel.openSource(source)
        gated.gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(gatedViewModel.state.value.loading)

        gated.gate = CompletableDeferred()
        gatedViewModel.enter(series)
        advanceUntilIdle()

        assertTrue(gatedViewModel.state.value.loading)
        assertEquals(emptyList<MediaFile>(), gatedViewModel.state.value.entries)
    }

    @Test
    fun `位置记录读取失败时按无记录继续加载`() = runTest {
        tree.addDirectory(smbRoot, testVideo("a.mkv", "smb://nas/media/a.mkv"))
        val failingViewModel = LibraryViewModel(
            DirectoryMediaLibrary(tree),
            tree,
            settings,
            sources,
            verifier,
            FailingDirectoryPositionStore(),
            navigation,
        )
        val source = ConnectionSource(SourceProtocol.Smb, "NAS", smbRoot.location, smbRoot)

        failingViewModel.openSource(source)
        advanceUntilIdle()

        assertFalse(failingViewModel.state.value.loading)
        assertNull(failingViewModel.state.value.failure)
        assertEquals(listOf("a.mkv"), failingViewModel.state.value.entries.map { it.name })
        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), failingViewModel.state.value.scrollTarget)
    }

    @Test
    fun `删除连接源等位置记录清理完再更新列表`() = runTest {
        tree.addDirectory(smbRoot)
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        val gatedStore = GatedDirectoryPositionStore()
        val gatedViewModel = LibraryViewModel(DirectoryMediaLibrary(tree), tree, settings, sources, verifier, gatedStore, navigation)
        advanceUntilIdle()

        gatedViewModel.removeSource(gatedViewModel.sourcesState.value.smb.single())
        advanceUntilIdle()

        assertEquals(1, gatedViewModel.sourcesState.value.smb.size)

        gatedStore.clearGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<ConnectionSource>(), gatedViewModel.sourcesState.value.smb)
    }

    @Test
    fun `进程回收后恢复目录栈、浏览位置并逐级返回`() = runTest {
        val series = testDirectory("剧集", "smb://nas/media/剧集")
        val season = testDirectory("第二季", "smb://nas/media/剧集/第二季")
        tree.addDirectory(smbRoot, series)
        tree.addDirectory(series, season)
        tree.addDirectory(
            season,
            testVideo("第01集.mkv", "smb://nas/media/剧集/第二季/第01集.mkv"),
            testVideo("第02集.mkv", "smb://nas/media/剧集/第二季/第02集.mkv"),
        )
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()
        viewModel.enter(series)
        advanceUntilIdle()
        viewModel.enter(season)
        advanceUntilIdle()
        viewModel.savePosition(
            "smb://nas/media",
            "剧集/第二季",
            "smb://nas/media/剧集/第二季/第02集.mkv",
            36,
        )
        advanceUntilIdle()

        val restored = createViewModel(navigation = restoredNavigation())
        advanceUntilIdle()

        assertEquals("smb://nas/media", restored.state.value.sourceLocation)
        assertEquals(listOf("nas", "剧集", "第二季"), restored.state.value.stack.map { it.name })
        assertEquals("第二季", restored.state.value.currentDirectory?.name)
        assertEquals(listOf("第01集.mkv", "第02集.mkv"), restored.state.value.entries.map { it.name })
        assertEquals(DirectoryScrollTarget(index = 1, offset = 36), restored.state.value.scrollTarget)

        restored.navigateUp()
        advanceUntilIdle()
        assertEquals(listOf("nas", "剧集"), restored.state.value.stack.map { it.name })
        assertEquals("剧集", restored.state.value.currentDirectory?.name)

        restored.navigateUp()
        advanceUntilIdle()
        assertEquals(1, restored.state.value.stack.size)
        assertEquals("smb://nas/media", restored.state.value.currentDirectory?.location)
    }

    @Test
    fun `开关始终关闭时进程回收恢复原目录与浏览位置`() = runTest {
        val series = testDirectory("剧集", "smb://nas/media/剧集")
        val season = testDirectory("第二季", "smb://nas/media/剧集/第二季")
        tree.addDirectory(
            smbRoot,
            testVideo("根01.mkv", "smb://nas/media/根01.mkv"),
            series,
            testVideo("根02.mkv", "smb://nas/media/根02.mkv"),
        )
        tree.addDirectory(
            series,
            season,
            testVideo("剧01.mkv", "smb://nas/media/剧集/剧01.mkv"),
            testVideo("剧02.mkv", "smb://nas/media/剧集/剧02.mkv"),
        )
        tree.addDirectory(
            season,
            testVideo("第01集.mkv", "smb://nas/media/剧集/第二季/第01集.mkv"),
            testVideo("第02集.mkv", "smb://nas/media/剧集/第二季/第02集.mkv"),
        )
        settings.setAutoRestorePosition(false)
        advanceUntilIdle()
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()
        viewModel.savePosition("smb://nas/media", "", "smb://nas/media/根02.mkv", 20)
        viewModel.enter(series)
        advanceUntilIdle()
        viewModel.savePosition("smb://nas/media", "剧集", "smb://nas/media/剧集/剧02.mkv", 24)
        viewModel.enter(season)
        advanceUntilIdle()
        viewModel.savePosition(
            "smb://nas/media",
            "剧集/第二季",
            "smb://nas/media/剧集/第二季/第02集.mkv",
            36,
        )
        advanceUntilIdle()

        assertEquals("smb://nas/media/根02.mkv", positions.load("smb://nas/media", "")?.entryLocation)
        assertEquals("smb://nas/media/剧集/剧02.mkv", positions.load("smb://nas/media", "剧集")?.entryLocation)
        assertEquals(
            "smb://nas/media/剧集/第二季/第02集.mkv",
            positions.load("smb://nas/media", "剧集/第二季")?.entryLocation,
        )

        val restored = createViewModel(navigation = restoredNavigation())
        advanceUntilIdle()

        assertEquals(listOf("nas", "剧集", "第二季"), restored.state.value.stack.map { it.name })
        assertEquals(DirectoryScrollTarget(index = 1, offset = 36), restored.state.value.scrollTarget)
        assertFalse(restored.state.value.restorePositionForced)

        settings.setAutoRestorePosition(true)
        advanceUntilIdle()
        settings.setAutoRestorePosition(false)
        advanceUntilIdle()
        assertEquals(DirectoryScrollTarget(index = 1, offset = 36), restored.state.value.scrollTarget)

        settings.setView(ViewMode.Grid, 3)
        advanceUntilIdle()
        assertEquals(DirectoryScrollTarget(index = 1, offset = 36), restored.state.value.scrollTarget)

        restored.navigateUp()
        advanceUntilIdle()
        assertEquals("剧集", restored.state.value.currentDirectory?.name)
        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), restored.state.value.scrollTarget)

        restored.enter(season)
        advanceUntilIdle()
        assertEquals("第二季", restored.state.value.currentDirectory?.name)
        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), restored.state.value.scrollTarget)

        restored.openSource(restored.sourcesState.value.smb.single())
        advanceUntilIdle()
        assertEquals("nas", restored.state.value.currentDirectory?.name)
        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), restored.state.value.scrollTarget)
    }

    @Test
    fun `恢复连续读取失败时保留强制定位并在重试成功后恢复`() = runTest {
        val series = testDirectory("剧集", "smb://nas/media/剧集")
        tree.addDirectory(smbRoot, series)
        tree.addDirectory(
            series,
            testVideo("第01集.mkv", "smb://nas/media/剧集/第01集.mkv"),
            testVideo("第02集.mkv", "smb://nas/media/剧集/第02集.mkv"),
        )
        settings.setAutoRestorePosition(false)
        advanceUntilIdle()
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()
        viewModel.enter(series)
        advanceUntilIdle()
        viewModel.savePosition("smb://nas/media", "剧集", "smb://nas/media/剧集/第02集.mkv", 36)
        advanceUntilIdle()

        val restored = createViewModel(
            library = FailTimesMediaLibrary(DirectoryMediaLibrary(tree), failures = 2),
            navigation = restoredNavigation(),
        )
        advanceUntilIdle()
        assertEquals(MediaFailure.Unreachable, restored.state.value.failure)
        assertTrue(restored.state.value.restorePositionForced)

        restored.retry()
        advanceUntilIdle()
        assertEquals(MediaFailure.Unreachable, restored.state.value.failure)
        assertTrue(restored.state.value.restorePositionForced)

        restored.retry()
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 1, offset = 36), restored.state.value.scrollTarget)
        assertFalse(restored.state.value.restorePositionForced)
    }

    @Test
    fun `恢复首次读取失败后正常进入目录仍从顶部开始`() = runTest {
        val series = testDirectory("剧集", "smb://nas/media/剧集")
        tree.addDirectory(
            smbRoot,
            testVideo("a.mkv", "smb://nas/media/a.mkv"),
            testVideo("b.mkv", "smb://nas/media/b.mkv"),
            series,
        )
        tree.addDirectory(series, testVideo("第01集.mkv", "smb://nas/media/剧集/第01集.mkv"))
        settings.setAutoRestorePosition(false)
        advanceUntilIdle()
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()
        viewModel.savePosition("smb://nas/media", "", "smb://nas/media/b.mkv", 20)
        viewModel.enter(series)
        advanceUntilIdle()

        val restored = createViewModel(
            library = FailTimesMediaLibrary(DirectoryMediaLibrary(tree), failures = 1),
            navigation = restoredNavigation(),
        )
        advanceUntilIdle()
        assertTrue(restored.state.value.restorePositionForced)

        restored.openSource(restored.sourcesState.value.smb.single())
        advanceUntilIdle()

        assertEquals("nas", restored.state.value.currentDirectory?.name)
        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), restored.state.value.scrollTarget)
        assertFalse(restored.state.value.restorePositionForced)
    }

    @Test
    fun `开关关闭时长按排序仍写入顶部位置`() = runTest {
        tree.addDirectory(
            smbRoot,
            testVideo("a.mkv", "smb://nas/media/a.mkv"),
            testVideo("b.mkv", "smb://nas/media/b.mkv"),
        )
        settings.setAutoRestorePosition(false)
        advanceUntilIdle()
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()
        viewModel.savePosition("smb://nas/media", "", "smb://nas/media/b.mkv", 40)
        advanceUntilIdle()

        viewModel.scrollToTop()
        advanceUntilIdle()

        assertEquals(DirectoryPosition("smb://nas/media/a.mkv", 0), positions.load("smb://nas/media", ""))
    }

    @Test
    fun `离开库页面时记录当前位置作为返回后的目标`() = runTest {
        tree.addDirectory(
            smbRoot,
            testVideo("a.mkv", "smb://nas/media/a.mkv"),
            testVideo("b.mkv", "smb://nas/media/b.mkv"),
            testVideo("c.mkv", "smb://nas/media/c.mkv"),
        )
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()

        // 离开库页面时界面补记一次当前位置。
        viewModel.savePosition(smbRoot.location, "", "smb://nas/media/c.mkv", 30)
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 2, offset = 30), viewModel.state.value.scrollTarget)

        val recreated = createViewModel()
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 2, offset = 30), recreated.state.value.scrollTarget)
    }

    @Test
    fun `加载中切换排序后目录读完回到顶部`() = runTest {
        tree.addDirectory(
            smbRoot,
            testVideo("a.mkv", "smb://nas/media/a.mkv"),
            testVideo("b.mkv", "smb://nas/media/b.mkv"),
        )
        positions.save(smbRoot.location, "", DirectoryPosition("smb://nas/media/b.mkv", 40))
        val gated = GatedMediaLibrary(DirectoryMediaLibrary(tree))
        val gatedViewModel = createViewModel(library = gated)
        val source = ConnectionSource(SourceProtocol.Smb, "NAS", smbRoot.location, smbRoot)

        gatedViewModel.openSource(source)
        advanceUntilIdle()
        assertTrue(gatedViewModel.state.value.loading)

        settings.setSortOrder(SortOrder.NameDescending)
        advanceUntilIdle()
        assertTrue(gatedViewModel.state.value.pendingScrollToTop)

        gated.gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(gatedViewModel.state.value.loading)
        assertFalse(gatedViewModel.state.value.pendingScrollToTop)
        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), gatedViewModel.state.value.scrollTarget)
    }

    @Test
    fun `加载中切换视图且开关关闭时仍按记录定位`() = runTest {
        tree.addDirectory(
            smbRoot,
            testVideo("a.mkv", "smb://nas/media/a.mkv"),
            testVideo("b.mkv", "smb://nas/media/b.mkv"),
        )
        positions.save(smbRoot.location, "", DirectoryPosition("smb://nas/media/b.mkv", 40))
        settings.setAutoRestorePosition(false)
        advanceUntilIdle()
        val gated = GatedMediaLibrary(DirectoryMediaLibrary(tree))
        val gatedViewModel = createViewModel(library = gated, navigation = NavigationViewModel())
        val source = ConnectionSource(SourceProtocol.Smb, "NAS", smbRoot.location, smbRoot)

        gatedViewModel.openSource(source)
        advanceUntilIdle()
        assertTrue(gatedViewModel.state.value.loading)
        assertNull(gatedViewModel.state.value.scrollTarget)

        settings.setView(ViewMode.Grid, 3)
        advanceUntilIdle()
        assertTrue(gatedViewModel.state.value.restorePositionForced)

        gated.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 1, offset = 40), gatedViewModel.state.value.scrollTarget)
        assertFalse(gatedViewModel.state.value.restorePositionForced)
    }

    @Test
    fun `长按排序回到顶部并写入位置记录`() = runTest {
        tree.addDirectory(
            smbRoot,
            testVideo("a.mkv", "smb://nas/media/a.mkv"),
            testVideo("b.mkv", "smb://nas/media/b.mkv"),
        )
        openSmbForm(address = "nas", path = "media")
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.smb.single())
        advanceUntilIdle()
        viewModel.savePosition(smbRoot.location, "", "smb://nas/media/b.mkv", 40)
        advanceUntilIdle()

        viewModel.scrollToTop()
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), viewModel.state.value.scrollTarget)
        assertEquals(DirectoryPosition("smb://nas/media/a.mkv", 0), viewModel.state.value.savedPosition)
        assertEquals(DirectoryPosition("smb://nas/media/a.mkv", 0), positions.load(smbRoot.location, ""))

        val recreated = createViewModel()
        advanceUntilIdle()

        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), recreated.state.value.scrollTarget)
    }

    @Test
    fun `冷启动开关开启时恢复连接源、完整目录栈与滚动位置`() = runTest {
        val series = testDirectory("剧集", "content://root/媒体/剧集")
        val season = testDirectory("第二季", "content://root/媒体/剧集/第二季")
        tree.addDirectory(localRoot, testVideo("根.mkv", "content://root/媒体/根.mkv"), series)
        tree.addDirectory(series, season)
        tree.addDirectory(
            season,
            testVideo("第01集.mkv", "content://root/媒体/剧集/第二季/第01集.mkv"),
            testVideo("第02集.mkv", "content://root/媒体/剧集/第二季/第02集.mkv"),
        )
        settings.addRoot(localRoot.location)
        positions.save(
            localRoot.location,
            "剧集/第二季",
            DirectoryPosition("content://root/媒体/剧集/第二季/第02集.mkv", 36),
        )
        snapshots.save(
            NavigationSnapshot(
                screen = AppScreen.Library,
                sourcesProtocol = SourceProtocol.Local,
                settingsReturn = AppScreen.Library,
                librarySource = localRoot.location,
                libraryStack = listOf(localRoot, series, season),
            ),
        )
        val coldNavigation = NavigationViewModel(SavedStateHandle(), snapshots = snapshots)

        val restored = createViewModel(navigation = coldNavigation)
        advanceUntilIdle()

        assertEquals(AppScreen.Library, coldNavigation.state.value.screen)
        assertEquals(
            listOf("媒体", "剧集", "第二季"),
            coldNavigation.state.value.library?.stack?.map { it.name },
        )
        assertEquals(localRoot.location, restored.state.value.sourceLocation)
        assertEquals(listOf("第01集.mkv", "第02集.mkv"), restored.state.value.entries.map { it.name })
        assertEquals(DirectoryScrollTarget(index = 1, offset = 36), restored.state.value.scrollTarget)
        assertFalse(restored.state.value.restorePositionForced)
    }

    @Test
    fun `冷启动开关关闭时回首页`() = runTest {
        tree.addDirectory(localRoot)
        settings.addRoot(localRoot.location)
        settings.setRestoreLastDirectory(false)
        snapshots.save(
            NavigationSnapshot(
                screen = AppScreen.Library,
                sourcesProtocol = SourceProtocol.Local,
                settingsReturn = AppScreen.Library,
                librarySource = localRoot.location,
                libraryStack = listOf(localRoot),
            ),
        )
        val coldNavigation = NavigationViewModel(SavedStateHandle(), snapshots = snapshots)

        val restored = createViewModel(navigation = coldNavigation)
        advanceUntilIdle()

        assertEquals(AppScreen.Home, coldNavigation.state.value.screen)
        assertNull(coldNavigation.state.value.library)
        assertEquals("", restored.state.value.sourceLocation)
        assertFalse(coldNavigation.coldStartPending.value)
    }

    @Test
    fun `上次停在非库页面时冷启动回首页`() = runTest {
        settings.addRoot(localRoot.location)
        snapshots.save(
            NavigationSnapshot(
                screen = AppScreen.Sources,
                sourcesProtocol = SourceProtocol.Local,
                settingsReturn = AppScreen.Home,
            ),
        )
        val coldNavigation = NavigationViewModel(SavedStateHandle(), snapshots = snapshots)

        createViewModel(navigation = coldNavigation)
        advanceUntilIdle()

        assertEquals(AppScreen.Home, coldNavigation.state.value.screen)
        assertNull(coldNavigation.state.value.library)
    }

    @Test
    fun `上次的连接源已删除时冷启动回首页并记录日志`() = runTest {
        tree.addDirectory(localRoot)
        val diagnostics = RecordingLibraryDiagnostics()
        snapshots.save(
            NavigationSnapshot(
                screen = AppScreen.Library,
                sourcesProtocol = SourceProtocol.Local,
                settingsReturn = AppScreen.Library,
                librarySource = "content://root/已删除",
                libraryStack = listOf(testDirectory("已删除", "content://root/已删除")),
            ),
        )
        val coldNavigation = NavigationViewModel(
            SavedStateHandle(),
            snapshots = snapshots,
            diagnostics = diagnostics,
        )

        createViewModel(navigation = coldNavigation)
        advanceUntilIdle()

        assertEquals(AppScreen.Home, coldNavigation.state.value.screen)
        assertNull(coldNavigation.state.value.library)
        assertTrue(diagnostics.messages.any { it.contains("导航恢复失败") && it.contains("连接源") })
    }

    @Test
    fun `目录不可访问时冷启动回首页并记录日志`() = runTest {
        val series = testDirectory("剧集", "content://root/媒体/剧集")
        tree.addDirectory(localRoot, series)
        tree.addDirectory(series, testVideo("第01集.mkv", "content://root/媒体/剧集/第01集.mkv"))
        tree.markUnreadable(series.location)
        settings.addRoot(localRoot.location)
        val diagnostics = RecordingLibraryDiagnostics()
        snapshots.save(
            NavigationSnapshot(
                screen = AppScreen.Library,
                sourcesProtocol = SourceProtocol.Local,
                settingsReturn = AppScreen.Library,
                librarySource = localRoot.location,
                libraryStack = listOf(localRoot, series),
            ),
        )
        val coldNavigation = NavigationViewModel(
            SavedStateHandle(),
            snapshots = snapshots,
            diagnostics = diagnostics,
        )

        val restored = createViewModel(navigation = coldNavigation)
        advanceUntilIdle()

        assertEquals(AppScreen.Home, coldNavigation.state.value.screen)
        assertNull(coldNavigation.state.value.library)
        assertEquals("", restored.state.value.sourceLocation)
        assertTrue(diagnostics.messages.any { it.contains("导航恢复失败") && it.contains("目录") })
    }

    @Test
    fun `冷启动恢复源协议`() = runTest {
        tree.addDirectory(webDavRoot)
        openWebDavForm(address = "https://dav.example.com", path = "/dav/files/cc")
        advanceUntilIdle()
        snapshots.save(
            NavigationSnapshot(
                screen = AppScreen.Library,
                sourcesProtocol = SourceProtocol.WebDav,
                settingsReturn = AppScreen.Library,
                librarySource = webDavRoot.location,
                libraryStack = listOf(webDavRoot),
            ),
        )
        val coldNavigation = NavigationViewModel(SavedStateHandle(), snapshots = snapshots)

        createViewModel(navigation = coldNavigation)
        advanceUntilIdle()

        assertEquals(AppScreen.Library, coldNavigation.state.value.screen)
        assertEquals(SourceProtocol.WebDav, coldNavigation.state.value.sourcesProtocol)

        coldNavigation.backFromLibrary()
        assertEquals(AppScreen.Sources, coldNavigation.state.value.screen)
        assertEquals(SourceProtocol.WebDav, coldNavigation.state.value.sourcesProtocol)
    }

    @Test
    fun `用户离开取消冷启动校验，迟到结果不覆盖当前界面`() = runTest {
        tree.addDirectory(localRoot)
        settings.addRoot(localRoot.location)
        snapshots.save(
            NavigationSnapshot(
                screen = AppScreen.Library,
                sourcesProtocol = SourceProtocol.Local,
                settingsReturn = AppScreen.Library,
                librarySource = localRoot.location,
                libraryStack = listOf(localRoot),
            ),
        )
        val gated = GatedMediaLibrary(DirectoryMediaLibrary(tree))
        val coldNavigation = NavigationViewModel(SavedStateHandle(), snapshots = snapshots)
        val recoveryLog = RecordingLibraryDiagnostics()
        val restored = createViewModel(library = gated, navigation = coldNavigation, diagnostics = recoveryLog)
        advanceUntilIdle()

        assertEquals(AppScreen.Home, coldNavigation.state.value.screen)

        coldNavigation.openProtocol(SourceProtocol.Local)
        advanceUntilIdle()
        gated.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(AppScreen.Sources, coldNavigation.state.value.screen)
        assertNull(coldNavigation.state.value.library)
        assertEquals("", restored.state.value.sourceLocation)
        assertTrue(recoveryLog.messages.any { it.contains("已放弃本次恢复") })
    }

    @Test
    fun `冷启动校验读到的当前目录直接用于首次加载`() = runTest {
        val series = testDirectory("剧集", "content://root/媒体/剧集")
        tree.addDirectory(localRoot, series)
        tree.addDirectory(series, testVideo("第01集.mkv", "content://root/媒体/剧集/第01集.mkv"))
        settings.addRoot(localRoot.location)
        snapshots.save(
            NavigationSnapshot(
                screen = AppScreen.Library,
                sourcesProtocol = SourceProtocol.Local,
                settingsReturn = AppScreen.Library,
                librarySource = localRoot.location,
                libraryStack = listOf(localRoot, series),
            ),
        )
        val counting = CountingMediaLibrary(DirectoryMediaLibrary(tree))
        val coldNavigation = NavigationViewModel(SavedStateHandle(), snapshots = snapshots)

        val restored = createViewModel(library = counting, navigation = coldNavigation)
        advanceUntilIdle()

        assertEquals(listOf(localRoot.location, series.location), counting.listCalls)
        assertEquals(listOf("第01集.mkv"), restored.state.value.entries.map { it.name })
    }

    @Test
    fun `冷启动判定完成前停在首页，完成后落到目标库页面`() = runTest {
        val series = testDirectory("剧集", "content://root/媒体/剧集")
        tree.addDirectory(localRoot, series)
        tree.addDirectory(series)
        settings.addRoot(localRoot.location)
        snapshots.save(
            NavigationSnapshot(
                screen = AppScreen.Library,
                sourcesProtocol = SourceProtocol.Local,
                settingsReturn = AppScreen.Library,
                librarySource = localRoot.location,
                libraryStack = listOf(localRoot, series),
            ),
        )
        val gated = GatedMediaLibrary(DirectoryMediaLibrary(tree))
        val coldNavigation = NavigationViewModel(SavedStateHandle(), snapshots = snapshots)

        val restored = createViewModel(library = gated, navigation = coldNavigation)
        advanceUntilIdle()

        // 目录列举仍挂起：判定未完成，导航状态停在首页，没有可渲染的库页面。
        assertTrue(coldNavigation.coldStartPending.value)
        assertEquals(AppScreen.Home, coldNavigation.state.value.screen)
        assertNull(coldNavigation.state.value.library)
        assertTrue(restored.state.value.stack.isEmpty())

        gated.gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(coldNavigation.coldStartPending.value)
        assertEquals(AppScreen.Library, coldNavigation.state.value.screen)
        assertEquals(listOf("媒体", "剧集"), coldNavigation.state.value.library?.stack?.map { it.name })
        assertEquals(series.location, restored.state.value.currentDirectory?.location)
    }

    @Test
    fun `进程恢复停在设置页时库页面目录已准备好返回`() = runTest {
        val series = testDirectory("剧集", "content://root/媒体/剧集")
        tree.addDirectory(localRoot, series)
        tree.addDirectory(series, testVideo("第01集.mkv", "content://root/媒体/剧集/第01集.mkv"))
        settings.addRoot(localRoot.location)
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.local.single())
        advanceUntilIdle()
        viewModel.enter(series)
        advanceUntilIdle()
        navigation.openSettings()

        val restoredNavigation = restoredNavigation()
        val restored = createViewModel(navigation = restoredNavigation)
        advanceUntilIdle()

        assertEquals(AppScreen.Settings, restoredNavigation.state.value.screen)
        assertEquals("剧集", restored.state.value.currentDirectory?.name)
        assertEquals(listOf("第01集.mkv"), restored.state.value.entries.map { it.name })

        restoredNavigation.closeSettings()
        assertEquals(AppScreen.Library, restoredNavigation.state.value.screen)

        restored.navigateUp()
        advanceUntilIdle()
        assertEquals("媒体", restored.state.value.currentDirectory?.name)
        assertEquals(listOf("剧集"), restored.state.value.entries.map { it.name })
    }

    @Test
    fun `设置页来处为库页面时恢复失败关闭设置回首页`() = runTest {
        val series = testDirectory("剧集", "content://root/媒体/剧集")
        tree.addDirectory(localRoot, series)
        tree.addDirectory(series, testVideo("第01集.mkv", "content://root/媒体/剧集/第01集.mkv"))
        settings.addRoot(localRoot.location)
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.local.single())
        advanceUntilIdle()
        viewModel.enter(series)
        advanceUntilIdle()
        navigation.openSettings()
        tree.markUnreadable(series.location)

        val recoveryLog = RecordingLibraryDiagnostics()
        val restoredNavigation = restoredNavigation(diagnostics = recoveryLog)
        createViewModel(navigation = restoredNavigation)
        advanceUntilIdle()

        assertEquals(AppScreen.Settings, restoredNavigation.state.value.screen)
        assertEquals(AppScreen.Home, restoredNavigation.state.value.settingsReturn)
        assertNull(restoredNavigation.state.value.library)
        assertTrue(recoveryLog.messages.any { it.contains("关闭设置后回首页") })

        restoredNavigation.closeSettings()
        assertEquals(AppScreen.Home, restoredNavigation.state.value.screen)
    }

    @Test
    fun `用户离开后恢复派生加载被取消`() = runTest {
        tree.addDirectory(localRoot, testVideo("a.mkv", "content://root/媒体/a.mkv"))
        settings.addRoot(localRoot.location)
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.local.single())
        advanceUntilIdle()
        val gated = GatedMediaLibrary(DirectoryMediaLibrary(tree))
        val restoredNavigation = restoredNavigation()
        val recoveryLog = RecordingLibraryDiagnostics()

        val restored = createViewModel(
            library = gated,
            navigation = restoredNavigation,
            diagnostics = recoveryLog,
        )
        advanceUntilIdle()
        assertEquals(AppScreen.Library, restoredNavigation.state.value.screen)

        restoredNavigation.backFromLibrary()
        advanceUntilIdle()
        gated.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(AppScreen.Sources, restoredNavigation.state.value.screen)
        assertEquals(0, gated.completedLists)
        assertTrue(restored.state.value.entries.isEmpty())
        assertTrue(recoveryLog.messages.any { it.contains("已放弃本次恢复") })
    }

    @Test
    fun `进程恢复打开目录失败时回首页并记录日志`() = runTest {
        val series = testDirectory("剧集", "content://root/媒体/剧集")
        tree.addDirectory(localRoot, series)
        tree.addDirectory(series, testVideo("第01集.mkv", "content://root/媒体/剧集/第01集.mkv"))
        settings.addRoot(localRoot.location)
        advanceUntilIdle()
        viewModel.openSource(viewModel.sourcesState.value.local.single())
        advanceUntilIdle()
        viewModel.enter(series)
        advanceUntilIdle()
        tree.markUnreadable(series.location)
        val diagnostics = RecordingLibraryDiagnostics()
        val restored = NavigationViewModel(
            restoredHandle(),
            snapshots = snapshots,
            diagnostics = diagnostics,
        )

        createViewModel(navigation = restored)
        advanceUntilIdle()

        assertEquals(AppScreen.Home, restored.state.value.screen)
        assertNull(restored.state.value.library)
        assertTrue(diagnostics.messages.any { it.contains("导航恢复失败") && it.contains("目录") })
    }

    /**
     * 模拟进程回收：新建句柄并深拷贝列表值。
     * 直接构造 SavedStateHandle 不经过 Bundle 序列化，只覆盖键值进入恢复路径。
     */
    private fun restoredHandle(): SavedStateHandle {
        val restored = savedState.keys().associateWith { key ->
            when (val value = savedState.get<Any?>(key)) {
                is ArrayList<*> -> ArrayList(value)
                else -> value
            }
        }
        return SavedStateHandle(restored)
    }

    private fun restoredNavigation(
        diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    ): NavigationViewModel {
        val restored = savedState.keys().associateWith { key ->
            when (val value = savedState.get<Any?>(key)) {
                is ArrayList<*> -> ArrayList(value)
                else -> value
            }
        }
        return NavigationViewModel(
            SavedStateHandle(restored),
            snapshots = snapshots,
            diagnostics = diagnostics,
        )
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
