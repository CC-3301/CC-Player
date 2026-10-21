package com.cc3301.ccplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.playback.DecodeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 列表视图偏好：单列列表或网格。 */
enum class ViewMode { List, Grid }

/** 起播位置：始终从头播，或接着上次位置播。 */
enum class ResumeMode { FromStart, LastPosition }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 应用设置的持久化：媒体根目录、列表视图与排序偏好、自动连播与后台播放开关、屏幕方向模式、解码模式。
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /** 用户添加的媒体根目录位置，顺序即添加顺序。 */
    val rootLocations: Flow<List<String>> = dataStore.data.map { preferences -> preferences.rootLocations() }

    val viewMode: Flow<ViewMode> = dataStore.data.map { preferences ->
        ViewMode.entries.firstOrNull { it.name == preferences[VIEW_MODE] } ?: ViewMode.List
    }

    /** 网格列数，取值限于 [GRID_COLUMN_CHOICES]。 */
    val gridColumns: Flow<Int> = dataStore.data.map { preferences ->
        preferences[GRID_COLUMNS]?.takeIf { it in GRID_COLUMN_CHOICES } ?: DEFAULT_GRID_COLUMNS
    }

    val sortOrder: Flow<SortOrder> = dataStore.data.map { preferences ->
        SortOrder.entries.firstOrNull { it.name == preferences[SORT_ORDER] } ?: DEFAULT_SORT_ORDER
    }

    /** "自动播放下一个"开关，默认开。 */
    val autoPlayNext: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[AUTO_PLAY_NEXT] ?: DEFAULT_AUTO_PLAY_NEXT
    }

    /** "后台播放"开关，默认开；关闭时退到后台立即暂停。 */
    val backgroundPlayback: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[BACKGROUND_PLAYBACK] ?: DEFAULT_BACKGROUND_PLAYBACK
    }

    val orientationMode: Flow<OrientationMode> = dataStore.data.map { preferences ->
        OrientationMode.entries.firstOrNull { it.name == preferences[ORIENTATION_MODE] }
            ?: DEFAULT_ORIENTATION_MODE
    }

    /** 起播位置设置，默认接着上次位置播。 */
    val resumeMode: Flow<ResumeMode> = dataStore.data.map { preferences ->
        ResumeMode.entries.firstOrNull { it.name == preferences[RESUME_MODE] } ?: DEFAULT_RESUME_MODE
    }

    /** "进入目录时自动回到上次停留的位置"开关，默认开；旧键的值在首次读取时迁移。 */
    val autoRestorePosition: Flow<Boolean> = dataStore.data.map { preferences ->
        val current = preferences[AUTO_RESTORE_POSITION]
        if (current != null) return@map current
        val legacy = preferences[LEGACY_AUTO_SCROLL_TO_LAST_PLAYED]
        if (legacy == null) {
            DEFAULT_AUTO_RESTORE_POSITION
        } else {
            runCatching {
                dataStore.edit { editable ->
                    if (editable[AUTO_RESTORE_POSITION] == null) editable[AUTO_RESTORE_POSITION] = legacy
                    editable.remove(LEGACY_AUTO_SCROLL_TO_LAST_PLAYED)
                }
            }
            legacy
        }
    }

    /** 全局解码模式，默认硬解（解码失败由 mpv 回退软解）。 */
    val decodeMode: Flow<DecodeMode> = dataStore.data.map { preferences ->
        DecodeMode.entries.firstOrNull { it.name == preferences[DECODE_MODE] } ?: DEFAULT_DECODE_MODE
    }

    /** "诊断日志"开关，默认关闭；关闭时不生成日志文件。 */
    val diagnosticsEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[DIAGNOSTICS_ENABLED] ?: DEFAULT_DIAGNOSTICS_ENABLED
    }

    suspend fun addRoot(location: String) {
        dataStore.edit { preferences ->
            val roots = preferences.rootLocations()
            if (location !in roots) preferences[ROOTS] = (roots + location).joinToString("\n")
        }
    }

    /** 移除本地连接源；只删记录，不动文件夹与其中的文件。 */
    suspend fun removeRoot(location: String) {
        dataStore.edit { preferences ->
            preferences[ROOTS] = preferences.rootLocations().filterNot { it == location }.joinToString("\n")
        }
    }

    suspend fun setViewMode(mode: ViewMode) {
        dataStore.edit { preferences -> preferences[VIEW_MODE] = mode.name }
    }

    /** 一次写入视图与网格列数，避免两次偏好发射各自重新定位；列表视图不动已保存的列数。 */
    suspend fun setView(mode: ViewMode, columns: Int) {
        require(columns in GRID_COLUMN_CHOICES) { "网格列数只能是 $GRID_COLUMN_CHOICES 之一：$columns" }
        dataStore.edit { preferences ->
            if (mode == ViewMode.Grid) preferences[GRID_COLUMNS] = columns
            preferences[VIEW_MODE] = mode.name
        }
    }

    suspend fun setGridColumns(columns: Int) {
        require(columns in GRID_COLUMN_CHOICES) { "网格列数只能是 $GRID_COLUMN_CHOICES 之一：$columns" }
        dataStore.edit { preferences -> preferences[GRID_COLUMNS] = columns }
    }

    suspend fun setSortOrder(order: SortOrder) {
        dataStore.edit { preferences -> preferences[SORT_ORDER] = order.name }
    }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[AUTO_PLAY_NEXT] = enabled }
    }

    suspend fun setBackgroundPlayback(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[BACKGROUND_PLAYBACK] = enabled }
    }

    suspend fun setOrientationMode(mode: OrientationMode) {
        dataStore.edit { preferences -> preferences[ORIENTATION_MODE] = mode.name }
    }

    suspend fun setResumeMode(mode: ResumeMode) {
        dataStore.edit { preferences -> preferences[RESUME_MODE] = mode.name }
    }

    suspend fun setAutoRestorePosition(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[AUTO_RESTORE_POSITION] = enabled }
    }

    suspend fun setDecodeMode(mode: DecodeMode) {
        dataStore.edit { preferences -> preferences[DECODE_MODE] = mode.name }
    }

    suspend fun setDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[DIAGNOSTICS_ENABLED] = enabled }
    }

    companion object {
        /** 网格可选的列数。 */
        val GRID_COLUMN_CHOICES = listOf(2, 3, 4)
        const val DEFAULT_GRID_COLUMNS = 3
        val DEFAULT_SORT_ORDER = SortOrder.NameAscending
        const val DEFAULT_AUTO_PLAY_NEXT = true
        const val DEFAULT_BACKGROUND_PLAYBACK = true
        val DEFAULT_ORIENTATION_MODE = OrientationMode.System
        val DEFAULT_RESUME_MODE = ResumeMode.LastPosition
        const val DEFAULT_AUTO_RESTORE_POSITION = true
        val DEFAULT_DECODE_MODE = DecodeMode.Hardware
        const val DEFAULT_DIAGNOSTICS_ENABLED = false

        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.applicationContext.settingsDataStore)

        /** 根目录位置用换行拼接成一个值：URI 不含换行，顺序也就能保住。 */
        private val ROOTS = stringPreferencesKey("media_roots")
        private val VIEW_MODE = stringPreferencesKey("view_mode")
        private val GRID_COLUMNS = intPreferencesKey("grid_columns")
        private val SORT_ORDER = stringPreferencesKey("sort_order")
        private val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        private val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        private val ORIENTATION_MODE = stringPreferencesKey("orientation_mode")
        private val RESUME_MODE = stringPreferencesKey("resume_mode")
        private val AUTO_RESTORE_POSITION = booleanPreferencesKey("auto_restore_position")
        private val LEGACY_AUTO_SCROLL_TO_LAST_PLAYED = booleanPreferencesKey("auto_scroll_to_last_played")
        private val DECODE_MODE = stringPreferencesKey("decode_mode")
        private val DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")

        private fun Preferences.rootLocations(): List<String> =
            this[ROOTS].orEmpty().lines().filter { it.isNotBlank() }
    }
}
