package com.cc3301.ccplayer.playback

/**
 * libmpv 在 init 之前必须设置好的选项。
 *
 * 日志桥按终端级别请求消息：启用 terminal 并限制到 error，播放失败时才能从
 * `MPVLib.logMessage` 拿到错误文本。
 */
object MpvOptions {

    /** 硬解优先，解码失败由 mpv 回退软解。 */
    const val HWDEC = "auto"

    /** 强制软解。 */
    const val HWDEC_SOFTWARE = "no"

    /** 视频输出：libmpv 的 OpenGL 渲染，画面进 Surface。 */
    const val VIDEO_OUTPUT = "gpu"

    /** 只把 error 及以上日志送进日志桥。 */
    const val MSG_LEVEL = "all=error"

    fun initial(cacheDir: String): Map<String, String> = linkedMapOf(
        "config" to "no",
        "terminal" to "yes",
        "msg-level" to MSG_LEVEL,
        "idle" to "once",
        "keep-open" to "yes",
        "force-window" to "no",
        "vo" to VIDEO_OUTPUT,
        "hwdec" to HWDEC,
        "gpu-shader-cache-dir" to cacheDir,
        "icc-cache-dir" to cacheDir,
    )
}
