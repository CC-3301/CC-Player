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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 列表视图偏好：单列列表或网格。 */
enum class ViewMode { List, Grid }

/** 起播位置：始终从头播，或接着上次位置播。 */
enum class ResumeMode { FromStart, LastPosition }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 应用设置的持久化：媒体根目录、列表视图与排序偏好、自动连播开关。
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

    /** 起播位置设置，默认接着上次位置播。 */
    val resumeMode: Flow<ResumeMode> = dataStore.data.map { preferences ->
        ResumeMode.entries.firstOrNull { it.name == preferences[RESUME_MODE] } ?: DEFAULT_RESUME_MODE
    }

    /** "进入列表自动滚动到上次播放位置"开关，默认开。 */
    val autoScrollToLastPlayed: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[AUTO_SCROLL_TO_LAST_PLAYED] ?: DEFAULT_AUTO_SCROLL_TO_LAST_PLAYED
    }

    /** 最近起播的媒体位置，列表据此滚动；还没播过时为 null。 */
    val lastPlayedLocation: Flow<String?> = dataStore.data.map { preferences -> preferences[LAST_PLAYED_LOCATION] }

    suspend fun addRoot(location: String) {
        dataStore.edit { preferences ->
            val roots = preferences.rootLocations()
            if (location !in roots) preferences[ROOTS] = (roots + location).joinToString("\n")
        }
    }

    suspend fun setViewMode(mode: ViewMode) {
        dataStore.edit { preferences -> preferences[VIEW_MODE] = mode.name }
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

    suspend fun setResumeMode(mode: ResumeMode) {
        dataStore.edit { preferences -> preferences[RESUME_MODE] = mode.name }
    }

    suspend fun setAutoScrollToLastPlayed(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[AUTO_SCROLL_TO_LAST_PLAYED] = enabled }
    }

    suspend fun setLastPlayedLocation(location: String) {
        dataStore.edit { preferences -> preferences[LAST_PLAYED_LOCATION] = location }
    }

    companion object {
        /** 网格可选的列数。 */
        val GRID_COLUMN_CHOICES = listOf(2, 3, 4)
        const val DEFAULT_GRID_COLUMNS = 3
        val DEFAULT_SORT_ORDER = SortOrder.NameAscending
        const val DEFAULT_AUTO_PLAY_NEXT = true
        val DEFAULT_RESUME_MODE = ResumeMode.LastPosition
        const val DEFAULT_AUTO_SCROLL_TO_LAST_PLAYED = true

        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.applicationContext.settingsDataStore)

        /** 根目录位置用换行拼接成一个值：URI 不含换行，顺序也就能保住。 */
        private val ROOTS = stringPreferencesKey("media_roots")
        private val VIEW_MODE = stringPreferencesKey("view_mode")
        private val GRID_COLUMNS = intPreferencesKey("grid_columns")
        private val SORT_ORDER = stringPreferencesKey("sort_order")
        private val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        private val RESUME_MODE = stringPreferencesKey("resume_mode")
        private val AUTO_SCROLL_TO_LAST_PLAYED = booleanPreferencesKey("auto_scroll_to_last_played")
        private val LAST_PLAYED_LOCATION = stringPreferencesKey("last_played_location")

        private fun Preferences.rootLocations(): List<String> =
            this[ROOTS].orEmpty().lines().filter { it.isNotBlank() }
    }
}
