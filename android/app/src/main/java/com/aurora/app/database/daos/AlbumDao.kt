package com.aurora.app.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aurora.app.database.entities.AlbumEntity

@Dao
interface AlbumDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(albums: List<AlbumEntity>)

    @Query("SELECT * FROM albums ORDER BY title COLLATE NOCASE ASC")
    fun getAll(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE album_id = :albumId LIMIT 1")
    fun getById(albumId: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE title = :title LIMIT 1")
    fun getByTitle(title: String): AlbumEntity?

    @Update
    fun update(album: AlbumEntity): Int
}
