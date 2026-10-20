package com.cc3301.ccplayer.webdav

import java.io.ByteArrayOutputStream

private const val HEX_DIGITS = "0123456789ABCDEF"
private const val SCHEME_SEPARATOR = "://"

/**
 * 按 UTF-8 把路径段编码成百分号形式，只保留 RFC 3986 的非保留字符。
 *
 * 先解码再编码：用户粘贴的已编码地址与文件树拼出的原始名字都能得到同一个请求路径。
 */
internal fun encodePathSegment(segment: String): String = buildString {
    percentDecode(segment).toByteArray(Charsets.UTF_8).forEach { byte ->
        val value = byte.toInt() and 0xFF
        val character = value.toChar()
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-._~") {
            append(character)
        } else {
            append('%').append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
        }
    }
}

/** 把地址里的路径段换成百分号编码形式；没有方案分隔符时原样返回。 */
internal fun encodeWebDavUrl(rawUrl: String): String {
    val separator = rawUrl.indexOf(SCHEME_SEPARATOR)
    if (separator < 0) return rawUrl
    val scheme = rawUrl.substring(0, separator)
    val rest = rawUrl.substring(separator + SCHEME_SEPARATOR.length)
    val authority = rest.substringBefore('/')
    val path = rest.substringAfter('/', "")
    val segments = path.split('/').filter { it.isNotEmpty() }
    return buildString {
        append(scheme).append(SCHEME_SEPARATOR).append(authority)
        segments.forEach { append('/').append(encodePathSegment(it)) }
        if (path.endsWith('/') && segments.isNotEmpty()) append('/')
    }
}

/** 百分号解码；'+' 按字面加号处理，非法序列原样保留。 */
internal fun percentDecode(text: String): String {
    if ('%' !in text) return text
    val bytes = ByteArrayOutputStream(text.length)
    var index = 0
    while (index < text.length) {
        val character = text[index]
        val hex = text.substring(index + 1, minOf(index + 3, text.length))
        if (character == '%' && hex.length == 2 && hex.all(::isHexDigit)) {
            bytes.write(hex.toInt(16))
            index += 3
            continue
        }
        bytes.write(character.toString().toByteArray(Charsets.UTF_8))
        index++
    }
    return bytes.toString(Charsets.UTF_8.name())
}

private fun isHexDigit(character: Char): Boolean =
    character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
