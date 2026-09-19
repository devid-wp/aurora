package com.aurora.app.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aurora.app.database.entities.ArtistEntity

@Dao
interface ArtistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(artist: ArtistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(artists: List<ArtistEntity>)

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    fun getAll(): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE artist_id = :artistId LIMIT 1")
    fun getById(artistId: Long): ArtistEntity?

    @Query("SELECT * FROM artists WHERE name = :name LIMIT 1")
    fun getByName(name: String): ArtistEntity?

    @Update
    fun update(artist: ArtistEntity): Int
}
