package com.cc3301.ccplayer.streaming

/**
 * 退到后台后何时丢弃已建立的字节源：
 * 后台且（后台播放关闭 或 当前暂停）才丢；加载期间先记待丢弃，
 * 加载结束（仍在后台）或回到前台时补做一次。
 *
 * 补做的标志只在 [discard] 交出去之后才清：没交出去的那一次留着等下一次补做，
 * 不会被「标志已消费」吞掉。退到后台当场丢的那一次不记标志，直接返回 true 由调用方立刻丢。
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
        pending = false
        return true
    }

    /**
     * 加载结束：按当前是否仍在后台重新判定——已回到前台就留给回前台补做，标志不动。
     * [discard] 返回丢弃是否已经交出去。
     */
    fun onLoadingFinished(inBackground: Boolean, discard: () -> Boolean): Boolean {
        if (!inBackground) return false
        return makeup(discard)
    }

    /** 回到前台发起请求前：有待丢弃就补做一次。 */
    fun onReturnedToForeground(discard: () -> Boolean): Boolean = makeup(discard)

    private fun makeup(discard: () -> Boolean): Boolean {
        if (!pending) return false
        if (!discard()) return false
        pending = false
        return true
    }
}
