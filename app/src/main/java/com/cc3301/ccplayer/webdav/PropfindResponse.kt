package com.cc3301.ccplayer.webdav

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val RESPONSE = "response"
private const val HREF = "href"
private const val RESOURCE_TYPE = "resourcetype"
private const val COLLECTION = "collection"
private const val GET_CONTENT_LENGTH = "getcontentlength"
private const val GET_LAST_MODIFIED = "getlastmodified"
private const val SCHEME_SEPARATOR = "://"

/**
 * 解析 PROPFIND 的 multistatus 响应体，得到相对 [requestUrl] 的目录条目。
 *
 * 标签按局部名匹配，命名空间前缀不作要求。请求地址自身那条略去；缺字段的条目按缺省值处理。
 */
fun parsePropfind(body: String, requestUrl: String): List<WebDavEntry> {
    val requested = pathOf(requestUrl)
    return elementBlocks(body, RESPONSE).mapNotNull { response -> entryOf(response, requested) }
}

private fun entryOf(response: String, requested: String): WebDavEntry? {
    val href = elementBlocks(response, HREF).firstOrNull()?.let(::unescapeXml) ?: return null
    val path = pathOf(href)
    if (path == requested) return null
    val name = path.substringAfterLast('/')
    if (name.isEmpty()) return null
    return WebDavEntry(
        name = name,
        isDirectory = elementBlocks(response, RESOURCE_TYPE).any { hasTag(it, COLLECTION) },
        size = elementBlocks(response, GET_CONTENT_LENGTH).firstOrNull()?.let(::unescapeXml)?.trim()?.toLongOrNull() ?: 0,
        lastModified = lastModifiedOf(response),
    )
}

private fun lastModifiedOf(response: String): Long =
    elementBlocks(response, GET_LAST_MODIFIED).firstOrNull()?.let(::unescapeXml)?.let(::parseHttpDate) ?: 0

/** 解析 RFC 1123 的 HTTP 日期；解析不了返回 0。 */
private fun parseHttpDate(text: String): Long =
    runCatching { ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        .getOrDefault(0L)

/** 取 URL 或 href 的路径并做百分号解码，去掉首尾斜杠。 */
private fun pathOf(urlOrPath: String): String {
    val withoutQuery = urlOrPath.substringBefore('?').substringBefore('#')
    val withoutAuthority =
        if (SCHEME_SEPARATOR in withoutQuery) withoutQuery.substringAfter(SCHEME_SEPARATOR).substringAfter('/', "") else withoutQuery
    return percentDecode(withoutAuthority).trim('/')
}

/**
 * 取出 [localName] 标签的内容块；同名标签嵌套时按层配平。自闭合标签不构成块。
 */
private fun elementBlocks(xml: String, localName: String): List<String> {
    val blocks = mutableListOf<String>()
    var depth = 0
    var contentStart = 0
    TAG.findAll(xml).forEach { match ->
        if (localNameOf(match) != localName || match.groupValues[4] == "/") return@forEach
        if (match.groupValues[1] == "/") {
            if (depth == 0) return@forEach
            depth--
            if (depth == 0) blocks += xml.substring(contentStart, match.range.first)
        } else {
            if (depth == 0) contentStart = match.range.last + 1
            depth++
        }
    }
    return blocks
}

/** 内容里是否出现过该局部名的标签，含自闭合。 */
private fun hasTag(content: String, localName: String): Boolean = TAG.findAll(content).any { localNameOf(it) == localName }

private fun localNameOf(match: MatchResult): String = match.groupValues[2].substringAfterLast(':')

/** 还原 XML 实体，含数字引用。 */
private fun unescapeXml(text: String): String = ENTITY.replace(text) { match ->
    when (val entity = match.groupValues[1]) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> when {
            entity.startsWith("#x") -> codePointText(entity.removePrefix("#x").toIntOrNull(16)) ?: match.value
            entity.startsWith("#") -> codePointText(entity.removePrefix("#").toIntOrNull()) ?: match.value
            else -> match.value
        }
    }
}

private fun codePointText(codePoint: Int?): String? =
    codePoint?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }

private val TAG = Regex("""<(/?)([A-Za-z_][A-Za-z0-9_.:-]*)([^>]*?)(/?)>""")
private val ENTITY = Regex("""&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z]+);""")
