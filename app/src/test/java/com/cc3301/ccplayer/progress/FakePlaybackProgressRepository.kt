package com.cc3301.ccplayer.progress

import kotlinx.coroutines.CompletableDeferred

/** 内存进度记录：单测里代替 Room。 */
class FakePlaybackProgressRepository : PlaybackProgressRepository {

    val records = mutableMapOf<String, MediaProgress>()

    /** 每次调用带的位置，按调用顺序；目录清理记目录与列举出的位置。 */
    val calledLocations = mutableListOf<String>()

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
        calledLocations += location
        findGate?.await()
        return records[location]
    }

    override suspend fun save(progress: MediaProgress) {
        calledLocations += progress.location
        records[progress.location] = progress
    }

    override suspend fun clear(location: String) {
        calledLocations += location
        records.remove(location)
    }

    override suspend fun removeMissingUnder(directoryLocation: String, presentLocations: Set<String>) {
        calledLocations += directoryLocation
        calledLocations += presentLocations
        records.keys
            .filter { it !in presentLocations && isDirectChildOf(directoryLocation, it) }
            .forEach { records.remove(it) }
    }
}
