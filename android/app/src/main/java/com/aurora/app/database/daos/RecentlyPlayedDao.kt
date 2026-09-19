package com.aurora.app.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aurora.app.database.entities.RecentlyPlayedEntity

@Dao
interface RecentlyPlayedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entry: RecentlyPlayedEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<RecentlyPlayedEntity>)

    @Query("SELECT * FROM recently_played ORDER BY played_at DESC LIMIT :limit")
    fun getRecent(limit: Int): List<RecentlyPlayedEntity>

    @Query("SELECT * FROM recently_played WHERE track_id = :trackId ORDER BY played_at DESC LIMIT 1")
    fun getLatestForTrack(trackId: Long): RecentlyPlayedEntity?

    @Query("DELETE FROM recently_played")
    fun clearAll(): Int
}
