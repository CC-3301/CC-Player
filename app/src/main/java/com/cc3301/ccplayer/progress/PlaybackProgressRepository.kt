package com.cc3301.ccplayer.progress

/**
 * 进度记录的存取接缝：播放会话与列表清理只依赖本接口，Room 实现见 [RoomPlaybackProgressRepository]。
 */
interface PlaybackProgressRepository {

    /** 读媒体 [location] 的进度记录；没有记录时返回 null。 */
    suspend fun find(location: String): MediaProgress?

    /** 写入进度记录；同一位置已有记录时覆盖。 */
    suspend fun save(progress: MediaProgress)

    /** 删除媒体 [location] 的进度记录；没有记录时不动作。 */
    suspend fun clear(location: String)

    /**
     * 清理 [directoryLocation] 下已不存在的媒体的记录。
     * [presentLocations] 是本次列举出的位置集合；该目录之外的记录不动。
     */
    suspend fun removeMissingUnder(directoryLocation: String, presentLocations: Set<String>)
}
