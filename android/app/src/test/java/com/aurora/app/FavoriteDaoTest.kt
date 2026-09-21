package com.aurora.app

import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.FavoriteEntity
import com.aurora.app.database.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteDaoTest {
    private val db: AuroraDatabase = AuroraDatabase.inMemory(ApplicationProvider.getApplicationContext())

    @Test
    fun favorites_are_keyed_by_source_aware_identity() {
        db.trackDao().upsert(
            TrackEntity(
                id = 5L,
                title = "Favorite Song",
                artist = "A",
                album = "B",
                durationMs = 1000L,
                uri = "content://audio/favorite/5"
            )
        )

        db.favoriteDao().upsert(
            FavoriteEntity(favoriteKey = "local:5", trackId = 5L, source = "", sourceTrackId = "")
        )
        val favorites = db.favoriteDao().getAll()

        assertEquals(1, favorites.size)
        assertTrue(favorites.any { it.trackId == 5L && it.favoriteKey == "local:5" })
        assertNotNull(db.favoriteDao().getByKey("local:5"))

        db.favoriteDao().removeByKey("local:5")
        assertNull(db.favoriteDao().getByKey("local:5"))
        assertTrue(db.favoriteDao().getAll().isEmpty())
    }

    @Test
    fun favorites_do_not_require_a_track_row() {
        // An online favorite may exist before/without a local track row: there
        // is no foreign key, so it must insert cleanly.
        db.favoriteDao().upsert(
            FavoriteEntity(
                favoriteKey = "audius:abc",
                trackId = 123L,
                source = "audius",
                sourceTrackId = "abc"
            )
        )

        val stored = db.favoriteDao().getByKey("audius:abc")
        assertNotNull(stored)
        assertEquals("audius", stored!!.source)
        assertEquals("abc", stored.sourceTrackId)
    }
}
