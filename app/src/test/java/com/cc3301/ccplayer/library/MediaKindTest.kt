package com.cc3301.ccplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaKindTest {

    @Test
    fun `视频扩展名识别为视频`() {
        listOf("movie.mp4", "movie.mkv", "clip.webm", "old.avi", "film.mov", "flash.flv", "stream.ts", "windows.wmv")
            .forEach { assertKind(MediaKind.Video, it) }
    }

    @Test
    fun `音频扩展名识别为音频`() {
        listOf("song.mp3", "voice.opus", "sound.wav", "album.flac", "ringtone.m4a", "podcast.aac", "track.ogg")
            .forEach { assertKind(MediaKind.Audio, it) }
    }

    @Test
    fun `扩展名不区分大小写`() {
        assertKind(MediaKind.Video, "MOVIE.MKV")
        assertKind(MediaKind.Audio, "Song.Mp3")
    }

    @Test
    fun `非媒体文件没有种类`() {
        listOf("说明.txt", "封面.jpg", "字幕.srt", "无扩展名", "movie.mp4.bak").forEach { name ->
            assertNull(name, mediaKindOf(name))
        }
    }

    private fun assertKind(expected: MediaKind, fileName: String) {
        assertEquals(fileName, expected, mediaKindOf(fileName))
    }
}
