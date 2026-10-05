package com.cc3301.ccplayer.progress

import android.content.Context
import androidx.room.Room

/** Room 实现的进度记录存取。 */
class RoomPlaybackProgressRepository private constructor(private val dao: ProgressDao) : PlaybackProgressRepository {

    override suspend fun find(location: String): MediaProgress? = dao.find(location)?.toMediaProgress()

    override suspend fun save(progress: MediaProgress) {
        dao.put(
            ProgressRecord(
                location = progress.location,
                positionMillis = progress.positionMillis,
                durationMillis = progress.durationMillis,
                lastModified = progress.lastModified,
            ),
        )
    }

    override suspend fun clear(location: String) = dao.remove(location)

    /** 记录条数按播过的媒体增长，清理时把全表位置读出来按目录的直接子项过滤。 */
    override suspend fun removeMissingUnder(directoryLocation: String, presentLocations: Set<String>) {
        val missing = dao.locations().filter {
            it !in presentLocations && isDirectChildOf(directoryLocation, it)
        }
        if (missing.isNotEmpty()) dao.removeAll(missing)
    }

    companion object {
        private val lock = Any()
        private var shared: PlaybackProgressRepository? = null

        /** 进程内共用一份：播放会话落记录、列表列举清记录，读写同一张表。 */
        fun create(context: Context): PlaybackProgressRepository = synchronized(lock) {
            shared ?: RoomPlaybackProgressRepository(
                Room.databaseBuilder(context.applicationContext, ProgressDatabase::class.java, DATABASE_NAME)
                    .build()
                    .progressDao(),
            ).also { shared = it }
        }

        private const val DATABASE_NAME = "playback_progress.db"
    }
}

private fun ProgressRecord.toMediaProgress(): MediaProgress = MediaProgress(
    location = location,
    positionMillis = positionMillis,
    durationMillis = durationMillis,
    lastModified = lastModified,
)
