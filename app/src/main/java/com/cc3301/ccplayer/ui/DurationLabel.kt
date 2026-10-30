package com.cc3301.ccplayer.ui

import kotlin.math.abs

/** 时长文本：不足一小时为 `分:秒`，满一小时为 `时:分:秒`；分钟补足两位。 */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** 带符号的时长文本：前进为 `+00:15`，后退为 `-00:15`。 */
fun signedDuration(millis: Long): String =
    (if (millis < 0) "-" else "+") + formatDuration(abs(millis))

/** 拖动提示里的增减量文本：方括号包裹带符号时长，形如 `[+00:20]`。 */
fun seekHintDeltaText(deltaMillis: Long): String = "[${signedDuration(deltaMillis)}]"

/** 进度条显示的进度：拖动预览优先，并限制在总时长内。 */
fun seekBarProgressMillis(positionMillis: Long, durationMillis: Long, previewMillis: Long?): Long =
    (previewMillis ?: positionMillis).coerceIn(0L, durationMillis.coerceAtLeast(0L))

/** 进度条左右文本与 seek 可用性；时长未知时总时长显示 [unknownText] 且禁用 seek。 */
data class SeekBarLabels(
    val current: String,
    val total: String,
    val seekEnabled: Boolean,
)

fun seekBarLabels(
    positionMillis: Long,
    durationMillis: Long,
    previewMillis: Long?,
    unknownText: String,
): SeekBarLabels {
    val known = durationMillis > 0L
    val currentMillis = if (known) {
        seekBarProgressMillis(positionMillis, durationMillis, previewMillis)
    } else {
        positionMillis.coerceAtLeast(0L)
    }
    return SeekBarLabels(
        current = formatDuration(currentMillis),
        total = if (known) formatDuration(durationMillis) else unknownText,
        seekEnabled = known,
    )
}
