package com.cc3301.ccplayer.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.Base64
import kotlinx.coroutines.flow.first

/** 一个目录的浏览位置：第一个可见条目、它上方的滚动偏移与写入时的排序。 */
data class DirectoryPosition(
    val entryLocation: String,
    val scrollOffset: Int,
    val sortOrder: SortOrder,
)

/** 目录位置记忆的存储接缝：按连接源身份与相对目录分别读写。 */
interface DirectoryPositionStore {

    suspend fun load(sourceLocation: String, relativePath: String): DirectoryPosition?

    suspend fun save(sourceLocation: String, relativePath: String, position: DirectoryPosition)

    /** 删除某个连接源的全部位置记录。 */
    suspend fun clearSource(sourceLocation: String)
}

private val Context.directoryPositionDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "directory_positions")

/** DataStore 实现：键由连接源身份与相对目录编码而成，值与播放进度分开存放。 */
class DataStoreDirectoryPositionStore(
    private val dataStore: DataStore<Preferences>,
) : DirectoryPositionStore {

    override suspend fun load(sourceLocation: String, relativePath: String): DirectoryPosition? =
        dataStore.data.first()[positionKey(sourceLocation, relativePath)]?.let(::decode)

    override suspend fun save(sourceLocation: String, relativePath: String, position: DirectoryPosition) {
        dataStore.edit { preferences ->
            preferences[positionKey(sourceLocation, relativePath)] = encode(position)
        }
    }

    override suspend fun clearSource(sourceLocation: String) {
        val prefix = sourcePrefix(sourceLocation)
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .map { it.name }
                .filter { it.startsWith(prefix) }
                .forEach { preferences.remove(stringPreferencesKey(it)) }
        }
    }

    companion object {
        fun create(context: Context): DirectoryPositionStore =
            DataStoreDirectoryPositionStore(context.applicationContext.directoryPositionDataStore)

        private const val KEY_PREFIX = "position_"

        fun positionKey(sourceLocation: String, relativePath: String): Preferences.Key<String> =
            stringPreferencesKey(sourcePrefix(sourceLocation) + encodeSegment(relativePath))

        fun sourcePrefix(sourceLocation: String): String = KEY_PREFIX + encodeSegment(sourceLocation) + ":"

        fun encodeSegment(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        fun encode(position: DirectoryPosition): String =
            "${position.sortOrder.name}\n${position.scrollOffset}\n${position.entryLocation}"

        fun decode(value: String): DirectoryPosition? {
            val parts = value.split('\n', limit = 3)
            if (parts.size < 3) return null
            val sortOrder = SortOrder.entries.firstOrNull { it.name == parts[0] } ?: return null
            val offset = parts[1].toIntOrNull() ?: return null
            return DirectoryPosition(
                entryLocation = parts[2],
                scrollOffset = offset,
                sortOrder = sortOrder,
            )
        }
    }
}

/** 相对目录路径：连接源根目录为空串，子目录按层级用斜线拼接。 */
fun relativeDirectoryPath(stack: List<MediaFile>): String =
    stack.drop(1).joinToString("/") { it.name }

/** 列表要滚动到的位置：[entries] 为实际展示次序。 */
data class DirectoryScrollTarget(val index: Int, val offset: Int)

/**
 * 由记录算出滚动目标：关闭记忆时回到顶部；记录的排序与当前排序不一致时视为没有记录、回到顶部；
 * 记录条目还在时定位到它；条目已不存在时停在末尾；没有记录时也回到顶部。
 */
fun directoryScrollTarget(
    entries: List<MediaFile>,
    position: DirectoryPosition?,
    restore: Boolean,
    sortOrder: SortOrder,
): DirectoryScrollTarget? {
    if (entries.isEmpty()) return null
    val top = DirectoryScrollTarget(index = 0, offset = 0)
    if (!restore) return top
    val saved = position?.takeIf { it.sortOrder == sortOrder } ?: return top
    val index = entries.indexOfFirst { it.location == saved.entryLocation }
    return if (index >= 0) {
        DirectoryScrollTarget(index = index, offset = saved.scrollOffset)
    } else {
        DirectoryScrollTarget(index = entries.lastIndex, offset = 0)
    }
}
