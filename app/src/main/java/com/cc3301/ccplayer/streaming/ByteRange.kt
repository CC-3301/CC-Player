package com.cc3301.ccplayer.streaming

private const val STATUS_OK = 200
private const val STATUS_PARTIAL = 206
private const val STATUS_UNSATISFIABLE = 416

private const val BYTES_PREFIX = "bytes="

/** 一次字节区间响应：状态码、要发送的区间与总长度。 */
data class ByteRangeResponse(
    val statusCode: Int,
    /** 响应体的首个字节位置。 */
    val start: Long,
    /** 响应体的末个字节位置（含）；空响应体时为 -1。 */
    val endInclusive: Long,
    val totalSize: Long,
) {
    /** 416 不带响应体。 */
    val contentLength: Long
        get() = if (statusCode == STATUS_UNSATISFIABLE) 0 else (endInclusive - start + 1).coerceAtLeast(0)

    /** 206 写明实际区间，416 写明总长度，200 不带本头。 */
    val contentRange: String?
        get() = when (statusCode) {
            STATUS_PARTIAL -> "bytes $start-$endInclusive/$totalSize"
            STATUS_UNSATISFIABLE -> "bytes */$totalSize"
            else -> null
        }
}

/**
 * 解析 Range 头：支持单段（`start-end`、`start-`）与后缀（`-length`）三种写法。
 *
 * 没有 Range、写法不合法或多段时回落到 200 全量；起点越界返回 416。
 */
fun byteRangeResponse(rangeHeader: String?, totalSize: Long): ByteRangeResponse {
    val size = totalSize.coerceAtLeast(0)
    val whole = ByteRangeResponse(STATUS_OK, 0, size - 1, size)
    val spec = rangeHeader
        ?.takeIf { it.startsWith(BYTES_PREFIX) }
        ?.removePrefix(BYTES_PREFIX)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && ',' !in it }
        ?: return whole

    val dash = spec.indexOf('-')
    if (dash < 0) return whole
    val startText = spec.substring(0, dash).trim()
    val endText = spec.substring(dash + 1).trim()

    if (startText.isEmpty()) {
        val suffix = endText.toLongOrNull() ?: return whole
        if (suffix <= 0) return whole
        if (size == 0L) return ByteRangeResponse(STATUS_UNSATISFIABLE, 0, -1, size)
        return ByteRangeResponse(STATUS_PARTIAL, (size - suffix).coerceAtLeast(0), size - 1, size)
    }

    val start = startText.toLongOrNull() ?: return whole
    if (start < 0) return whole
    if (start >= size) return ByteRangeResponse(STATUS_UNSATISFIABLE, 0, -1, size)

    val end = if (endText.isEmpty()) size - 1 else endText.toLongOrNull() ?: return whole
    if (end < start) return whole
    return ByteRangeResponse(STATUS_PARTIAL, start, minOf(end, size - 1), size)
}
