package com.aurora.app.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["artist_id"]),
        Index(value = ["album_id"]),
        Index(value = ["source_type"]),
        Index(value = ["is_aurora_imported"]),
        Index(value = ["content_hash"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["artist_id"],
            childColumns = ["artist_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["album_id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class TrackEntity(
    @PrimaryKey
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    val uri: String,
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long? = null,
    @ColumnInfo(name = "artist_id")
    val artistId: Long? = null,
    @ColumnInfo(name = "album_id")
    val albumId: Long? = null,
    @ColumnInfo(name = "source_type")
    val sourceType: String = "media_store",
    @ColumnInfo(name = "is_aurora_imported")
    val isAuroraImported: Boolean = false,
    @ColumnInfo(name = "local_path")
    val localPath: String? = null,
    @ColumnInfo(name = "artwork_path")
    val artworkPath: String? = null,
    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = createdAt
)
