package com.cc3301.ccplayer.network

/** 相对路径规范化：去首尾空白与空段，用 `/` 连接。 */
fun normalizeRelativePath(path: String): String =
    path.trim().split('/').filter { it.isNotEmpty() }.joinToString("/")
