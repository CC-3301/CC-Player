package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFile

/**
 * 后台准备完成的播放请求：等播放服务与 Surface 就绪后只起播一次。
 */
class PendingPlayback {

    private var request: Request? = null

    fun set(current: MediaFile, playlist: List<MediaFile>, directoryEntries: List<MediaFile>) {
        request = Request(current, playlist, directoryEntries)
    }

    fun clear() {
        request = null
    }

    /** 就绪时取走请求并起播；未就绪或没有请求时不动作。 */
    fun start(canStart: Boolean, onStart: (MediaFile, List<MediaFile>, List<MediaFile>) -> Unit) {
        if (!canStart) return
        val pending = request ?: return
        request = null
        onStart(pending.current, pending.playlist, pending.directoryEntries)
    }

    private data class Request(
        val current: MediaFile,
        val playlist: List<MediaFile>,
        val directoryEntries: List<MediaFile>,
    )
}
