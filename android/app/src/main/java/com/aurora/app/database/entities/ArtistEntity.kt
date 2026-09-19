package com.aurora.app.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "artists",
    indices = [Index(value = ["name"], unique = false)]
)
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "artist_id")
    val artistId: Long = 0L,
    val name: String,
    @ColumnInfo(name = "sort_name")
    val sortName: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = createdAt
)
