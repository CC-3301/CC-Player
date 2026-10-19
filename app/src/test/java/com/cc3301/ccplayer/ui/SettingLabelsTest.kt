package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.settings.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 设置选项文案：网格选项必须带实际列数，不能留下占位符。 */
class SettingLabelsTest {

    @Test
    fun `列表选项文案`() {
        assertEquals(
            OptionLabel(R.string.library_view_list),
            viewChoiceLabel(ViewChoice(ViewMode.List, gridColumns = 3)),
        )
    }

    @Test
    fun `视图选项顺序为列表与 2、3、4 列网格`() {
        assertEquals(
            listOf(
                ViewChoice(ViewMode.List, 3),
                ViewChoice(ViewMode.Grid, 2),
                ViewChoice(ViewMode.Grid, 3),
                ViewChoice(ViewMode.Grid, 4),
            ),
            viewChoices,
        )
    }

    @Test
    fun `设置页解码文案与播放器文案分开`() {
        assertEquals(R.string.settings_decode_hardware, settingsDecodeModeLabel(DecodeMode.Hardware))
        assertEquals(R.string.settings_decode_software, settingsDecodeModeLabel(DecodeMode.Software))
        assertEquals(R.string.player_decode_hardware, decodeModeLabel(DecodeMode.Hardware))
        assertEquals(R.string.player_decode_software, decodeModeLabel(DecodeMode.Software))
        assertNotEquals(settingsDecodeModeLabel(DecodeMode.Hardware), decodeModeLabel(DecodeMode.Hardware))
    }

    @Test
    fun `网格选项按列数格式化`() {
        listOf(2, 3, 4).forEach { columns ->
            assertEquals(
                OptionLabel(R.string.library_view_grid_columns, formatArg = columns),
                viewChoiceLabel(ViewChoice(ViewMode.Grid, gridColumns = columns)),
            )
        }
    }
}
