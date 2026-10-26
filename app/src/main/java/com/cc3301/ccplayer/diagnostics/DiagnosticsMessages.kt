package com.cc3301.ccplayer.diagnostics

import java.net.URI

/** 连接源日志只保留名称与主机；地址中的 userinfo 不进入日志。 */
fun connectionLogLine(protocol: Any, name: String, location: String): String {
    val host = runCatching { URI(location).host }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: fallbackHost(location)
    val label = name.ifBlank { host.orEmpty() }
    return when {
        host == null -> "连接源: $protocol $label"
        label == host -> "连接源: $protocol $host"
        else -> "连接源: $protocol $label ($host)"
    }
}

private fun fallbackHost(location: String): String? {
    val authority = location.substringAfter("://", "").substringBefore('/')
    return authority.substringAfterLast('@').takeIf { it.isNotBlank() }
}

/** 库内导航日志：记录源名称与相对路径。 */
fun libraryNavigationLine(
    rootName: String,
    relativePath: String,
    entryName: String,
    directory: Boolean,
): String {
    val path = listOf(relativePath.trim('/'), entryName.trim('/'))
        .filter { it.isNotBlank() }
        .joinToString("/")
    val target = if (path.isEmpty()) "/" else "/$path"
    val label = if (directory) "导航: 目录" else "导航: 播放"
    return "$label ${rootName.ifBlank { "-" }} $target"
}

/** 屏幕方向切换日志：记录切换前后的方向，含 180° 反向。 */
fun screenOrientationChangeLine(from: String, to: String): String = "屏幕方向: $from -> $to"

/** 写一条库内导航日志；关闭状态不拼接消息。 */
fun logLibraryNavigation(
    diagnostics: DiagnosticsLog,
    rootName: String,
    relativePath: String,
    entryName: String,
    directory: Boolean,
) {
    diagnostics.log { libraryNavigationLine(rootName, relativePath, entryName, directory) }
}
