package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.network.WebDavSourceAddress

/**
 * WebDAV 源地址：`http(s)://主机[:端口]/路径`。地址是源根，源内位置以它为前缀。
 */
object WebDavAddress {

    /** 明文与 TLS 两种方案都走同一套 WebDAV 实现。 */
    const val SCHEME_HTTP = WebDavSourceAddress.SCHEME_HTTP
    const val SCHEME_HTTPS = WebDavSourceAddress.SCHEME_HTTPS

    /**
     * 规范化用户输入的地址：去首尾空白、去末尾斜杠、方案转小写。
     *
     * 方案不是 http/https、没有主机、含换行或制表符时返回 null；后两者会破坏书签的按行存储。
     */
    fun normalize(address: String): String? = WebDavSourceAddress.normalize(address)
}
