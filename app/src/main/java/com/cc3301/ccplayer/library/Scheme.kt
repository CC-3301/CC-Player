package com.cc3301.ccplayer.library

/** 位置里的方案名：`://` 之前的部分，不区分大小写；没有 `://` 时是空串。 */
fun schemeOf(location: String): String =
    location.substringBefore(SEPARATOR, missingDelimiterValue = "").lowercase()

private const val SEPARATOR = "://"
