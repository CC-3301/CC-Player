package com.cc3301.ccplayer.network

/** SMB 服务器地址解析结果。 */
data class SmbAuthority(val host: String, val port: Int)

/** SMB 地址（`主机[:端口]`）的唯一解析实现；端口缺省为 445。 */
object SmbAuthorityParser {

    const val DEFAULT_PORT = 445

    /** 地址为空、含空白或路径、或端口不合法时返回 null。 */
    fun parse(address: String): SmbAuthority? {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() } || trimmed.contains('/')) return null

        val host = trimmed.substringBefore(':')
        if (host.isEmpty()) return null
        val portText = trimmed.substringAfter(':', missingDelimiterValue = "")
        if (portText.isEmpty()) return SmbAuthority(host = host, port = DEFAULT_PORT)
        val port = portText.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT } ?: return null
        return SmbAuthority(host = host, port = port)
    }

    /** 取默认端口时不写出来。 */
    fun normalize(address: String): String? = parse(address)?.let { authority ->
        if (authority.port == DEFAULT_PORT) authority.host else "${authority.host}:${authority.port}"
    }

    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535
}
