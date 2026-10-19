package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.playback.LoopMode
import com.cc3301.ccplayer.settings.OrientationMode
import com.cc3301.ccplayer.settings.ViewMode

/** 设置里的一个选项文案：字符串资源与可选格式参数（如网格列数）。 */
data class OptionLabel(val text: Int, val formatArg: Int? = null)

/** 视图选项：列表与 2/3/4 列网格。 */
data class ViewChoice(val viewMode: ViewMode, val gridColumns: Int)

/** 视图选项文案；网格选项带上实际列数，避免占位符漏填。 */
fun viewChoiceLabel(choice: ViewChoice): OptionLabel = when (choice.viewMode) {
    ViewMode.List -> OptionLabel(R.string.library_view_list)
    ViewMode.Grid -> OptionLabel(R.string.library_view_grid_columns, choice.gridColumns)
}

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
