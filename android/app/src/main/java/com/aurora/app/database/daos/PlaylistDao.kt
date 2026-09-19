package com.aurora.app.database.daos

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.aurora.app.database.entities.PlaylistEntity
import com.aurora.app.database.entities.PlaylistTrackEntity

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(playlist: PlaylistEntity): Long

    @Update
    fun update(playlist: PlaylistEntity): Int

    @Query("SELECT * FROM playlists ORDER BY updated_at DESC")
    fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE playlist_id = :playlistId LIMIT 1")
    fun getById(playlistId: Long): PlaylistEntity?

    @Transaction
    @Query("SELECT * FROM playlists WHERE playlist_id = :playlistId LIMIT 1")
    fun getPlaylistWithTracks(playlistId: Long): PlaylistWithTracks?

    @Query("DELETE FROM playlists WHERE playlist_id = :playlistId")
    fun deleteById(playlistId: Long): Int
}

data class PlaylistWithTracks(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "playlist_id",
        entityColumn = "playlist_id",
        entity = PlaylistTrackEntity::class
    )
    val tracks: List<PlaylistTrackEntity>
)
