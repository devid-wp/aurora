package com.aurora.app.database.repositories

import com.aurora.app.Track
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.TrackEntity

class TrackRepository(private val database: AuroraDatabase) {
    private val dao = database.trackDao()

    fun upsert(track: Track): Long {
        return dao.upsert(track.toEntity())
    }

    fun upsertAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        dao.upsertAll(tracks.map { it.toEntity() })
    }

    fun getAll(): List<TrackEntity> = dao.getAll()

    fun getById(trackId: Long): TrackEntity? = dao.getById(trackId)

    fun getByContentHash(contentHash: String): TrackEntity? = dao.getByContentHash(contentHash)

    fun delete(trackId: Long): Int = dao.deleteById(trackId)

    fun clearAll(): Int = dao.clearAll()

    private fun Track.toEntity(): TrackEntity = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = duration,
        uri = uri.toString(),
        mediaStoreId = id,
        sourceType = "media_store",
        isAuroraImported = false
    )
}
