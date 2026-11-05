package com.cc3301.ccplayer.playback

/**
 * 位置中性刷新的 seek 归属。
 *
 * 刷新预留自己的 seek 序号；只有该序号的 SEEK 事件与随后那条 PLAYBACK_RESTART 用来给本次恢复收尾，
 * 其它序号照常走普通 seek 的 ready 路径。一次刷新只回报一次。
 * 序号在 3 秒内没有被 SEEK 消费时标记为坟标：保留队列位、仍消费一条迟到的 SEEK 事件，
 * 避免它去消费下一条真实 seek 的序号。
 */
internal class MpvRefreshSeek {

    private var serial: Int? = null
    private var seen = false
    private var tombstone = false

    /** 刷新命令已下发：记录它预留的序号。 */
    @Synchronized
    fun begin(serial: Int) {
        this.serial = serial
        seen = false
        tombstone = false
    }

    /**
     * 一条 SEEK 事件消费了 [consumed]；是刷新自己的序号时返回 true。
     * 坟标只消费这条事件并清状态，不产生回执。
     */
    @Synchronized
    fun onSeekConsumed(consumed: Int?): Boolean {
        val current = serial ?: return false
        if (consumed != current) return false
        if (tombstone) {
            serial = null
            tombstone = false
            seen = false
            return true
        }
        seen = true
        return true
    }

    /** 本条 ready 的序号 [accepted] 属于刷新时消费掉这次回执；只返回一次。 */
    @Synchronized
    fun claimReceipt(accepted: Int): Int? {
        val current = serial ?: return null
        if (!seen || accepted != current) return null
        serial = null
        seen = false
        return current
    }

    /** 该 ready 序号是不是刷新自己的；用于拦住刷新自己的重配 ready。 */
    @Synchronized
    fun isCurrent(accepted: Int): Boolean = serial != null && accepted == serial

    /**
     * [timeoutSerial] 的计时到点：还没见到 SEEK 时把它标记为坟标并返回序号；
     * 已经见到就只等它自己的 PLAYBACK_RESTART，不清状态。
     */
    @Synchronized
    fun onTimeout(timeoutSerial: Int): Int? {
        val current = serial ?: return null
        if (current != timeoutSerial || seen) return null
        tombstone = true
        return current
    }

    /**
     * 新加载、停止或释放：清掉本次刷新的归属。
     * 还没被 SEEK 消费的序号作为残留返回，由调用方决定丢弃还是留给坟标。
     */
    @Synchronized
    fun clear(): Int? {
        val residual = serial?.takeIf { !seen }
        serial = null
        seen = false
        tombstone = false
        return residual
    }
}
