package com.aurora.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabaseMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the v6 → v7 migration that adds `download_permitted` to saved online
 * tracks. Existing rows must be preserved and default to "not permitted" so a
 * legacy saved entry never claims a download the source did not grant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackDownloadPermittedMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun v6TracksTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tracks (
                id INTEGER PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                uri TEXT NOT NULL,
                source_type TEXT NOT NULL DEFAULT 'media_store',
                is_aurora_imported INTEGER NOT NULL DEFAULT 0,
                local_path TEXT,
                source TEXT NOT NULL DEFAULT '',
                source_track_id TEXT NOT NULL DEFAULT '',
                artwork_url TEXT,
                is_saved INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    @Test
    fun migration_6_7_preserves_rows_and_defaults_download_to_false() {
        val dbName = "download-migration-${System.nanoTime()}.db"
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    v6TracksTable(db)
                    db.execSQL(
                        "INSERT INTO tracks (id, title, artist, album, duration_ms, uri, " +
                            "source_type, is_aurora_imported, source, source_track_id, is_saved, created_at, updated_at) " +
                            "VALUES (7, 'Saved', 'Artist', 'Album', 1000, '', 'audius', 0, 'audius', 'abc', 1, 111, 111)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            AuroraDatabaseMigrations.MIGRATION_6_7.migrate(db)

            db.query("SELECT title, is_saved, download_permitted FROM tracks WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Saved", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals("legacy rows must default to not permitted", 0, cursor.getInt(2))
                assertFalse(cursor.moveToNext())
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }
}
