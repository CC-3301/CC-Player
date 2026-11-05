package com.cc3301.ccplayer.playback

/** 刷新序号的等待窗口：超时登记坟标与坟标回收共用同一个 3 秒判据。 */
internal const val REFRESH_SEEK_TIMEOUT_MILLIS = 3_000L

/** 刷新序号转成的坟标：保留队列位，[expiresAtMillis] 前仍可能被迟到事件消费，到期后回收。 */
internal data class RefreshTombstone(val serial: Int, val expiresAtMillis: Long)

/**
 * 位置中性刷新的 seek 归属。
 *
 * 刷新预留自己的 seek 序号；只有该序号的 SEEK 事件与随后那条 PLAYBACK_RESTART 用来给本次恢复收尾，
 * 其它序号照常走普通 seek 的 ready 路径。一次刷新只回报一次。
 * 预留的序号只有四种归宿：
 * - 正常消费：收到自己的 SEEK，继续等自己的 PLAYBACK_RESTART；
 * - 迟到：等待窗口内没有 SEEK，转为坟标，保留队列位并仍消费一条迟到事件；
 * - 取消：翻转或分离时命令可能已经下发，同样转为坟标，不直接撤掉队列位；
 * - 作废：命令下发失败、新加载、停止或释放时确定不会再有事件，直接撤掉。
 * 坟标登记后 3 秒内仍可能被迟到事件消费；超过 3 秒仍没有事件，由下一次预留序号回收，避免永久吞掉后续真实序号。
 */
internal class MpvRefreshSeek {

    private var serial: Int? = null
    private var seen = false

    /** 已登记为坟标：队列位仍在，等一条迟到事件把它消费掉。 */
    private var tombstoned = false

    /** 刷新命令已下发：记录它预留的序号。 */
    fun begin(serial: Int) {
        this.serial = serial
        seen = false
        tombstoned = false
    }

    /**
     * 一条 SEEK 事件消费了 [consumed]；是刷新自己的序号时返回 true。
     * 坟标只消费这条事件并清状态，不产生回执。
     */
    fun onSeekConsumed(consumed: Int?): Boolean {
        val current = serial ?: return false
        if (consumed != current) return false
        if (tombstoned) {
            clearState()
            return true
        }
        seen = true
        return true
    }

    /** 本条 ready 的序号 [accepted] 属于刷新时消费掉这次回执；只返回一次。 */
    fun claimReceipt(accepted: Int): Int? {
        val current = serial ?: return null
        if (!seen || accepted != current) return null
        clearState()
        return current
    }

    /** 该 ready 序号是不是刷新自己的；用于拦住刷新自己的重配 ready。 */
    fun isCurrent(accepted: Int): Boolean = serial != null && accepted == serial

    /**
     * [timeoutSerial] 的计时到点：还没见到 SEEK 时把它登记为坟标并返回；
     * 已经见到就只等它自己的 PLAYBACK_RESTART，不清状态。已经登记过的不重复登记。
     */
    fun onTimeout(timeoutSerial: Int, nowMillis: Long): RefreshTombstone? {
        val current = serial ?: return null
        if (current != timeoutSerial || seen || tombstoned) return null
        tombstoned = true
        return tombstoneFor(current, nowMillis)
    }

    /**
     * 翻转或分离：刷新不再有效。
     * 还没见到 SEEK 时命令可能仍在路上，返回要登记的坟标；已经见过或已经登记过则只清归属。
     */
    fun cancel(nowMillis: Long): RefreshTombstone? {
        val current = serial ?: return null
        val pending = !seen && !tombstoned
        clearState()
        return if (pending) tombstoneFor(current, nowMillis) else null
    }

    /**
     * 新加载、停止、释放或命令下发失败：这条命令确定不会再产生事件，返回要从队列撤掉的序号。
     * 已经见过 SEEK 的序号早已离开队列，撤掉是空操作。
     */
    fun discard(): Int? {
        val current = serial ?: return null
        clearState()
        return current
    }

    /** 到期时间从登记时刻起算：此前 3 秒内迟到事件仍可能来消费这个坟标。 */
    private fun tombstoneFor(serial: Int, nowMillis: Long): RefreshTombstone =
        RefreshTombstone(serial, nowMillis + REFRESH_SEEK_TIMEOUT_MILLIS)

    private fun clearState() {
        serial = null
        seen = false
        tombstoned = false
    }
}
