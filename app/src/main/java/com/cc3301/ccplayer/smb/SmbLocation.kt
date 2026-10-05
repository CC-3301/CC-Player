package com.cc3301.ccplayer.smb

/**
 * SMB 位置：`smb://主机[:端口]/共享[/路径]`。端口取默认值时不出现在字符串形式里。
 */
data class SmbLocation(
    val host: String,
    val port: Int,
    val share: String,
    /** 共享内路径，以 `/` 分隔；共享根为空串。 */
    val path: String,
) {

    /** 位置的字符串形式，也是 [com.cc3301.ccplayer.library.MediaFile.location] 的取值。 */
    val url: String
        get() = buildString {
            append(SCHEME)
            append(SCHEME_SEPARATOR)
            append(host)
            if (port != DEFAULT_PORT) append(':').append(port)
            append('/').append(share)
            if (path.isNotEmpty()) append('/').append(path)
        }

    companion object {
        const val SCHEME = "smb"
        const val DEFAULT_PORT = 445

        /** 用表单里的主机与共享拼出源根位置。 */
        fun shareUrl(host: String, share: String): String =
            SmbLocation(host = host.trim(), port = DEFAULT_PORT, share = share.trim(), path = "").url

        private const val SCHEME_SEPARATOR = "://"
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535

        /** 解析位置；方案不对、缺主机或共享、端口越界时返回 null。 */
        fun parse(location: String): SmbLocation? {
            if (!location.startsWith(SCHEME + SCHEME_SEPARATOR)) return null
            val rest = location.removePrefix(SCHEME + SCHEME_SEPARATOR)
            val authority = rest.substringBefore('/')
            val host = authority.substringBefore(':')
            if (host.isEmpty()) return null

            val portText = authority.substringAfter(':', missingDelimiterValue = "")
            val port = if (portText.isEmpty()) DEFAULT_PORT else portText.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT } ?: return null

            val segments = rest.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
            val share = segments.firstOrNull() ?: return null
            return SmbLocation(host = host, port = port, share = share, path = segments.drop(1).joinToString("/"))
        }
    }
}
