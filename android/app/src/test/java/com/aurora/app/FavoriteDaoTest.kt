package com.aurora.app

import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.FavoriteEntity
import com.aurora.app.database.entities.TrackEntity
import org.junit.Assert.assertEquals
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
    fun favorites_can_be_inserted_and_read_back() {
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

        db.favoriteDao().addFavorite(FavoriteEntity(trackId = 5L))
        val favorites = db.favoriteDao().getAll()

        assertEquals(1, favorites.size)
        assertTrue(favorites.any { it.trackId == 5L })
    }
}
