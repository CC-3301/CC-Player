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
import com.cc3301.ccplayer.library.MediaFileTree
import com.cc3301.ccplayer.library.MediaLibrary
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.ProgressCleanupMediaLibrary
import com.cc3301.ccplayer.library.RoutingMediaFileTree
import com.cc3301.ccplayer.library.SafMediaFileTree
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.library.sortedForDisplay
import com.cc3301.ccplayer.network.DataStoreBookmarkStore
import com.cc3301.ccplayer.network.EncryptedCredentialStore
import com.cc3301.ccplayer.network.NetworkBookmark
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.network.NetworkSourceVerifier
import com.cc3301.ccplayer.network.RoutingNetworkSourceVerifier
import com.cc3301.ccplayer.network.SourceProtocol
import com.cc3301.ccplayer.network.normalizeRelativePath
import com.cc3301.ccplayer.progress.RoomPlaybackProgressRepository
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.settings.ViewMode
import com.cc3301.ccplayer.smb.SmbAddress
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

/** 一条连接源：源列表展示 [name]，进入库页面时以 [root] 为根目录。 */
data class ConnectionSource(
    val protocol: SourceProtocol,
    val name: String,
    /** 持久化位置：本地是 SAF 树 URI，网络源是书签地址。 */
    val location: String,
    val root: MediaFile,
) {
    /** 只有网络源可以编辑；本地连接源只提供删除。 */
    val editable: Boolean get() = protocol != SourceProtocol.Local
}

/** 新增或编辑网络源的表单状态。 */
data class NetworkSourceForm(
    val protocol: SourceProtocol,
    /** 编辑时的原地址；null 表示新增。 */
    val originalUrl: String? = null,
    val name: String = "",
    val address: String = "",
    val path: String = "",
    val username: String = "",
    val password: String = "",
    val saving: Boolean = false,
    /** 服务器地址缺失或格式不对。 */
    val addressInvalid: Boolean = false,
    /** 编辑后的地址与另一条连接源重复。 */
    val duplicateAddress: Boolean = false,
    /** 上次连通性校验失败的原因。 */
    val failure: MediaFailure? = null,
) {
    val editing: Boolean get() = originalUrl != null
}

/** 首页与源列表的状态。 */
data class SourcesState(
    val ready: Boolean = false,
    val local: List<ConnectionSource> = emptyList(),
    val smb: List<ConnectionSource> = emptyList(),
    val webDav: List<ConnectionSource> = emptyList(),
    val form: NetworkSourceForm? = null,
) {
    fun sources(protocol: SourceProtocol): List<ConnectionSource> = when (protocol) {
        SourceProtocol.Local -> local
        SourceProtocol.Smb -> smb
        SourceProtocol.WebDav -> webDav
    }
}

/** 库页面的状态。 */
data class LibraryState(
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
 * 应用状态持有者：管理本地与网络连接源、网络源表单，以及单个连接源的目录浏览。
 *
 * 外挂字幕发现需要当前目录的原始条目（字幕文件不在媒体列表里），因此同时持有 [files]。
 */
class LibraryViewModel(
    private val library: MediaLibrary,
    private val files: MediaFileTree,
    private val settings: SettingsRepository,
    private val sources: NetworkSourceRepository,
    private val verifier: NetworkSourceVerifier,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _sourcesState = MutableStateFlow(SourcesState())
    val sourcesState: StateFlow<SourcesState> = _sourcesState.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            combine(settings.rootLocations, sources.bookmarks) { local, network -> local to network }
                .collect { (local, network) ->
                    _sourcesState.update { current ->
                        current.copy(
                            ready = true,
                            local = local.mapNotNull { resolveLocalSource(it) },
                            smb = network.filter { it.protocol == SourceProtocol.Smb }.mapNotNull { resolveNetworkSource(it) },
                            webDav = network.filter { it.protocol == SourceProtocol.WebDav }.mapNotNull { resolveNetworkSource(it) },
                        )
                    }
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

    /** 本地文件夹选择器返回后调用。 */
    fun addLocalRoot(location: String) {
        viewModelScope.launch { settings.addRoot(location) }
    }

    /** 删除连接源：只删本地记录与凭据，不动远程文件或本地文件夹。 */
    fun removeSource(source: ConnectionSource) {
        viewModelScope.launch {
            if (source.protocol == SourceProtocol.Local) {
                settings.removeRoot(source.location)
            } else {
                sources.remove(source.location)
            }
        }
    }

    /** 打开新增网络源表单；本地连接源不需要表单。 */
    fun openNewSourceForm(protocol: SourceProtocol) {
        if (protocol == SourceProtocol.Local) return
        _sourcesState.update { it.copy(form = NetworkSourceForm(protocol = protocol)) }
    }

    /** 打开编辑表单并带出已保存的字段与密码。 */
    fun openEditSourceForm(source: ConnectionSource) {
        if (!source.editable) return
        viewModelScope.launch {
            val bookmark = sources.bookmarks.first().firstOrNull { it.url == source.location } ?: return@launch
            val password = sources.password(bookmark.url).orEmpty()
            _sourcesState.update { current ->
                if (current.form != null) {
                    current
                } else {
                    current.copy(
                        form = NetworkSourceForm(
                            protocol = bookmark.protocol,
                            originalUrl = bookmark.url,
                            name = bookmark.name,
                            address = bookmark.address,
                            path = bookmark.path,
                            username = bookmark.username,
                            password = password,
                        ),
                    )
                }
            }
        }
    }

    fun dismissNetworkSourceForm() {
        _sourcesState.update { current ->
            if (current.form?.saving == true) current else current.copy(form = null)
        }
    }

    /** 离开源列表或切换协议时清掉表单，避免把旧表单带进另一协议。 */
    fun clearNetworkSourceForm() {
        _sourcesState.update { it.copy(form = null) }
    }

    /** 按字段本身更新表单，不依赖字段在界面上的位置。 */
    fun updateFormField(kind: NetworkSourceFieldKind, value: String) {
        _sourcesState.update { current ->
            val form = current.form ?: return@update current
            val updated = when (kind) {
                NetworkSourceFieldKind.Name -> form.copy(name = value)
                NetworkSourceFieldKind.Address -> form.copy(address = value)
                NetworkSourceFieldKind.Path -> form.copy(path = value)
                NetworkSourceFieldKind.Username -> form.copy(username = value)
                NetworkSourceFieldKind.Password -> form.copy(password = value)
            }
            current.copy(form = updated.copy(addressInvalid = false, duplicateAddress = false, failure = null))
        }
    }

    /** 校验地址并试连后保存；地址不合法、重复或连不上时保留表单并给出原因。 */
    fun saveNetworkSourceForm() {
        val form = _sourcesState.value.form ?: return
        if (form.saving) return
        val bookmark = normalizedBookmark(form)
        if (bookmark == null) {
            _sourcesState.update { current ->
                current.copy(form = current.form?.copy(addressInvalid = true, duplicateAddress = false, failure = null))
            }
            return
        }
        viewModelScope.launch {
            val duplicate = form.editing && sources.bookmarks.first().any { it.url == bookmark.url && it.url != form.originalUrl }
            if (duplicate) {
                _sourcesState.update { current ->
                    current.copy(form = current.form?.copy(duplicateAddress = true, failure = null))
                }
                return@launch
            }
            _sourcesState.update { current ->
                current.copy(form = current.form?.copy(saving = true, addressInvalid = false, duplicateAddress = false, failure = null))
            }
            val failure = verify(bookmark.url, bookmark.username, form.password)
            if (failure != null) {
                _sourcesState.update { current ->
                    current.copy(form = current.form?.copy(saving = false, failure = failure))
                }
                return@launch
            }
            try {
                if (form.originalUrl != null) {
                    sources.update(previousUrl = form.originalUrl, bookmark = bookmark, password = form.password)
                } else {
                    sources.add(bookmark = bookmark, password = form.password)
                }
            } catch (duplicate: IllegalArgumentException) {
                _sourcesState.update { current ->
                    current.copy(form = current.form?.copy(saving = false, duplicateAddress = true))
                }
                return@launch
            }
            _sourcesState.update { it.copy(form = null) }
        }
    }

    /** 进入某个连接源的库页面，根目录为该连接源的根。 */
    fun openSource(source: ConnectionSource) {
        _state.update {
            it.copy(
                stack = listOf(source.root),
                entries = emptyList(),
                loading = true,
                failure = null,
                scrollTargetLocation = null,
            )
        }
        load(source.root)
    }

    fun enter(directory: MediaFile) {
        _state.update { it.copy(stack = it.stack + directory) }
        load(directory)
    }

    /** 回到上一级；停在连接源根目录时不动，由界面离开库页面。 */
    fun navigateUp() {
        val stack = _state.value.stack
        if (stack.size <= 1) return
        val parent = stack.dropLast(1).last()
        _state.update { it.copy(stack = stack.dropLast(1)) }
        load(parent)
    }

    /** 重新读取当前目录。 */
    fun retry() {
        _state.value.currentDirectory?.let { load(it) }
    }

    /** 当前目录的原始条目：媒体列表过滤掉的字幕文件也在；停在根目录或读不出来时为空。 */
    suspend fun directoryEntries(): List<MediaFile> {
        val directory = _state.value.currentDirectory ?: return emptyList()
        return try {
            files.children(directory)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            emptyList()
        }
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

    /** 本地源解析不出来（比如授权失效）时仍列出，好让用户能删除它。 */
    private suspend fun resolveLocalSource(location: String): ConnectionSource {
        val root = runCatching { library.resolveDirectory(location) }.getOrNull()
            ?: MediaFile(
                name = location.substringAfterLast('/').ifBlank { location },
                location = location,
                isDirectory = true,
            )
        return ConnectionSource(protocol = SourceProtocol.Local, name = root.name, location = location, root = root)
    }

    private suspend fun resolveNetworkSource(bookmark: NetworkBookmark): ConnectionSource? =
        runCatching { library.resolveDirectory(bookmark.url) }.getOrNull()?.let { root ->
            ConnectionSource(
                protocol = bookmark.protocol,
                name = bookmark.displayName,
                location = bookmark.url,
                root = root.copy(name = bookmark.displayName),
            )
        }

    private fun normalizedBookmark(form: NetworkSourceForm): NetworkBookmark? {
        val address = when (form.protocol) {
            SourceProtocol.Smb -> SmbAddress.normalize(form.address)
            SourceProtocol.WebDav -> WebDavAddress.normalize(form.address)
            SourceProtocol.Local -> null
        } ?: return null
        return NetworkBookmark(
            protocol = form.protocol,
            name = form.name.trim(),
            address = address,
            path = normalizeRelativePath(form.path),
            username = form.username,
        )
    }

    private suspend fun verify(url: String, username: String, password: String): MediaFailure? = try {
        verifier.verify(url, username, password)
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (problem: Exception) {
        problem.toFailure()
    }

    private fun Throwable?.toFailure(): MediaFailure? = when (this) {
        null -> null
        is MediaSourceException -> failure
        else -> MediaFailure.Unknown
    }

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
                val tree = RoutingMediaFileTree(
                    mapOf(
                        RoutingMediaFileTree.SAF_SCHEME to SafMediaFileTree(appContext),
                        SmbLocation.SCHEME to SmbMediaFileTree(smbConnector, smbLocator),
                        WebDavAddress.SCHEME_HTTP to WebDavMediaFileTree(webDavTransport, webDavLocator),
                        WebDavAddress.SCHEME_HTTPS to WebDavMediaFileTree(webDavTransport, webDavLocator),
                    ),
                )
                LibraryViewModel(
                    library = ProgressCleanupMediaLibrary(
                        DirectoryMediaLibrary(tree),
                        RoomPlaybackProgressRepository.create(appContext),
                    ),
                    files = tree,
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
