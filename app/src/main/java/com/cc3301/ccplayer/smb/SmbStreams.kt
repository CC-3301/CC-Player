package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.streaming.ByteSource
import com.cc3301.ccplayer.streaming.LoopbackStreamServer
import com.cc3301.ccplayer.streaming.NetworkReadPriority
import com.cc3301.ccplayer.streaming.ReadKind
import kotlinx.coroutines.runBlocking

/**
 * 网络媒体播放接缝：把 SMB 位置映到本机回环地址，播放核心只拿到本机地址。
 *
 * 一个位置恒得同一个地址，播放列表里的每一项都能先换成地址；字节源在播放器读到时才建立。
 * 探测与播放共用同一条会话，探测只做存活确认，播放不再完整握手一次。
 */
class SmbStreams(
    private val sessions: SmbSessions,
    private val locator: SmbLocator,
    diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    private val server: LoopbackStreamServer = LoopbackStreamServer(diagnostics = diagnostics),
) : NetworkStreams {

    /** 起播前试连一次：会话建立或复用并做存活确认；连不上或位置不属于任何书签时抛出来源失败异常。 */
    override suspend fun probe(location: String) {
        sessions.probe(targetOf(location))
    }

    /** 位置对应的本机地址。 */
    override fun urlFor(location: String): String = server.serve(location) { openBlocking(location) }

    override fun returnSource(location: String) {
        server.returnSource(location)
    }

    override fun discardByteSources() {
        server.discardSources()
    }

    /** SMB 探测只取会话做存活确认，不留读句柄，没有要丢的预置句柄。 */
    override fun discardPreparedSource() = Unit

    override fun close() {
        server.close()
    }

    /** 代理在自己的线程上取流，这里阻塞等打开完成。 */
    private fun openBlocking(location: String): ByteSource = runBlocking {
        NetworkReadPriority.shared.wrap(sessions.openRead(targetOf(location)), ReadKind.Playback)
    }

    private suspend fun targetOf(location: String): SmbTarget =
        locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
}
