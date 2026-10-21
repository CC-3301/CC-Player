package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.network.withPlaybackLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 浏览页点开媒体的入口：先同步切进播放页，再在后台解析外挂字幕目录与网络连接。
 *
 * 网络媒体需要回环地址才能交给播放核心，因此探测完成前不开始播放；
 * 进入播放页不等待这些准备。准备失败时回调 [onFailure]，由界面退回列表并提示原因。
 */
class PlaybackEntryOpener(
    private val scope: CoroutineScope,
    private val directoryEntries: suspend () -> List<MediaFile>,
    private val networkStreams: () -> NetworkStreams,
    private val onEnterPlayer: () -> Unit,
    private val onReady: (current: MediaFile, playlist: List<MediaFile>, directoryEntries: List<MediaFile>) -> Unit,
    private val onFailure: (MediaFailure) -> Unit,
) {

    private var job: Job? = null

    /** 点开 [entry]：立即进入播放页，准备完成后以 [entries] 为播放列表起播。 */
    fun open(entry: MediaFile, entries: List<MediaFile>) {
        job?.cancel()
        onEnterPlayer()
        job = scope.launch {
            try {
                val plainEntries = directoryEntries()
                if (entry.source != MediaSource.Network) {
                    onReady(entry, entries, plainEntries)
                    return@launch
                }
                val streams = networkStreams()
                streams.probe(entry.location)
                onReady(
                    entry.withPlaybackLocation(streams),
                    entries.map { it.withPlaybackLocation(streams) },
                    plainEntries.map { it.withPlaybackLocation(streams) },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: MediaSourceException) {
                onFailure(failure.failure)
            } catch (failure: Exception) {
                onFailure(MediaFailure.Unknown)
            }
        }
    }

    /** 离开播放页时取消尚未完成的准备。 */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
