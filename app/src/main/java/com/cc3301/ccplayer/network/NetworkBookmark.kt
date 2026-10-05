package com.cc3301.ccplayer.network

/**
 * 网络源书签：地址即标识，协议体现在 URL 方案里；密码不进本结构。
 *
 * 书签的 [url] 是源根位置，源内条目位置以它为前缀。
 */
data class NetworkBookmark(
    val url: String,
    val username: String,
)
