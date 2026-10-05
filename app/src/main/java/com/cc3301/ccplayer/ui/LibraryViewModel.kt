package com.cc3301.ccplayer.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cc3301.ccplayer.library.DirectoryMediaLibrary
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaLibrary
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.ProgressCleanupMediaLibrary
import com.cc3301.ccplayer.library.RoutingMediaFileTree
import com.cc3301.ccplayer.library.SafMediaFileTree
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.library.sortedForDisplay
import com.cc3301.ccplayer.network.DataStoreBookmarkStore
import com.cc3301.ccplayer.network.EncryptedCredentialStore
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.network.NetworkSourceVerifier
import com.cc3301.ccplayer.network.RoutingNetworkSourceVerifier
import com.cc3301.ccplayer.progress.RoomPlaybackProgressRepository
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.settings.ViewMode
import com.cc3301.ccplayer.smb.SmbJConnector
import com.cc3301.ccplayer.smb.SmbLocation
import com.cc3301.ccplayer.smb.SmbLocator
import com.cc3301.ccplayer.smb.SmbMediaFileTree
import com.cc3301.ccplayer.smb.SmbSourceVerifier
import com.cc3301.ccplayer.webdav.OkHttpWebDavTransport
import com.cc3301.ccplayer.webdav.WebDavAddress
import com.cc3301.ccplayer.webdav.WebDavLocator
import com.cc3301.ccplayer.webdav.WebDavMediaFileTree
import com.cc3301.ccplayer.webdav.WebDavSourceVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 网络源的两种接入协议。 */
enum class NetworkSourceProtocol { Smb, WebDav }

/** 新增网络源表单的状态。 */
data class NetworkSourceForm(
    /** 正在填写的协议；打开表单时定下，填写中不变。 */
    val protocol: NetworkSourceProtocol,
    /** 正在校验连通性。 */
    val adding: Boolean = false,
    /** 上次校验失败的原因；成功或未提交时为 null。 */
    val failure: MediaFailure? = null,
)

/** 媒体列表界面的状态。 */
data class LibraryState(
    /** 设置读出来没有；没读出来时先不显示首次引导。 */
    val ready: Boolean = false,
    val roots: List<MediaFile> = emptyList(),
    /** 浏览路径，最后一项是当前目录；为空表示停在根目录这一层。 */
    val stack: List<MediaFile> = emptyList(),
    /** 当前目录读出的条目，未排序；展示次序见 [visibleEntries]。 */
    val entries: List<MediaFile> = emptyList(),
    val viewMode: ViewMode = ViewMode.List,
    val gridColumns: Int = SettingsRepository.DEFAULT_GRID_COLUMNS,
    val sortOrder: SortOrder = SettingsRepository.DEFAULT_SORT_ORDER,
    /** 排序选项每次变化的递增序号：界面据此把列表回到顶部。 */
    val sortRevision: Int = 0,
    /** 最近起播的媒体位置；列表进入时据此滚动。 */
    val lastPlayedLocation: String? = null,
    /** "进入列表自动滚动到上次播放位置"开关，默认开。 */
    val autoScrollToLastPlayed: Boolean = true,
    /** 本次进入列表要滚动到的条目位置；没有目标时为 null。 */
    val scrollTargetLocation: String? = null,
    /** 每次定出滚动目标时递增：界面据此滚动一次。 */
    val scrollRevision: Int = 0,
    val loading: Boolean = false,
    /** 当前目录读取失败的原因；成功时为 null。 */
    val failure: MediaFailure? = null,
    /** 新增网络源表单；null 表示表单没打开。 */
    val networkSourceForm: NetworkSourceForm? = null,
) {
    val currentDirectory: MediaFile? get() = stack.lastOrNull()

    /** 列表实际展示的次序：子目录在前，组内按当前排序选项。 */
    val visibleEntries: List<MediaFile> get() = entries.sortedForDisplay(sortOrder)

    /** 套用排序偏好；排序选项变了才递增 [sortRevision]。 */
    fun withSortOrder(order: SortOrder): LibraryState =
        copy(sortOrder = order, sortRevision = if (order == sortOrder) sortRevision else sortRevision + 1)

    /** 目录读完后套用结果，并定出进入列表要滚动到的条目。 */
    fun withLoadedEntries(entries: List<MediaFile>, failure: MediaFailure?): LibraryState {
        val target = scrollTargetIn(entries)
        return copy(
            entries = entries,
            loading = false,
            failure = failure,
            scrollTargetLocation = target,
            scrollRevision = if (target == null) scrollRevision else scrollRevision + 1,
        )
    }

    /** 上次播放的位置变了（比如在播放器里切了集）：按下目前读出的目录重定滚动目标。 */
    fun withRefreshedScrollTarget(): LibraryState {
        val target = scrollTargetIn(entries)
        return copy(
            scrollTargetLocation = target,
            scrollRevision = if (target == null) scrollRevision else scrollRevision + 1,
        )
    }

    /** 滚动目标：开关关、还没播过、或上次播放的条目不在 [entries] 里时为空。 */
    private fun scrollTargetIn(entries: List<MediaFile>): String? =
        lastPlayedLocation?.takeIf { autoScrollToLastPlayed && entries.any { entry -> entry.location == it } }
}

/**
 * 媒体列表的状态持有者：读设置里的本地根目录与书签里的网络源，按当前目录向 [MediaLibrary] 要列表。
 */
class LibraryViewModel(
    private val library: MediaLibrary,
    private val settings: SettingsRepository,
    private val sources: NetworkSourceRepository,
    private val verifier: NetworkSourceVerifier,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            combine(settings.rootLocations, sources.locations) { local, network -> local + network }
                .collect { locations ->
                    _state.update { it.copy(ready = true, roots = resolveRoots(locations)) }
                }
        }
        viewModelScope.launch {
            combine(
                settings.viewMode,
                settings.gridColumns,
                settings.sortOrder,
                settings.autoScrollToLastPlayed,
                settings.lastPlayedLocation,
            ) { mode, columns, order, autoScroll, lastPlayed ->
                ListPreferences(mode, columns, order, autoScroll, lastPlayed)
            }.collect { preferences ->
                _state.update { current ->
                    val updated = current.withSortOrder(preferences.sortOrder).copy(
                        viewMode = preferences.viewMode,
                        gridColumns = preferences.gridColumns,
                        autoScrollToLastPlayed = preferences.autoScrollToLastPlayed,
                        lastPlayedLocation = preferences.lastPlayedLocation,
                    )
                    if (updated.lastPlayedLocation == current.lastPlayedLocation) {
                        updated
                    } else {
                        updated.withRefreshedScrollTarget()
                    }
                }
            }
        }
    }

    /** 本地目录选择器返回后调用。 */
    fun addRoot(location: String) {
        viewModelScope.launch {
            settings.addRoot(location)
            refreshRoots()
        }
    }

    /** 打开新增网络源表单。 */
    fun openNetworkSourceForm(protocol: NetworkSourceProtocol) {
        _state.update { it.copy(networkSourceForm = NetworkSourceForm(protocol = protocol)) }
    }

    fun dismissNetworkSourceForm() {
        _state.update { it.copy(networkSourceForm = null) }
    }

    /** 校验凭据后保存 SMB 书签；校验不过时保留表单并给出失败原因。 */
    fun addSmbSource(host: String, share: String, username: String, password: String) {
        saveNetworkSource(url = SmbLocation.shareUrl(host, share), username = username, password = password)
    }

    /** 校验凭据后保存 WebDAV 书签；地址不合法或校验不过时保留表单并给出失败原因。 */
    fun addWebDavSource(address: String, username: String, password: String) {
        val url = WebDavAddress.normalize(address)
        if (url == null) {
            failNetworkSourceForm(MediaFailure.NotFound)
            return
        }
        saveNetworkSource(url = url, username = username, password = password)
    }

    /** 校验连通性后保存书签；校验不过时保留表单并给出失败原因。 */
    private fun saveNetworkSource(url: String, username: String, password: String) {
        val form = _state.value.networkSourceForm ?: return
        viewModelScope.launch {
            _state.update { it.copy(networkSourceForm = form.copy(adding = true, failure = null)) }
            val failure = verify(url, username, password)
            if (failure != null) {
                failNetworkSourceForm(failure)
                return@launch
            }
            sources.add(url = url, username = username, password = password)
            _state.update { it.copy(networkSourceForm = null) }
            refreshRoots()
        }
    }

    /** 保留表单并给出失败原因。 */
    private fun failNetworkSourceForm(failure: MediaFailure) {
        _state.update { current ->
            val form = current.networkSourceForm ?: return@update current
            current.copy(networkSourceForm = form.copy(adding = false, failure = failure))
        }
    }

    fun enter(directory: MediaFile) {
        _state.update { it.copy(stack = it.stack + directory) }
        load(directory)
    }

    fun navigateUp() {
        val stack = _state.value.stack
        if (stack.isEmpty()) return
        val parent = stack.dropLast(1).lastOrNull()
        _state.update { it.copy(stack = stack.dropLast(1)) }
        if (parent == null) {
            loadJob?.cancel()
            _state.update { it.copy(entries = emptyList(), loading = false, failure = null) }
        } else {
            load(parent)
        }
    }

    /** 重新读取当前目录。 */
    fun retry() {
        _state.value.currentDirectory?.let { load(it) }
    }

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { settings.setSortOrder(order) }
    }

    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch { settings.setViewMode(mode) }
    }

    fun setGridColumns(columns: Int) {
        viewModelScope.launch { settings.setGridColumns(columns) }
    }

    private fun load(directory: MediaFile) {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, failure = null) }
        loadJob = viewModelScope.launch {
            val result = library.list(directory)
            _state.update {
                it.withLoadedEntries(result.getOrDefault(emptyList()), result.exceptionOrNull().toFailure())
            }
        }
    }

    private suspend fun verify(url: String, username: String, password: String): MediaFailure? = try {
        verifier.verify(url, username, password)
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (problem: Exception) {
        problem.toFailure()
    }

    private suspend fun refreshRoots() {
        _state.update { it.copy(roots = resolveRoots(settings.rootLocations.first() + sources.locations.first())) }
    }

    private fun Throwable?.toFailure(): MediaFailure? = when (this) {
        null -> null
        is MediaSourceException -> failure
        else -> MediaFailure.Unknown
    }

    /** 位置读不出来的根目录不展示：授权失效或连不上时它在界面上没有用处。 */
    private suspend fun resolveRoots(locations: List<String>): List<MediaFile> =
        locations.mapNotNull { library.resolveDirectory(it) }

    /** 影响列表展示与滚动的设置项。 */
    private data class ListPreferences(
        val viewMode: ViewMode,
        val gridColumns: Int,
        val sortOrder: SortOrder,
        val autoScrollToLastPlayed: Boolean,
        val lastPlayedLocation: String?,
    )

    companion object {
        /** 生产入口：本地 SAF 与各网络协议的文件树（列举目录后清已消失媒体的进度记录）+ DataStore 设置与书签。 */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val appContext = context.applicationContext
                val sources = NetworkSourceRepository(
                    store = DataStoreBookmarkStore.create(appContext),
                    credentials = EncryptedCredentialStore.create(appContext),
                )
                val smbConnector = SmbJConnector()
                val smbLocator = SmbLocator(sources)
                val webDavTransport = OkHttpWebDavTransport()
                val webDavLocator = WebDavLocator(sources)
                LibraryViewModel(
                    library = ProgressCleanupMediaLibrary(
                        DirectoryMediaLibrary(
                            RoutingMediaFileTree(
                                mapOf(
                                    RoutingMediaFileTree.SAF_SCHEME to SafMediaFileTree(appContext),
                                    SmbLocation.SCHEME to SmbMediaFileTree(smbConnector, smbLocator),
                                    WebDavAddress.SCHEME_HTTP to WebDavMediaFileTree(webDavTransport, webDavLocator),
                                    WebDavAddress.SCHEME_HTTPS to WebDavMediaFileTree(webDavTransport, webDavLocator),
                                ),
                            ),
                        ),
                        RoomPlaybackProgressRepository.create(appContext),
                    ),
                    settings = SettingsRepository.create(appContext),
                    sources = sources,
                    verifier = RoutingNetworkSourceVerifier(
                        mapOf(
                            SmbLocation.SCHEME to SmbSourceVerifier(smbConnector),
                            WebDavAddress.SCHEME_HTTP to WebDavSourceVerifier(webDavTransport),
                            WebDavAddress.SCHEME_HTTPS to WebDavSourceVerifier(webDavTransport),
                        ),
                    ),
                )
            }
        }
    }
}
