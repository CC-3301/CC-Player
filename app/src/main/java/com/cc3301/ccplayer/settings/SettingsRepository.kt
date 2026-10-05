package com.cc3301.ccplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 列表视图偏好：单列列表或网格。 */
enum class ViewMode { List, Grid }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 应用设置的持久化：媒体根目录与列表视图偏好。
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /** 用户添加的媒体根目录位置，顺序即添加顺序。 */
    val rootLocations: Flow<List<String>> = dataStore.data.map { preferences -> preferences.rootLocations() }

    val viewMode: Flow<ViewMode> = dataStore.data.map { preferences ->
        ViewMode.entries.firstOrNull { it.name == preferences[VIEW_MODE] } ?: ViewMode.List
    }

    suspend fun addRoot(location: String) {
        dataStore.edit { preferences ->
            val roots = preferences.rootLocations()
            if (location !in roots) preferences[ROOTS] = (roots + location).joinToString("\n")
        }
    }

    suspend fun setViewMode(mode: ViewMode) {
        dataStore.edit { preferences -> preferences[VIEW_MODE] = mode.name }
    }

    companion object {
        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.applicationContext.settingsDataStore)

        /** 根目录位置用换行拼接成一个值：URI 不含换行，顺序也就能保住。 */
        private val ROOTS = stringPreferencesKey("media_roots")
        private val VIEW_MODE = stringPreferencesKey("view_mode")

        private fun Preferences.rootLocations(): List<String> =
            this[ROOTS].orEmpty().lines().filter { it.isNotBlank() }
    }
}
