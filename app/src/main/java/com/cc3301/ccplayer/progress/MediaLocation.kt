package com.cc3301.ccplayer.progress

/**
 * 位置是否在目录 [directoryLocation] 之下，层级不限。
 *
 * 本地位置是 SAF 的 document URI，文档名里的路径分隔符编码成 `%2F`；网络源位置是 URL，用 `/` 分隔。
 * 要求目录位置后紧跟分隔符，同级目录不会误判。
 */
fun isUnderDirectory(directoryLocation: String, location: String): Boolean =
    location.startsWith("$directoryLocation/") || location.startsWith("$directoryLocation%2F")
