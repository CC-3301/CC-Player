package com.cc3301.ccplayer.playback

/**
 * libmpv 在 init 之前必须设置好的选项。
 */
object MpvOptions {

    /** 硬解优先，解码失败由 mpv 回退软解。 */
    const val HWDEC = "auto"

    /** 视频输出：libmpv 的 OpenGL 渲染，画面进 Surface。 */
    const val VIDEO_OUTPUT = "gpu"

    fun initial(cacheDir: String): Map<String, String> = linkedMapOf(
        "config" to "no",
        "terminal" to "no",
        "idle" to "once",
        "force-window" to "no",
        "vo" to VIDEO_OUTPUT,
        "hwdec" to HWDEC,
        "gpu-shader-cache-dir" to cacheDir,
        "icc-cache-dir" to cacheDir,
    )
}
