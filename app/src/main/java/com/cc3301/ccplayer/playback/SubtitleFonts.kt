package com.cc3301.ccplayer.playback

import android.content.Context
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 字幕字体：把内嵌中文字体子集与 fontconfig 配置释放到应用私有目录。
 *
 * 字体由 libass 经 fontconfig 使用；配置把系统未命中的族引到内嵌字体，生僻字仍回退到系统字体。
 * 释放结果在进程内只写一次，可从任意线程调用。
 */
class SubtitleFonts(
    context: Context,
    private val diagnostics: DiagnosticsLog,
) {

    private val fontsDirectory = File(context.filesDir, DIRECTORY_NAME)

    private val cacheDirectory = File(context.cacheDir, FONTCONFIG_CACHE_DIRECTORY_NAME)

    private val assets = context.assets

    @Volatile
    private var released = false

    /** 释放字体与配置并返回字体目录；写盘失败只留诊断，字幕退回系统字体。 */
    fun prepare(): File = synchronized(this) {
        if (!released) {
            runCatching { release(fontsDirectory, cacheDirectory) { assets.open(ASSET_FONT) } }
                .onFailure { error -> diagnostics.log { "字幕字体释放失败: ${error.message}" } }
            released = true
        }
        fontsDirectory
    }

    companion object {

        /** 字体在应用私有目录下的目录名。 */
        const val DIRECTORY_NAME = "subtitle-fonts"

        /** fontconfig 缓存目录名。 */
        const val FONTCONFIG_CACHE_DIRECTORY_NAME = "fontconfig"

        /** 内嵌字体子集的字族名，与字体 name 表一致。 */
        const val FAMILY = "Noto Sans SC"

        private const val ASSET_FONT = "fonts/NotoSansSC-Regular.otf"

        private const val FONT_FILE_NAME = "NotoSansSC-Regular.otf"

        private const val CONFIG_FILE_NAME = "fonts.conf"

        fun fontFile(directory: File): File = File(directory, FONT_FILE_NAME)

        fun configFile(directory: File): File = File(directory, CONFIG_FILE_NAME)

        /** fontconfig 配置：内嵌字体与系统字体都参与匹配，系统未命中的族落到内嵌字体。 */
        fun config(fontsDirectory: File, cacheDirectory: File): String = """
            |<?xml version="1.0" encoding="utf-8"?>
            |<fontconfig>
            |    <dir>${fontsDirectory.path}</dir>
            |    <dir>/system/fonts</dir>
            |    <dir>/product/fonts</dir>
            |    <cachedir>${cacheDirectory.path}</cachedir>
            |    <match target="pattern">
            |        <edit name="family" mode="append" binding="weak"><string>sans-serif</string></edit>
            |    </match>
            |    <alias>
            |        <family>sans-serif</family>
            |        <default><family>$FAMILY</family></default>
            |    </alias>
            |    <alias>
            |        <family>serif</family>
            |        <default><family>$FAMILY</family></default>
            |    </alias>
            |</fontconfig>
            |
        """.trimMargin()

        /** 释放字体文件与配置；这两个文件先写临时文件再改名，避免中断留下半个文件。 */
        fun release(fontsDirectory: File, cacheDirectory: File, openFont: () -> InputStream) {
            fontsDirectory.mkdirs()
            write(fontFile(fontsDirectory)) { output -> openFont().use { it.copyTo(output) } }
            write(configFile(fontsDirectory)) { output ->
                output.write(config(fontsDirectory, cacheDirectory).toByteArray())
            }
        }

        private fun write(target: File, body: (OutputStream) -> Unit) {
            val staging = File(target.parentFile, "${target.name}.tmp")
            staging.outputStream().use(body)
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
