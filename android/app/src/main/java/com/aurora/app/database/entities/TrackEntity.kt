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
        Index(value = ["content_hash"], unique = true),
        Index(value = ["source", "source_track_id"])
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
    /**
     * Online origin of a saved remote track ("audius", ...). Empty for local
     * rows. Together with [sourceTrackId] it addresses the track on its
     * source without persisting expiring signed stream URLs.
     */
    @ColumnInfo(name = "source")
    val source: String = "",
    @ColumnInfo(name = "source_track_id")
    val sourceTrackId: String = "",
    /** Remote artwork URL (online sources); local rows use [artworkPath]. */
    @ColumnInfo(name = "artwork_url")
    val artworkUrl: String? = null,
    /**
     * True for explicitly user-saved online library entries. Saved rows have
     * no local file ([localPath] null) and are never treated as downloaded.
     */
    @ColumnInfo(name = "is_saved")
    val isSaved: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = createdAt
)
