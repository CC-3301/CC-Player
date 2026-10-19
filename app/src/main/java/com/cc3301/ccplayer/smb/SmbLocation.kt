package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.network.SmbAuthorityParser
import com.cc3301.ccplayer.network.normalizeRelativePath

/**
 * SMB 位置：`smb://主机[:端口][/共享[/路径]]`。端口取默认值时不出现在字符串形式里。
 *
 * 共享为空表示服务器根：这时列举的是服务器共享名。
 */
data class SmbLocation(
    val host: String,
    val port: Int,
    /** 共享名；服务器根为空串。 */
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
            if (share.isNotEmpty()) {
                append('/').append(share)
                if (path.isNotEmpty()) append('/').append(path)
            }
        }

    companion object {
        const val SCHEME = "smb"
        const val DEFAULT_PORT = SmbAuthorityParser.DEFAULT_PORT

        private const val SCHEME_SEPARATOR = "://"

        /** 解析位置；方案不对、缺主机或端口越界时返回 null。 */
        fun parse(location: String): SmbLocation? {
            if (!location.startsWith(SCHEME + SCHEME_SEPARATOR)) return null
            val rest = location.removePrefix(SCHEME + SCHEME_SEPARATOR)
            val authority = SmbAddress.parse(rest.substringBefore('/')) ?: return null

            val path = normalizeRelativePath(rest.substringAfter('/', ""))
            val share = path.substringBefore('/')
            return SmbLocation(
                host = authority.host,
                port = authority.port,
                share = share,
                path = path.substringAfter('/', ""),
            )
        }
    }
}
