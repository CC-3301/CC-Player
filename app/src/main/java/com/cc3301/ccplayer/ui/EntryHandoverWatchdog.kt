package com.cc3301.ccplayer.ui

/**
 * 等待接手的兜底计时：只累计运行期间的真实时间差。
 *
 * 停表只丢基线、保留已累计时长；重新开始一轮用 [reset]。
 * 到点只在 tick 上判定，实际收尾最多晚一个 tick，但累计口径不会算进暂停时间。
 */
class EntryHandoverWatchdog(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val nowMillis: () -> Long,
) {

    private var elapsedMillis = 0L
    private var lastTickMillis: Long? = null

    /** 清除累计，供新的打开重新起算。 */
    fun reset() {
        elapsedMillis = 0L
        lastTickMillis = null
    }

    /** 开始或恢复计时：按当前时刻建立基线，暂停期间的时间不计入。 */
    fun resume() {
        lastTickMillis = nowMillis()
    }

    /** 暂停计时：丢弃基线，已累计时长保留。 */
    fun pause() {
        lastTickMillis = null
    }

    /** 推进一次；累计到时限返回 true。不在计时中时只返回 false。 */
    fun onTick(): Boolean {
        val last = lastTickMillis ?: return false
        val now = nowMillis()
        lastTickMillis = now
        elapsedMillis += (now - last).coerceAtLeast(0L)
        return elapsedMillis >= timeoutMillis
    }

    /** 已累计的前台等待时长。 */
    fun elapsedMillis(): Long = elapsedMillis

    companion object {
        /** 准备完成到播放控制层接手的最长前台等待。 */
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
