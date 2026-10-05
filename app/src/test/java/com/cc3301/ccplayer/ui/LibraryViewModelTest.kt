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
import com.cc3301.ccplayer.settings.FakePreferencesDataStore
import com.cc3301.ccplayer.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val verifier = RecordingVerifier()
    private val sources = NetworkSourceRepository(store, FakeCredentialStore())

    private val networkRoot = testDirectory("media", "smb://nas/media")

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
    fun `添加网络源后书签与根目录都出现`() = runTest {
        tree.addDirectory(networkRoot)

        viewModel.addNetworkSource(host = "nas", share = "media", username = "cc", password = "secret")
        advanceUntilIdle()

        assertEquals(listOf("smb://nas/media" to "cc"), verifier.attempts)
        assertEquals(listOf(NetworkBookmark(url = "smb://nas/media", username = "cc")), store.current())
        assertEquals(listOf(networkRoot), viewModel.state.value.roots)
        assertNull(viewModel.state.value.networkSourceForm)
    }

    @Test
    fun `校验失败时不保存书签并保留表单与原因`() = runTest {
        verifier.failure = MediaSourceException(MediaFailure.Authentication, "拒绝登录")
        viewModel.openNetworkSourceForm()

        viewModel.addNetworkSource(host = "nas", share = "media", username = "cc", password = "错的")
        advanceUntilIdle()

        assertEquals(emptyList<NetworkBookmark>(), store.current())
        assertEquals(emptyList<MediaFile>(), viewModel.state.value.roots)
        assertEquals(MediaFailure.Authentication, viewModel.state.value.networkSourceForm?.failure)
        assertEquals(false, viewModel.state.value.networkSourceForm?.adding)
    }

    @Test
    fun `表单关掉后不再显示`() = runTest {
        viewModel.openNetworkSourceForm()

        viewModel.dismissNetworkSourceForm()

        advanceUntilIdle()

        assertNull(viewModel.state.value.networkSourceForm)
    }

    @Test
    fun `网络目录读不到时状态带来源失败原因`() = runTest {
        val series = testDirectory("剧集", "smb://nas/media/剧集")
        tree.addDirectory(networkRoot, series)
        tree.markFailing(series.location, MediaSourceException(MediaFailure.Unreachable, "断线"))

        viewModel.enter(series)
        advanceUntilIdle()

        assertEquals(MediaFailure.Unreachable, viewModel.state.value.failure)
        assertEquals(false, viewModel.state.value.loading)
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
}
