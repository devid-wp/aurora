package com.aurora.app.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted download job.
 *
 * [trackId] is nullable because a remote search result has no local track row
 * until the transfer completes and metadata is extracted. The job carries
 * enough source metadata to survive an app restart and be shown correctly in
 * the Downloads screen.
 */
@Entity(
    tableName = "downloads",
    indices = [Index(value = ["track_id"]), Index(value = ["status"])]
)
data class DownloadEntity(
    @PrimaryKey
    @ColumnInfo(name = "download_id")
    val downloadId: Long = 0L,
    @ColumnInfo(name = "track_id")
    val trackId: Long? = null,
    @ColumnInfo(name = "source")
    val source: String = "",
    @ColumnInfo(name = "source_track_id")
    val sourceTrackId: String = "",
    val title: String = "",
    val artist: String = "",
    val uri: String,
    val status: String = "queued",
    @ColumnInfo(name = "local_path")
    val localPath: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "downloaded_bytes")
    val downloadedBytes: Long = 0L,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long = 0L,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = createdAt,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
)
