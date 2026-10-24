package com.cc3301.ccplayer.ui

/** 内存导航快照：模拟冷启动前持久化的最后导航状态。 */
class InMemoryNavigationSnapshotStore(
    private var snapshot: NavigationSnapshot? = null,
) : NavigationSnapshotStore {

    override suspend fun load(): NavigationSnapshot? = snapshot

    override suspend fun save(snapshot: NavigationSnapshot) {
        this.snapshot = snapshot
    }
}
