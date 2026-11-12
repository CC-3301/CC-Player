package com.cc3301.ccplayer.playback

import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

/** 内嵌字幕字体与 fontconfig 配置的落盘规则。 */
class SubtitleFontsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `配置把内嵌字体目录与系统字体目录交给 fontconfig`() {
        val root = parse(SubtitleFonts.config(fontsDirectory, cacheDirectory))

        val dirs = children(root, "dir").map { it.textContent.trim() }
        assertTrue(dirs.contains(fontsDirectory.path))
        assertTrue(dirs.contains("/system/fonts"))
        assertEquals(cacheDirectory.path, children(root, "cachedir").single().textContent.trim())
    }

    @Test
    fun `系统未命中的族落到 sans-serif`() {
        val root = parse(SubtitleFonts.config(fontsDirectory, cacheDirectory))

        val appended = children(root, "match")
            .flatMap { children(it, "edit") }
            .single { it.getAttribute("name") == "family" && it.getAttribute("mode") == "append" }

        assertEquals("sans-serif", children(appended, "string").single().textContent.trim())
    }

    @Test
    fun `sans-serif 与 serif 的默认族都是内嵌字体`() {
        val root = parse(SubtitleFonts.config(fontsDirectory, cacheDirectory))

        val defaults = children(root, "alias")
            .associate { alias ->
                children(alias, "family").single().textContent.trim() to
                    children(alias, "default").flatMap { children(it, "family") }.map { it.textContent.trim() }
            }

        assertEquals(listOf("Noto Sans SC"), defaults["sans-serif"])
        assertEquals(listOf("Noto Sans SC"), defaults["serif"])
    }

    @Test
    fun `释放字体与配置`() {
        val font = byteArrayOf(1, 2, 3, 4)

        SubtitleFonts.release(fontsDirectory, cacheDirectory) { ByteArrayInputStream(font) }

        assertArrayEquals(font, SubtitleFonts.fontFile(fontsDirectory).readBytes())
        assertEquals(
            SubtitleFonts.config(fontsDirectory, cacheDirectory),
            SubtitleFonts.configFile(fontsDirectory).readText(),
        )
    }

    @Test
    fun `再次释放会替换旧字体`() {
        SubtitleFonts.release(fontsDirectory, cacheDirectory) { ByteArrayInputStream(byteArrayOf(1)) }

        SubtitleFonts.release(fontsDirectory, cacheDirectory) { ByteArrayInputStream(byteArrayOf(2, 3)) }

        assertArrayEquals(byteArrayOf(2, 3), SubtitleFonts.fontFile(fontsDirectory).readBytes())
    }

    @Test
    fun `内嵌字体子集不超过体积上限`() {
        val font = File("src/main/assets/fonts/NotoSansSC-Regular.otf")

        assertTrue("缺少内嵌字体: ${font.absolutePath}", font.isFile)
        assertTrue("内嵌字体超过 5MB: ${font.length()}", font.length() <= MAX_FONT_BYTES)
    }

    private fun parse(xml: String): Element =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
            .documentElement

    private fun children(element: Element, tag: String): List<Element> {
        val nodes = element.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.parentNode == element }
    }

    private val fontsDirectory: File get() = File(temporaryFolder.root, "subtitle-fonts")

    private val cacheDirectory: File get() = File(temporaryFolder.root, "fontconfig")

    private companion object {
        const val MAX_FONT_BYTES = 5L * 1024L * 1024L
    }
}
