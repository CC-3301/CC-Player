package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.playback.LoopMode
import com.cc3301.ccplayer.settings.OrientationMode

/** 控制条与设置页共用的取值文案，同一取值只在此处映射一次。 */
fun decodeModeLabel(mode: DecodeMode): Int = when (mode) {
    DecodeMode.Hardware -> R.string.player_decode_hardware
    DecodeMode.Software -> R.string.player_decode_software
}

fun loopModeLabel(mode: LoopMode): Int = when (mode) {
    LoopMode.Sequential -> R.string.player_loop_sequential
    LoopMode.ListLoop -> R.string.player_loop_list
    LoopMode.SingleLoop -> R.string.player_loop_single
}

fun orientationModeLabel(mode: OrientationMode): Int = when (mode) {
    OrientationMode.System -> R.string.player_orientation_system
    OrientationMode.Landscape -> R.string.player_orientation_landscape
    OrientationMode.Portrait -> R.string.player_orientation_portrait
    OrientationMode.FollowVideo -> R.string.player_orientation_follow_video
}

fun sortLabel(order: SortOrder): Int = when (order) {
    SortOrder.NameAscending -> R.string.library_sort_name_ascending
    SortOrder.NameDescending -> R.string.library_sort_name_descending
    SortOrder.LastModifiedNewestFirst -> R.string.library_sort_time_newest_first
    SortOrder.LastModifiedOldestFirst -> R.string.library_sort_time_oldest_first
}
