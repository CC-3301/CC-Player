package com.cc3301.ccplayer.progress

import kotlinx.coroutines.CompletableDeferred

/** 内存进度记录：单测里代替 Room。 */
class FakePlaybackProgressRepository : PlaybackProgressRepository {

    val records = mutableMapOf<String, MediaProgress>()

    private var findGate: CompletableDeferred<Unit>? = null

    /** 让之后的 [find] 挂起，直到 [releaseFinds]，用于制造记录迟到的时序。 */
    fun holdFinds() {
        findGate = CompletableDeferred()
    }

    fun releaseFinds() {
        val gate = findGate ?: return
        findGate = null
        gate.complete(Unit)
    }

    override suspend fun find(location: String): MediaProgress? {
        findGate?.await()
        return records[location]
    }

    override suspend fun save(progress: MediaProgress) {
        records[progress.location] = progress
    }

    override suspend fun clear(location: String) {
        records.remove(location)
    }

    override suspend fun removeMissingUnder(directoryLocation: String, presentLocations: Set<String>) {
        records.keys
            .filter { it !in presentLocations && isUnderDirectory(directoryLocation, it) }
            .forEach { records.remove(it) }
    }
}
