package com.cc3301.ccplayer.smb

/**
 * SMB 服务器地址（`主机[:端口]`）的规范化：去首尾空白，端口默认 445，取默认端口时不写出来。
 */
object SmbAddress {

    /** 地址为空、含空白或路径、或端口不合法时返回 null。 */
    fun normalize(address: String): String? {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() } || trimmed.contains('/')) return null

        val host = trimmed.substringBefore(':')
        if (host.isEmpty()) return null
        val portText = trimmed.substringAfter(':', missingDelimiterValue = "")
        if (portText.isEmpty()) return host
        val port = portText.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT } ?: return null
        return if (port == SmbLocation.DEFAULT_PORT) host else "$host:$port"
    }

    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535
}
