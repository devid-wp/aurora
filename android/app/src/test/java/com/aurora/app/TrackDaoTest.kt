package com.aurora.app

import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.AlbumEntity
import com.aurora.app.database.entities.ArtistEntity
import com.aurora.app.database.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackDaoTest {
    private val db: AuroraDatabase = AuroraDatabase.inMemory(ApplicationProvider.getApplicationContext())

    @Test
    fun trackDao_crud_roundTrip() {
        db.artistDao().upsert(ArtistEntity(artistId = 10L, name = "Test Artist"))
        db.albumDao().upsert(AlbumEntity(albumId = 20L, title = "Test Album", artistId = 10L))

        val track = TrackEntity(
            id = 42L,
            title = "Test Track",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 180000L,
            uri = "content://audio/test/42",
            artistId = 10L,
            albumId = 20L,
            sourceType = "media_store"
        )

        db.trackDao().upsert(track)
        val loaded = db.trackDao().getById(42L)
        assertNotNull(loaded)
        assertEquals("Test Track", loaded?.title)

        db.trackDao().update(track.copy(title = "Updated Track"))
        assertEquals("Updated Track", db.trackDao().getById(42L)?.title)

        db.trackDao().deleteById(42L)
        assertNull(db.trackDao().getById(42L))
    }
}
