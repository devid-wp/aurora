package com.aurora.app.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aurora.app.database.entities.DownloadEntity

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(download: DownloadEntity): Long

    @Update
    fun update(download: DownloadEntity): Int

    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE track_id = :trackId ORDER BY created_at DESC")
    fun getByTrackId(trackId: Long): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY created_at DESC")
    fun getByStatus(status: String): List<DownloadEntity>

    @Query(
        "SELECT * FROM downloads WHERE source = :source AND source_track_id = :sourceTrackId " +
            "AND status IN ('queued','resolving','downloading','processing','verifying','paused') " +
            "ORDER BY created_at DESC LIMIT 1"
    )
    fun findActive(source: String, sourceTrackId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE download_id = :downloadId LIMIT 1")
    fun getById(downloadId: Long): DownloadEntity?

    @Query("DELETE FROM downloads WHERE download_id = :downloadId")
    fun deleteById(downloadId: Long): Int
}
