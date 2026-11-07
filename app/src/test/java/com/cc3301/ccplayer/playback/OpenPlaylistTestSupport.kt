package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile

/** 控制器测试统一使用的入口代际；每条用例显式传入。 */
internal const val TEST_ENTRY_GENERATION = 1

/** 测试用打开入口：代际由调用方显式给出，不隐藏任何生产契约。 */
internal fun PlaybackController.openPlaylistForTest(
    generation: Int,
    files: List<MediaFile>,
    current: MediaFile,
    directoryEntries: List<MediaFile> = emptyList(),
) {
    openPlaylist(
        files = files,
        current = current,
        generation = generation,
        directoryEntries = directoryEntries,
    )
}
