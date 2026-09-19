package com.aurora.app.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AuroraDatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Reserved for future schema changes. Keeping a defined migration keeps the
            // database versioning path explicit and safe for future releases.
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN content_hash TEXT")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_tracks_content_hash ON tracks(content_hash)")
        }
    }

    /**
     * Downloads outlive the track row: a remote result has no local track until
     * the transfer completes. Rebuild the table with a nullable track_id, drop
     * the foreign key, and add the source metadata needed to restore jobs after
     * an app restart.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS downloads_new (
                    download_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    track_id INTEGER,
                    source TEXT NOT NULL DEFAULT '',
                    source_track_id TEXT NOT NULL DEFAULT '',
                    title TEXT NOT NULL DEFAULT '',
                    artist TEXT NOT NULL DEFAULT '',
                    uri TEXT NOT NULL,
                    status TEXT NOT NULL,
                    local_path TEXT,
                    error_message TEXT,
                    downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                    total_bytes INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    completed_at INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO downloads_new (
                    download_id, track_id, uri, status, local_path, error_message,
                    created_at, updated_at, completed_at
                )
                SELECT download_id, track_id, uri, status, local_path, error_message,
                       created_at, updated_at, completed_at
                FROM downloads
                """.trimIndent()
            )
            db.execSQL("DROP TABLE downloads")
            db.execSQL("ALTER TABLE downloads_new RENAME TO downloads")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_track_id ON downloads(track_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_status ON downloads(status)")
        }
    }
}
