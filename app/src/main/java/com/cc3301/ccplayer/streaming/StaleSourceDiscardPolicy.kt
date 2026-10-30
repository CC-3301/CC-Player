package com.cc3301.ccplayer.streaming

/**
 * 退到后台后何时丢弃已建立的字节源：
 * 后台且（后台播放关闭 或 当前暂停）才丢；加载期间先记待丢弃，
 * 加载结束或下次回到前台发起请求前补做一次，做完清标志。
 */
class StaleSourceDiscardPolicy {

    /** 后台期间因加载中没丢成的丢弃请求。 */
    private var pending = false

    /**
     * 退到后台：返回现在是否该丢弃。
     * 加载期间不丢（当前响应正踩在这些源上），只记待丢弃。
     */
    fun onLeftForeground(
        backgroundPlaybackOff: Boolean,
        paused: Boolean,
        loading: Boolean,
    ): Boolean {
        if (!backgroundPlaybackOff && !paused) return false
        if (loading) {
            pending = true
            return false
        }
        return true
    }

    /** 加载结束：有待丢弃就补做一次。 */
    fun onLoadingFinished(): Boolean = consumePending()

    /** 回到前台发起请求前：有待丢弃就补做一次。 */
    fun onReturnedToForeground(): Boolean = consumePending()

    private fun consumePending(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }
}
