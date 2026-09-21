package com.aurora.app.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One favorited track, regardless of source.
 *
 * [favoriteKey] is the single source of truth identity:
 *  - online:  `<source>:<sourceTrackId>` (e.g. "audius:zKYMk7p")
 *  - local:   `local:<trackId>`
 *
 * There is deliberately no foreign key to `tracks`: an online favorite is
 * always saved to the library as well, but the favorite must survive an
 * unfavorite → re-favorite cycle and a downloaded copy replacing the remote
 * row without losing the user's intent. [trackId]/[source]/[sourceTrackId] are
 * denormalised copies for quick lookups and for rebuilding a `Track` when the
 * library row is momentarily absent.
 */
@Entity(
    tableName = "favorites",
    primaryKeys = ["favorite_key"],
    indices = [Index(value = ["track_id"])]
)
data class FavoriteEntity(
    @ColumnInfo(name = "favorite_key")
    val favoriteKey: String,
    @ColumnInfo(name = "track_id")
    val trackId: Long = 0L,
    @ColumnInfo(name = "source")
    val source: String = "",
    @ColumnInfo(name = "source_track_id")
    val sourceTrackId: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
