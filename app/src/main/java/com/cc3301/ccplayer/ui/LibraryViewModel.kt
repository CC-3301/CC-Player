package com.cc3301.ccplayer.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cc3301.ccplayer.library.DirectoryMediaLibrary
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaLibrary
import com.cc3301.ccplayer.library.ProgressCleanupMediaLibrary
import com.cc3301.ccplayer.library.SafMediaFileTree
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.library.sortedForDisplay
import com.cc3301.ccplayer.progress.RoomPlaybackProgressRepository
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.settings.ViewMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val failed: Boolean = false,
) {
    val currentDirectory: MediaFile? get() = stack.lastOrNull()

    /** 列表实际展示的次序：子目录在前，组内按当前排序选项。 */
    val visibleEntries: List<MediaFile> get() = entries.sortedForDisplay(sortOrder)

    /** 套用排序偏好；排序选项变了才递增 [sortRevision]。 */
    fun withSortOrder(order: SortOrder): LibraryState =
        copy(sortOrder = order, sortRevision = if (order == sortOrder) sortRevision else sortRevision + 1)

    /** 目录读完后套用结果，并定出进入列表要滚动到的条目。 */
    fun withLoadedEntries(entries: List<MediaFile>, failed: Boolean): LibraryState {
        val target = scrollTargetIn(entries)
        return copy(
            entries = entries,
            loading = false,
            failed = failed,
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
 * 媒体列表的状态持有者：读设置里的根目录与视图偏好，按当前目录向 [MediaLibrary] 要列表。
 */
class LibraryViewModel(
    private val library: MediaLibrary,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            settings.rootLocations.collect { locations ->
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

    /** 目录选择器返回后调用。 */
    fun addRoot(location: String) {
        viewModelScope.launch {
            settings.addRoot(location)
            _state.update { it.copy(roots = resolveRoots(settings.rootLocations.first())) }
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
            _state.update { it.copy(entries = emptyList(), loading = false, failed = false) }
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
        _state.update { it.copy(loading = true, failed = false) }
        loadJob = viewModelScope.launch {
            val result = library.list(directory)
            _state.update { it.withLoadedEntries(result.getOrDefault(emptyList()), result.isFailure) }
        }
    }

    /** 位置读不出来的根目录不展示：授权已失效时它在界面上没有用处。 */
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
        /** 生产入口：SAF 文件树（列举目录后清已消失媒体的进度记录）+ DataStore 设置。 */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    library = ProgressCleanupMediaLibrary(
                        DirectoryMediaLibrary(SafMediaFileTree(context)),
                        RoomPlaybackProgressRepository.create(context),
                    ),
                    settings = SettingsRepository.create(context),
                )
            }
        }
    }
}
