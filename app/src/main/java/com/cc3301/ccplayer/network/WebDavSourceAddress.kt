package com.cc3301.ccplayer.network

/** WebDAV 源地址拆出的服务器地址与起始目录。 */
data class WebDavSource(val address: String, val path: String)

/**
 * WebDAV 源地址（`http(s)://主机[:端口]/路径`）的唯一解析实现。
 *
 * [WebDavSource.address] 是源根地址，[WebDavSource.path] 是源内相对路径。
 */
object WebDavSourceAddress {

    const val SCHEME_HTTP = "http"
    const val SCHEME_HTTPS = "https"

    private const val SCHEME_SEPARATOR = "://"

    /**
     * 拆分用户输入的地址：去首尾空白、方案转小写、路径去空段。
     *
     * 方案不是 http/https、没有主机、含换行或制表符时返回 null；后两者会破坏书签的按行存储。
     */
    fun split(value: String): WebDavSource? {
        val trimmed = value.trim()
        if (trimmed.any { it == '\n' || it == '\r' || it == '\t' }) return null

        val separator = trimmed.indexOf(SCHEME_SEPARATOR)
        if (separator <= 0) return null
        val scheme = trimmed.substring(0, separator).lowercase()
        if (scheme != SCHEME_HTTP && scheme != SCHEME_HTTPS) return null

        val rest = trimmed.substring(separator + SCHEME_SEPARATOR.length)
        val authority = rest.substringBefore('/')
        if (authority.isEmpty() || authority.startsWith(":")) return null

        return WebDavSource(
            address = "$scheme$SCHEME_SEPARATOR$authority",
            path = normalizeRelativePath(rest.substringAfter('/', "")),
        )
    }

    /** 规范化后的完整源地址；路径为空时就是服务器地址。 */
    fun normalize(value: String): String? = split(value)?.let { source ->
        if (source.path.isEmpty()) source.address else "${source.address}/${source.path}"
    }
}
