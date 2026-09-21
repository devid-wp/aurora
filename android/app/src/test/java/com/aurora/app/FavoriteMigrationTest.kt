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
 * Verifies the v5 → v6 favorites migration that switches from a track-id key
 * with a foreign key to a source-aware `favorite_key` without a foreign key.
 * Existing rows must be preserved as local favorites.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migration_5_6_preserves_legacy_favorites_as_local_keys() {
        val dbName = "favorite-migration-${System.nanoTime()}.db"
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Old v5 favorites table.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS favorites (" +
                            "track_id INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                            "PRIMARY KEY(track_id))"
                    )
                    db.execSQL("INSERT INTO favorites (track_id, created_at) VALUES (5, 111)")
                    db.execSQL("INSERT INTO favorites (track_id, created_at) VALUES (9, 222)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            AuroraDatabaseMigrations.MIGRATION_5_6.migrate(db)

            db.query("SELECT favorite_key, track_id, source, source_track_id FROM favorites ORDER BY track_id").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("local:5", cursor.getString(0))
                assertEquals(5L, cursor.getLong(1))
                assertEquals("", cursor.getString(2))
                assertEquals("", cursor.getString(3))

                assertTrue(cursor.moveToNext())
                assertEquals("local:9", cursor.getString(0))
                assertEquals(9L, cursor.getLong(1))
                assertFalse("all legacy rows must be preserved", cursor.moveToNext())
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }
}
