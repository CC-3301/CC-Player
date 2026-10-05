package com.cc3301.ccplayer.smb

/** 一次 SMB 访问的参数：主机、共享、共享内路径与凭据。 */
data class SmbTarget(
    val host: String,
    val port: Int,
    val share: String,
    /** 共享内路径；共享根为空串。 */
    val path: String,
    val username: String,
    val password: String?,
) {

    /** 本参数指向的位置的字符串形式。 */
    val url: String get() = SmbLocation(host, port, share, path).url

    /** 本参数指向的条目名：共享根是共享名，否则是路径最后一段。 */
    val name: String get() = path.takeIf { it.isNotEmpty() }?.substringAfterLast('/') ?: share

    /** 子项在本共享内的路径。 */
    fun childPath(name: String): String = if (path.isEmpty()) name else "$path/$name"
}
