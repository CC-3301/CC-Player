package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.network.SourceProtocol
import com.cc3301.ccplayer.settings.FakePreferencesDataStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 导航快照：整份状态一次写入、一次读回，损坏内容按没有快照处理。 */
class NavigationSnapshotStoreTest {

    private val dataStore = FakePreferencesDataStore()
    private val store = DataStoreNavigationSnapshotStore(dataStore)

    @Test
    fun `完整导航状态写入后读回`() = runTest {
        val snapshot = NavigationSnapshot(
            screen = AppScreen.Library,
            sourcesProtocol = SourceProtocol.WebDav,
            settingsReturn = AppScreen.Library,
            librarySource = "https://dav.example.com/cc",
            libraryStack = listOf(
                MediaFile("cc", "https://dav.example.com/cc", isDirectory = true),
                MediaFile("剧集", "https://dav.example.com/cc/剧集", isDirectory = true),
            ),
        )

        store.save(snapshot)

        assertEquals(snapshot, store.load())
    }

    @Test
    fun `停在首页时来源与目录栈为空`() = runTest {
        val snapshot = NavigationSnapshot(
            screen = AppScreen.Home,
            sourcesProtocol = SourceProtocol.Local,
            settingsReturn = AppScreen.Home,
        )

        store.save(snapshot)

        assertEquals(snapshot, store.load())
    }

    @Test
    fun `损坏的快照按没有记录处理`() = runTest {
        store.save(NavigationSnapshot(AppScreen.Home, SourceProtocol.Local, AppScreen.Home))
        dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(
                    androidx.datastore.preferences.core.stringPreferencesKey("navigation_snapshot"),
                    "not-a-snapshot",
                )
            }
        }

        assertNull(store.load())
    }
}
