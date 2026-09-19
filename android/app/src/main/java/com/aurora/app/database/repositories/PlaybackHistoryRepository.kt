package com.aurora.app.database.repositories

import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.RecentlyPlayedEntity

class PlaybackHistoryRepository(private val database: AuroraDatabase) {
    private val dao = database.recentlyPlayedDao()

    fun recordPlay(trackId: Long, positionMs: Long = 0L, source: String = "local") {
        dao.insert(
            RecentlyPlayedEntity(
                trackId = trackId,
                positionMs = positionMs,
                source = source
            )
        )
    }

    fun getRecent(limit: Int = 20): List<RecentlyPlayedEntity> = dao.getRecent(limit)

    fun clearAll(): Int = dao.clearAll()
}
