package com.aurora.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.aurora.app.database.daos.AlbumDao
import com.aurora.app.database.daos.ArtistDao
import com.aurora.app.database.daos.DownloadDao
import com.aurora.app.database.daos.FavoriteDao
import com.aurora.app.database.daos.PlaylistDao
import com.aurora.app.database.daos.PlaylistTrackDao
import com.aurora.app.database.daos.RecentlyPlayedDao
import com.aurora.app.database.daos.TrackDao
import com.aurora.app.database.entities.AlbumEntity
import com.aurora.app.database.entities.ArtistEntity
import com.aurora.app.database.entities.DownloadEntity
import com.aurora.app.database.entities.FavoriteEntity
import com.aurora.app.database.entities.PlaylistEntity
import com.aurora.app.database.entities.PlaylistTrackEntity
import com.aurora.app.database.entities.RecentlyPlayedEntity
import com.aurora.app.database.entities.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        FavoriteEntity::class,
        RecentlyPlayedEntity::class,
        DownloadEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AuroraDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: AuroraDatabase? = null

        fun getInstance(context: Context): AuroraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuroraDatabase::class.java,
                    "aurora.db"
                )
                    .addMigrations(
                        AuroraDatabaseMigrations.MIGRATION_1_2,
                        AuroraDatabaseMigrations.MIGRATION_2_3,
                        AuroraDatabaseMigrations.MIGRATION_3_4
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun inMemory(context: Context): AuroraDatabase {
            return Room.inMemoryDatabaseBuilder(context.applicationContext, AuroraDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        }
    }
}
