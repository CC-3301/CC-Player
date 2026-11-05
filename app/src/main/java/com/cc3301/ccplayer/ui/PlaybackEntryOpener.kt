package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.network.withPlaybackLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 浏览页点开媒体的入口：视频先同步切进播放页，再在后台解析外挂字幕目录与网络连接；
 * 音频等媒体准备完成后才进入播放页。
 *
 * 网络媒体需要回环地址才能交给播放核心，因此探测完成前不开始播放。
 * 准备失败时先提示原因，再由界面按现有播放会话收尾。
 */
class PlaybackEntryOpener(
    private val scope: CoroutineScope,
    private val directoryEntries: suspend () -> List<MediaFile>,
    private val networkStreams: () -> NetworkStreams,
    private val onEnterPlayer: (preparing: Boolean) -> Unit,
    private val onStartPlayback: (current: MediaFile, playlist: List<MediaFile>, directoryEntries: List<MediaFile>) -> Unit,
    private val onShowFailure: (MediaFailure) -> Unit,
    private val onLeavePlayer: () -> Unit,
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
) {

    private var job: Job? = null

    /** 本次点开是否已经进入播放页；音频准备失败时仍停在浏览页。 */
    private var playerEntered = false

    /**
     * 点开 [entry]。[enterBeforePreparing] 为真时立即进入播放页再准备；
     * 为假时准备完成后才进入，保持原行为。
     */
    fun open(entry: MediaFile, entries: List<MediaFile>, enterBeforePreparing: Boolean) {
        job?.cancel()
        playerEntered = false
        if (enterBeforePreparing) {
            onEnterPlayer(true)
            playerEntered = true
        }
        val started = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val plainEntries = directoryEntries()
                val prepared = prepare(entry, entries, plainEntries)
                if (!enterBeforePreparing) {
                    onEnterPlayer(false)
                    playerEntered = true
                }
                onStartPlayback(prepared.current, prepared.playlist, prepared.directoryEntries)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: MediaSourceException) {
                diagnostics.log { "准备失败: ${entry.name} (${failure.failure})" }
                finishWithFailure(failure.failure)
            } catch (failure: Exception) {
                diagnostics.log { "准备失败: ${entry.name} (Unknown)" }
                finishWithFailure(MediaFailure.Unknown)
            }
        }
        job = started
        started.start()
    }

    /** 网络媒体换成回环地址；本地媒体原样返回。 */
    private suspend fun prepare(
        entry: MediaFile,
        entries: List<MediaFile>,
        plainEntries: List<MediaFile>,
    ): PreparedPlayback {
        if (entry.source != MediaSource.Network) {
            return PreparedPlayback(entry, entries, plainEntries)
        }
        val streams = networkStreams()
        streams.probe(entry.location)
        return PreparedPlayback(
            entry.withPlaybackLocation(streams),
            entries.map { it.withPlaybackLocation(streams) },
            plainEntries.map { it.withPlaybackLocation(streams) },
        )
    }

    /** 准备失败：先提示原因；已进入播放页才退回列表。先清任务再回调，避免回调里取消自身。 */
    private fun finishWithFailure(failure: MediaFailure) {
        job = null
        onShowFailure(failure)
        if (playerEntered) onLeavePlayer()
    }

    /** 离开播放页时取消尚未完成的准备。 */
    fun cancel() {
        job?.cancel()
        job = null
    }

    private data class PreparedPlayback(
        val current: MediaFile,
        val playlist: List<MediaFile>,
        val directoryEntries: List<MediaFile>,
    )
}
