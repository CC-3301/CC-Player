package com.cc3301.ccplayer.network

/**
 * 网络源书签：协议、名称、服务器地址、路径与用户名；密码不进本结构。
 *
 * [url] 是源根位置，源内条目位置以它为前缀。名称可空，展示时回退到服务器地址。
 */
data class NetworkBookmark(
    val protocol: SourceProtocol,
    val name: String,
    /** SMB 为 `主机[:端口]`，WebDAV 为 `http(s)://主机[:端口]`。 */
    val address: String,
    /** SMB 为共享名加子目录，WebDAV 为起始目录；为空表示源根。 */
    val path: String,
    val username: String,
) {

    init {
        require(protocol != SourceProtocol.Local) { "本地连接源不存网络书签" }
    }

    /** 源列表展示的名称：名称为空时用服务器地址。 */
    val displayName: String get() = name.ifBlank { address }

    /** 源根位置。 */
    val url: String
        get() = when (protocol) {
            SourceProtocol.Smb -> "smb://$address" + suffix
            SourceProtocol.WebDav -> address + suffix
            SourceProtocol.Local -> error("本地连接源没有网络地址")
        }

    private val suffix: String get() = if (path.isEmpty()) "" else "/$path"

    companion object {

        /** 把旧书签的地址拆成协议、服务器地址与路径；地址不认识时返回 null。 */
        fun fromUrl(url: String, username: String): NetworkBookmark? {
            val trimmed = url.trim()
            return when {
                trimmed.startsWith("smb://", ignoreCase = true) -> {
                    val rest = trimmed.substringAfter("://")
                    val address = rest.substringBefore('/').trim()
                    if (SmbAuthorityParser.parse(address) == null) return null
                    NetworkBookmark(
                        protocol = SourceProtocol.Smb,
                        name = "",
                        address = address,
                        path = normalizeRelativePath(rest.substringAfter('/', "")),
                        username = username,
                    )
                }

                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> {
                    val source = WebDavSourceAddress.split(trimmed) ?: return null
                    NetworkBookmark(
                        protocol = SourceProtocol.WebDav,
                        name = "",
                        address = source.address,
                        path = source.path,
                        username = username,
                    )
                }

                else -> null
            }
        }
    }
}
