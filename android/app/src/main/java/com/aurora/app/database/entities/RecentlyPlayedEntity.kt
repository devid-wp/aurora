package com.aurora.app.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recently_played",
    indices = [Index(value = ["track_id"], unique = false)],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RecentlyPlayedEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "track_id")
    val trackId: Long,
    @ColumnInfo(name = "played_at")
    val playedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "position_ms")
    val positionMs: Long = 0L,
    val source: String = "local"
)
