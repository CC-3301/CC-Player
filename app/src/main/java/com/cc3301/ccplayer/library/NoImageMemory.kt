package com.cc3301.ccplayer.library

/**
 * 本次运行的无图记忆：按最近使用排序，超过上限时淘汰最久未用的一条；每条带过期时间。
 *
 * 只在进程内有效，不参与磁盘缓存；成功抽出图片时调用方负责移除。
 * 过期后重新按「可能有图」处理，避免一次失败把条目钉死到进程结束。
 */
internal class NoImageMemory(
    private val limit: Int = DEFAULT_LIMIT,
    /** 有效期；null 表示本次运行内一直有效。 */
    private val ttlMillis: Long? = DEFAULT_TTL_MILLIS,
    /** 单调时钟，单位毫秒；有效期按它比较。 */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private val lock = Any()

    /** 键到过期时刻；[NEVER_EXPIRES] 表示本次运行内一直有效。按最近使用排序。 */
    private val expiries = LinkedHashMap<String, Long>()

    /** 命中会刷新最近使用顺序；已过期的记录直接作废。 */
    fun contains(key: String): Boolean = synchronized(lock) {
        val expiresAt = expiries.remove(key) ?: return false
        if (expiresAt != NEVER_EXPIRES && clock() >= expiresAt) return false
        expiries[key] = expiresAt
        true
    }

    fun remember(key: String) {
        synchronized(lock) {
            expiries.remove(key)
            expiries[key] = ttlMillis?.let { ttl -> clock() + ttl } ?: NEVER_EXPIRES
            while (expiries.size > limit) {
                val oldest = expiries.keys.first()
                expiries.remove(oldest)
            }
        }
    }

    fun forget(key: String) {
        synchronized(lock) { expiries.remove(key) }
    }

    companion object {
        const val DEFAULT_LIMIT = 512

        /** 「确认无图」的进程内记忆有效期：10 分钟。 */
        const val DEFAULT_TTL_MILLIS = 10 * 60 * 1000L

        /** 没有有效期的记录用的过期时刻。 */
        private const val NEVER_EXPIRES = Long.MAX_VALUE
    }
}
