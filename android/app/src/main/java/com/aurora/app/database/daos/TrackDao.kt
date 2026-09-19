package com.aurora.app.database.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aurora.app.database.entities.TrackEntity

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(track: TrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    fun getAll(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    fun getById(trackId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE content_hash = :contentHash LIMIT 1")
    fun getByContentHash(contentHash: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE source_type = :sourceType ORDER BY title COLLATE NOCASE ASC")
    fun getBySourceType(sourceType: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE artist_id = :artistId ORDER BY title COLLATE NOCASE ASC")
    fun getByArtistId(artistId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE album_id = :albumId ORDER BY title COLLATE NOCASE ASC")
    fun getByAlbumId(albumId: Long): List<TrackEntity>

    @Update
    fun update(track: TrackEntity): Int

    @Delete
    fun delete(track: TrackEntity): Int

    @Query("DELETE FROM tracks WHERE id = :trackId")
    fun deleteById(trackId: Long): Int

    @Query("DELETE FROM tracks")
    fun clearAll(): Int
}
