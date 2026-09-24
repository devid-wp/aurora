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

    /**
     * Saved online library entries: adds online origin, remote artwork, and
     * the saved flag. Existing rows are preserved untouched (new columns
     * default to empty/absent/false).
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN source TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tracks ADD COLUMN source_track_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tracks ADD COLUMN artwork_url TEXT")
            db.execSQL("ALTER TABLE tracks ADD COLUMN is_saved INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_source_source_track_id ON tracks(source, source_track_id)")
        }
    }

    /**
     * Unified favorites, keyed by a source-aware identity string.
     *
     * The old `favorites` table was keyed by track id and carried a foreign key
     * to `tracks`; it is replaced by a table keyed by `favorite_key`
     * ("<source>:<sourceTrackId>" for online, "local:<trackId>" for local) with
     * no foreign key, so a favorite survives its remote row being replaced by a
     * downloaded local row. Existing rows (if any) migrate as local favorites;
     * the table was never written by the app, so this is a safe, lossless path.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS favorites_new (
                    favorite_key TEXT NOT NULL,
                    track_id INTEGER NOT NULL DEFAULT 0,
                    source TEXT NOT NULL DEFAULT '',
                    source_track_id TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY(favorite_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO favorites_new (favorite_key, track_id, created_at)
                SELECT 'local:' || track_id, track_id, created_at FROM favorites
                """.trimIndent()
            )
            db.execSQL("DROP TABLE favorites")
            db.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_track_id ON favorites(track_id)")
        }
    }

    /**
     * Records whether a saved online entry's source explicitly permitted a
     * persistent download at save time. Existing rows are preserved and default
     * to "not permitted"; the live source is re-checked before any download.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN download_permitted INTEGER NOT NULL DEFAULT 0")
        }
    }
}
