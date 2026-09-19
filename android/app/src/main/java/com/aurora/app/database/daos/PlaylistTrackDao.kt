package com.aurora.app.database.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aurora.app.database.entities.PlaylistTrackEntity

@Dao
interface PlaylistTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: PlaylistTrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(items: List<PlaylistTrackEntity>)

    @Delete
    fun delete(item: PlaylistTrackEntity): Int

    @Query("SELECT * FROM playlist_tracks WHERE playlist_id = :playlistId ORDER BY position ASC")
    fun getTracksForPlaylist(playlistId: Long): List<PlaylistTrackEntity>

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    fun clear(playlistId: Long): Int
}
