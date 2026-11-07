package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile

/** 控制器测试统一使用的入口代际。 */
internal const val TEST_ENTRY_GENERATION = 1

/**
 * 测试用的打开入口：先声明入口代际，再按生产签名交播放列表。
 * 生产路径由界面在进入播放页时声明代际，测试用同一个固定值模拟。
 */
internal fun PlaybackController.openPlaylistForTest(
    files: List<MediaFile>,
    current: MediaFile,
    directoryEntries: List<MediaFile> = emptyList(),
) {
    beginOpenHandover(generation = TEST_ENTRY_GENERATION)
    openPlaylist(
        files = files,
        current = current,
        generation = TEST_ENTRY_GENERATION,
        directoryEntries = directoryEntries,
    )
}
