package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.network.SmbAuthority
import com.cc3301.ccplayer.network.SmbAuthorityParser

/**
 * SMB 服务器地址（`主机[:端口]`）的规范化：去首尾空白，端口默认 445，取默认端口时不写出来。
 */
object SmbAddress {

    fun parse(address: String): SmbAuthority? = SmbAuthorityParser.parse(address)

    /** 地址为空、含空白或路径、或端口不合法时返回 null。 */
    fun normalize(address: String): String? = SmbAuthorityParser.normalize(address)
}
