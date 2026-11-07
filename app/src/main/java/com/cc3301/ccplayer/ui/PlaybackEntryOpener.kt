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
 * 每次打开由入口阶段分配代际；准备的成功与失败都带同一代际才被接受，
 * 迟到的结果一律丢弃，不得起播、不得离开播放页。
 */
class PlaybackEntryOpener(
    private val scope: CoroutineScope,
    private val entryState: PlaybackEntryState,
    private val directoryEntries: suspend () -> List<MediaFile>,
    private val networkStreams: () -> NetworkStreams,
    private val onEnterPlayer: () -> Unit,
    private val onStartPlayback: (
        current: MediaFile,
        playlist: List<MediaFile>,
        directoryEntries: List<MediaFile>,
        generation: Int,
    ) -> Unit,
    private val onShowFailure: (MediaFailure) -> Unit,
    private val onLeavePlayer: () -> Unit,
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
) {

    private var job: Job? = null

    /** 这次准备用到的字节源出口：取消时用它丢掉探测预置的读句柄。 */
    private var preparedStreams: NetworkStreams? = null

    /**
     * 点开 [entry]。[enterBeforePreparing] 为真时立即进入播放页再准备；
     * 为假时准备完成后才进入，保持原行为。
     *
     * 返回这次打开分配到的代际。
     */
    fun open(entry: MediaFile, entries: List<MediaFile>, enterBeforePreparing: Boolean): Int {
        cancel()
        val generation = entryState.begin(preparing = enterBeforePreparing)
        if (enterBeforePreparing) onEnterPlayer()
        val started = scope.launch(start = CoroutineStart.LAZY) {
            val prepared = try {
                val plainEntries = directoryEntries()
                Result.success(prepare(entry, entries, plainEntries))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Result.failure<PreparedPlayback>(failure)
            }
            // 准备已结束：预置句柄交给播放或随失败丢掉，不再挂在入口上。
            preparedStreams = null
            // 阻塞的准备调用不响应取消，回到这里时可能已开始另一次打开：代际不同就丢弃。
            if (!entryState.isCurrent(generation)) return@launch
            prepared.fold(
                onSuccess = { playback ->
                    if (!entryState.prepared(generation)) return@launch
                    if (!enterBeforePreparing) onEnterPlayer()
                    onStartPlayback(
                        playback.current,
                        playback.playlist,
                        playback.directoryEntries,
                        generation,
                    )
                },
                onFailure = { failure ->
                    val mediaFailure = (failure as? MediaSourceException)?.failure ?: MediaFailure.Unknown
                    if (!entryState.failed(generation)) return@launch
                    diagnostics.log { "准备失败: ${entry.name} ($mediaFailure)" }
                    onShowFailure(mediaFailure)
                    if (enterBeforePreparing) onLeavePlayer()
                },
            )
        }
        job = started
        started.start()
        return generation
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
        preparedStreams = streams
        streams.probe(entry.location)
        return PreparedPlayback(
            entry.withPlaybackLocation(streams),
            entries.map { it.withPlaybackLocation(streams) },
            plainEntries.map { it.withPlaybackLocation(streams) },
        )
    }

    /** 取消尚未完成的准备：连同它探测预置的读句柄一起丢掉，不等下一次探测或关闭。 */
    fun cancel() {
        job?.cancel()
        job = null
        preparedStreams?.discardPreparedSource()
        preparedStreams = null
    }

    private data class PreparedPlayback(
        val current: MediaFile,
        val playlist: List<MediaFile>,
        val directoryEntries: List<MediaFile>,
    )
}
