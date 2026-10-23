package com.cc3301.ccplayer.playback

/**
 * libmpv 在 init 之前必须设置好的选项。
 *
 * 日志桥由 native 初始化时以 `terminal-default` 级别请求；该级别取 `msg-level`，
 * 与 terminal 是否开启无关。terminal 关闭，避免 Android 上没有 tty 的终端初始化。
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
        "terminal" to "no",
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
