package com.cc3301.ccplayer.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cc3301.ccplayer.library.DataStoreDirectoryPositionStore
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.network.SourceProtocol
import kotlinx.coroutines.flow.first

/** 导航协调器的完整快照：当前屏、源协议、设置页来处与库目录栈。 */
data class NavigationSnapshot(
    val screen: AppScreen,
    val sourcesProtocol: SourceProtocol,
    val settingsReturn: AppScreen,
    val librarySource: String? = null,
    val libraryStack: List<MediaFile> = emptyList(),
)

/** 导航快照的持久化接缝：冷启动没有保存状态时从这里读回上次导航。 */
interface NavigationSnapshotStore {

    suspend fun load(): NavigationSnapshot?

    suspend fun save(snapshot: NavigationSnapshot)
}

/** 不持久化导航快照的实现，供不需要冷启动恢复的构造使用。 */
object NoNavigationSnapshotStore : NavigationSnapshotStore {

    override suspend fun load(): NavigationSnapshot? = null

    override suspend fun save(snapshot: NavigationSnapshot) = Unit
}

private val Context.navigationSnapshotDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "navigation_snapshot")

/** DataStore 实现：整份导航状态编码成一个值，一次写入、一次读回。 */
class DataStoreNavigationSnapshotStore(
    private val dataStore: DataStore<Preferences>,
) : NavigationSnapshotStore {

    override suspend fun load(): NavigationSnapshot? =
        dataStore.data.first()[SNAPSHOT]?.let(::decode)

    override suspend fun save(snapshot: NavigationSnapshot) {
        dataStore.edit { preferences -> preferences[SNAPSHOT] = encode(snapshot) }
    }

    companion object {
        fun create(context: Context): NavigationSnapshotStore =
            DataStoreNavigationSnapshotStore(context.applicationContext.navigationSnapshotDataStore)

        private val SNAPSHOT = stringPreferencesKey("navigation_snapshot")

        fun encode(snapshot: NavigationSnapshot): String {
            val header = listOf(
                snapshot.screen.name,
                snapshot.sourcesProtocol.name,
                snapshot.settingsReturn.name,
                snapshot.librarySource.orEmpty(),
            ).joinToString(SEPARATOR) { DataStoreDirectoryPositionStore.encodeSegment(it) }
            val stack = snapshot.libraryStack.joinToString("\n") { entry ->
                listOf(entry.location, entry.name)
                    .joinToString(SEPARATOR) { DataStoreDirectoryPositionStore.encodeSegment(it) }
            }
            return header + "\n" + stack
        }

        fun decode(value: String): NavigationSnapshot? {
            val lines = value.split('\n')
            val header = lines.firstOrNull()?.split(SEPARATOR) ?: return null
            if (header.size != HEADER_FIELDS) return null
            val screen = decodeEnum(header[0], AppScreen.entries) ?: return null
            val protocol = decodeEnum(header[1], SourceProtocol.entries) ?: return null
            val settingsReturn = decodeEnum(header[2], AppScreen.entries) ?: return null
            val librarySource = header[3].decodeSegment()?.takeIf { it.isNotBlank() }
            val stack = lines.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
                val fields = line.split(SEPARATOR)
                if (fields.size != STACK_FIELDS) return@mapNotNull null
                val location = fields[0].decodeSegment() ?: return@mapNotNull null
                val name = fields[1].decodeSegment() ?: return@mapNotNull null
                MediaFile(name = name, location = location, isDirectory = true)
            }
            if (stack.size != lines.drop(1).count { it.isNotBlank() }) return null
            return NavigationSnapshot(
                screen = screen,
                sourcesProtocol = protocol,
                settingsReturn = settingsReturn,
                librarySource = librarySource,
                libraryStack = stack,
            )
        }

        private fun <T : Enum<T>> decodeEnum(encoded: String, entries: List<T>): T? {
            val name = encoded.decodeSegment() ?: return null
            return entries.firstOrNull { it.name == name }
        }

        private fun String.decodeSegment(): String? = runCatching {
            String(java.util.Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
        }.getOrNull()

        private const val SEPARATOR = "|"
        private const val HEADER_FIELDS = 4
        private const val STACK_FIELDS = 2
    }
}
