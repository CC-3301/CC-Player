package com.cc3301.ccplayer.library

/**
 * 本次运行的无图记忆：按最近使用排序，超过上限时淘汰最久未用的一条。
 *
 * 只在进程内有效，不参与磁盘缓存；成功抽出图片时调用方负责移除。
 */
internal class NoImageMemory(private val limit: Int = DEFAULT_LIMIT) {

    private val lock = Any()
    private val keys = LinkedHashSet<String>()

    /** 命中会刷新最近使用顺序。 */
    fun contains(key: String): Boolean = synchronized(lock) {
        if (!keys.remove(key)) return false
        keys.add(key)
        true
    }

    fun remember(key: String) {
        synchronized(lock) {
            keys.remove(key)
            keys.add(key)
            while (keys.size > limit) {
                val oldest = keys.iterator().next()
                keys.remove(oldest)
            }
        }
    }

    fun forget(key: String) {
        synchronized(lock) { keys.remove(key) }
    }

    companion object {
        const val DEFAULT_LIMIT = 512
    }
}
