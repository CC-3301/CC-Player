package com.cc3301.ccplayer.playback

/** 解码模式：硬解优先（mpv 解码失败时回退软解）或强制软解。 */
enum class DecodeMode { Hardware, Software }
