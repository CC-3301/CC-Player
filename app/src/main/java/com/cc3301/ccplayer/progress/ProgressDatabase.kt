package com.cc3301.ccplayer.progress

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/** 进度记录表的一行：媒体位置是唯一键。 */
@Entity(tableName = "playback_progress")
data class ProgressRecord(
    @PrimaryKey val location: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val lastModified: Long,
)

@Dao
interface ProgressDao {

    @Query("SELECT location FROM playback_progress")
    suspend fun locations(): List<String>

    @Query("SELECT * FROM playback_progress WHERE location = :location")
    suspend fun find(location: String): ProgressRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: ProgressRecord)

    @Query("DELETE FROM playback_progress WHERE location = :location")
    suspend fun remove(location: String)

    @Query("DELETE FROM playback_progress WHERE location IN (:locations)")
    suspend fun removeAll(locations: List<String>)
}

/** 只放进度记录的库；媒体索引不落库。 */
@Database(entities = [ProgressRecord::class], version = 1)
abstract class ProgressDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
}
