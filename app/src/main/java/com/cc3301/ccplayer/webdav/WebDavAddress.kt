package com.cc3301.ccplayer.webdav

/**
 * WebDAV 源地址：`http(s)://主机[:端口]/路径`。地址是源根，源内位置以它为前缀。
 */
object WebDavAddress {

    /** 明文与 TLS 两种方案都走同一套 WebDAV 实现。 */
    const val SCHEME_HTTP = "http"
    const val SCHEME_HTTPS = "https"

    private const val SCHEME_SEPARATOR = "://"

    /**
     * 规范化用户输入的地址：去首尾空白、去末尾斜杠、方案转小写。
     *
     * 方案不是 http/https、没有主机、含换行或制表符时返回 null；后两者会破坏书签的按行存储。
     */
    fun normalize(address: String): String? {
        val trimmed = address.trim()
        if (trimmed.any { it == '\n' || it == '\r' || it == '\t' }) return null

        val separator = trimmed.indexOf(SCHEME_SEPARATOR)
        if (separator <= 0) return null
        val scheme = trimmed.substring(0, separator).lowercase()
        if (scheme != SCHEME_HTTP && scheme != SCHEME_HTTPS) return null

        val rest = trimmed.substring(separator + SCHEME_SEPARATOR.length)
        val authority = rest.substringBefore('/')
        if (authority.isEmpty() || authority.startsWith(":")) return null

        val path = rest.substringAfter('/', "").split('/').filter { it.isNotEmpty() }.joinToString("/")
        return buildString {
            append(scheme).append(SCHEME_SEPARATOR).append(authority)
            if (path.isNotEmpty()) append('/').append(path)
        }
    }
}
