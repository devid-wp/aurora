package com.aurora.app.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aurora.app.database.entities.FavoriteEntity

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addFavorite(favorite: FavoriteEntity): Long

    @Query("SELECT * FROM favorites ORDER BY created_at DESC")
    fun getAll(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE track_id = :trackId LIMIT 1")
    fun getByTrackId(trackId: Long): FavoriteEntity?

    @Query("DELETE FROM favorites WHERE track_id = :trackId")
    fun removeByTrackId(trackId: Long): Int

    @Query("DELETE FROM favorites")
    fun clearAll(): Int
}
