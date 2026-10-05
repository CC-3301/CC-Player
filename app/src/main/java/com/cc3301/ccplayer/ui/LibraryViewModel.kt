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
import com.cc3301.ccplayer.library.SafMediaFileTree
import com.cc3301.ccplayer.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val entries: List<MediaFile> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val currentDirectory: MediaFile? get() = stack.lastOrNull()
}

/**
 * 媒体列表的状态持有者：读设置里的根目录，按当前目录向 [MediaLibrary] 要列表。
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

    private fun load(directory: MediaFile) {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, failed = false) }
        loadJob = viewModelScope.launch {
            val result = library.list(directory)
            _state.update {
                it.copy(entries = result.getOrDefault(emptyList()), loading = false, failed = result.isFailure)
            }
        }
    }

    /** 位置读不出来的根目录不展示：授权已失效时它在界面上没有用处。 */
    private suspend fun resolveRoots(locations: List<String>): List<MediaFile> =
        locations.mapNotNull { library.resolveDirectory(it) }

    companion object {
        /** 生产入口：SAF 文件树 + DataStore 设置。 */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    library = DirectoryMediaLibrary(SafMediaFileTree(context)),
                    settings = SettingsRepository.create(context),
                )
            }
        }
    }
}
