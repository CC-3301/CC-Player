package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.DirectoryMediaLibrary
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.ProgressCleanupMediaLibrary
import com.cc3301.ccplayer.network.FakeBookmarkStore
import com.cc3301.ccplayer.network.FakeCredentialStore
import com.cc3301.ccplayer.network.NetworkBookmark
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.network.RoutingNetworkStreams
import com.cc3301.ccplayer.network.withPlaybackLocation
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import com.cc3301.ccplayer.progress.MediaProgress
import com.cc3301.ccplayer.smb.FakeSmbConnector
import com.cc3301.ccplayer.smb.SmbLocation
import com.cc3301.ccplayer.smb.SmbLocator
import com.cc3301.ccplayer.smb.SmbMediaFileTree
import com.cc3301.ccplayer.smb.SmbStreams
import com.cc3301.ccplayer.smb.fileEntry
import com.cc3301.ccplayer.webdav.FakeWebDavTransport
import com.cc3301.ccplayer.webdav.WebDavAddress
import com.cc3301.ccplayer.webdav.WebDavLocator
import com.cc3301.ccplayer.webdav.WebDavStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络媒体的进度记录以原始位置为键：回环地址只交给播放核心，不是条目的身份。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkPlaybackProgressTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()
    private val sources = NetworkSourceRepository(FakeBookmarkStore(), FakeCredentialStore())
    private val smbConnector = FakeSmbConnector()
    private val webDavTransport = FakeWebDavTransport()

    private val openedStreams = mutableListOf<NetworkStreams>()

    private val smbRoot = "smb://nas/media"
    private val smbMovie = networkMedia("电影.mkv", "$smbRoot/电影.mkv")

    private val webDavRoot = "https://dav.example.com/remote.php/dav/files/cc"
    private val webDavMovie = networkMedia("电影.mkv", "$webDavRoot/电影.mkv")

    @After
    fun tearDown() {
        openedStreams.forEach { streams -> streams.close() }
    }

    private suspend fun addSource(url: String, username: String = "cc") {
        sources.add(NetworkBookmark.fromUrl(url, username)!!, password = "secret")
    }

    @Test
    fun `SMB 媒体的记录键是原始位置，交给引擎的是回环地址`() = runTest {
        addSource(smbRoot)
        smbConnector.addFile("media", "电影.mkv", payload)
        val entry = playable(smbMovie)

        open(backgroundScope, entry)
        engine.reportPositionChanged(30_000, 60_000)
        runCurrent()

        assertTrue(entry.playbackLocation.startsWith(LOOPBACK_PREFIX))
        assertEquals(smbMovie.location, entry.location)
        assertEquals(setOf(smbMovie.location), progress.records.keys)
        assertEquals(listOf(entry.playbackLocation), engine.startedUrls)
    }

    @Test
    fun `WebDAV 媒体的记录键是原始位置，交给引擎的是回环地址`() = runTest {
        addSource(webDavRoot)
        webDavTransport.addFile(webDavMovie.location, payload)
        val entry = playable(webDavMovie)

        open(backgroundScope, entry)
        engine.reportPositionChanged(30_000, 60_000)
        runCurrent()

        assertTrue(entry.playbackLocation.startsWith(LOOPBACK_PREFIX))
        assertEquals(webDavMovie.location, entry.location)
        assertEquals(setOf(webDavMovie.location), progress.records.keys)
        assertEquals(listOf(entry.playbackLocation), engine.startedUrls)
    }

    @Test
    fun `重启后回环地址变了，按同一原始位置仍能续播`() = runTest {
        addSource(smbRoot)
        smbConnector.addFile("media", "电影.mkv", payload)
        val first = playable(smbMovie)

        open(backgroundScope, first)
        engine.reportPositionChanged(30_000, 60_000)
        runCurrent()

        val second = playable(smbMovie)
        open(backgroundScope, second)
        runCurrent()

        assertNotEquals(first.playbackLocation, second.playbackLocation)
        assertEquals(second.playbackLocation, engine.startedUrls.last())
        assertEquals(listOf(30_000L), engine.seekPositions)
        assertEquals(setOf(smbMovie.location), progress.records.keys)
    }

    @Test
    fun `进度仓储的调用位置里没有回环地址`() = runTest {
        addSource(smbRoot)
        smbConnector.addFile("media", "电影.mkv", payload)
        val entry = playable(smbMovie)
        val controller = open(backgroundScope, entry)

        engine.reportPositionChanged(30_000, 60_000)
        runCurrent()
        controller.stop()
        runCurrent()

        assertTrue(progress.calledLocations.isNotEmpty())
        assertTrue(progress.calledLocations.none { it.startsWith(LOOPBACK_PREFIX) })
        assertEquals(setOf(smbMovie.location), progress.calledLocations.toSet())
    }

    @Test
    fun `网络媒体移走后清理按原始位置删掉记录`() = runTest {
        addSource(smbRoot)
        smbConnector.addDirectory("media", "", fileEntry("还在.mkv"))
        progress.records["$smbRoot/还在.mkv"] = record("$smbRoot/还在.mkv")
        progress.records["$smbRoot/已移走.mkv"] = record("$smbRoot/已移走.mkv")
        val library = ProgressCleanupMediaLibrary(
            DirectoryMediaLibrary(SmbMediaFileTree(smbConnector, SmbLocator(sources))),
            progress,
        )

        library.list(MediaFile(name = "media", location = smbRoot, isDirectory = true, source = MediaSource.Network))

        assertEquals(setOf("$smbRoot/还在.mkv"), progress.records.keys)
    }

    /** 起播 [entry] 并等到媒体加载完成。 */
    private fun open(scope: CoroutineScope, entry: MediaFile): PlaybackController =
        PlaybackController(
            engine,
            progress,
            scope,
            nowMillis = { 0L },
            probeTarget = alwaysReadableProbe,
        ).also { controller ->
            controller.openPlaylist(listOf(entry), entry)
            engine.reportMediaLoaded()
        }

    private fun playable(media: MediaFile): MediaFile = media.withPlaybackLocation(newStreams())

    private fun newStreams(): NetworkStreams = RoutingNetworkStreams(
        mapOf(
            SmbLocation.SCHEME to SmbStreams(smbConnector, SmbLocator(sources)),
            WebDavAddress.SCHEME_HTTPS to WebDavStreams(webDavTransport, WebDavLocator(sources)),
        ),
    ).also { openedStreams += it }

    private companion object {
        const val LOOPBACK_PREFIX = "http://127.0.0.1:"
        val payload = ByteArray(4096) { (it % 241).toByte() }

        fun record(location: String) = MediaProgress(location, 5_000, 60_000, 1_700_000_000_000)

        fun networkMedia(name: String, location: String): MediaFile = MediaFile(
            name = name,
            location = location,
            size = payload.size.toLong(),
            lastModified = 1_700_000_000_000,
            source = MediaSource.Network,
        )
    }
}
