package com.cc3301.ccplayer.progress

/**
 * 位置是否是目录 [directoryLocation] 的直接子项（目录位置后紧跟分隔符，且不再有分隔符）。
 *
 * 本地位置是 SAF 的 document URI，文档名里的路径分隔符编码成 `%2F`；网络源位置是 URL，用 `/` 分隔。
 * 目录列举只能看到直接子项，判断媒体在不在也只看这一层。
 */
fun isDirectChildOf(directoryLocation: String, location: String): Boolean =
    SEPARATORS.any { separator ->
        val prefix = "$directoryLocation$separator"
        location.startsWith(prefix) && !location.removePrefix(prefix).contains(separator)
    }

private val SEPARATORS = listOf("/", "%2F")
