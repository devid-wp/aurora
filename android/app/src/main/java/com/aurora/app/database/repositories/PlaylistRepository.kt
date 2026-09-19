package com.aurora.app.database.repositories

import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.daos.PlaylistWithTracks
import com.aurora.app.database.entities.PlaylistEntity
import com.aurora.app.database.entities.PlaylistTrackEntity

class PlaylistRepository(private val database: AuroraDatabase) {
    private val playlistDao = database.playlistDao()
    private val playlistTrackDao = database.playlistTrackDao()

    fun createPlaylist(name: String, description: String? = null): Long {
        return playlistDao.insert(PlaylistEntity(name = name, description = description))
    }

    fun getAll(): List<PlaylistEntity> = playlistDao.getAll()

    fun getById(playlistId: Long): PlaylistEntity? = playlistDao.getById(playlistId)

    fun getWithTracks(playlistId: Long): PlaylistWithTracks? = playlistDao.getPlaylistWithTracks(playlistId)

    fun addTrack(playlistId: Long, trackId: Long, position: Int): Long {
        return playlistTrackDao.insert(
            PlaylistTrackEntity(
                playlistId = playlistId,
                trackId = trackId,
                position = position
            )
        )
    }

    fun removeTrack(playlistId: Long, trackId: Long): Int {
        return playlistTrackDao.delete(PlaylistTrackEntity(playlistId, trackId, 0))
    }

    fun deletePlaylist(playlistId: Long): Int = playlistDao.deleteById(playlistId)
}
