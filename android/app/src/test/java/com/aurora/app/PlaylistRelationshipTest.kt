package com.aurora.app

import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistRelationshipTest {
    private val db: AuroraDatabase = AuroraDatabase.inMemory(ApplicationProvider.getApplicationContext())

    @Test
    fun playlist_track_relationships_are_saved() {
        db.trackDao().upsert(
            TrackEntity(
                id = 11L,
                title = "Track A",
                artist = "Artist",
                album = "Album",
                durationMs = 1000L,
                uri = "content://audio/playlist/11"
            )
        )
        db.trackDao().upsert(
            TrackEntity(
                id = 12L,
                title = "Track B",
                artist = "Artist",
                album = "Album",
                durationMs = 2000L,
                uri = "content://audio/playlist/12"
            )
        )

        val playlistId = db.playlistDao().insert(
            com.aurora.app.database.entities.PlaylistEntity(name = "Favorites", description = "Test")
        )

        db.playlistTrackDao().insertAll(
            listOf(
                com.aurora.app.database.entities.PlaylistTrackEntity(playlistId = playlistId, trackId = 11L, position = 0),
                com.aurora.app.database.entities.PlaylistTrackEntity(playlistId = playlistId, trackId = 12L, position = 1)
            )
        )

        val withTracks = db.playlistDao().getPlaylistWithTracks(playlistId)
        assertEquals(2, withTracks?.tracks?.size)
        assertEquals("Favorites", withTracks?.playlist?.name)
    }
}
