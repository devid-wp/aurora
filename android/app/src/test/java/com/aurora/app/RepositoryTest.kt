package com.aurora.app

import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.repositories.FavoriteRepository
import com.aurora.app.database.repositories.LibraryRepository
import com.aurora.app.database.repositories.PlaybackHistoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {
    private val db: AuroraDatabase = AuroraDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val libraryRepository = LibraryRepository(db)
    private val favoriteRepository = FavoriteRepository(db)
    private val historyRepository = PlaybackHistoryRepository(db)

    @Test
    fun repositories_sync_and_manage_library_state() {
        val tracks = listOf(
            Track(
                id = 20L,
                title = "Repo Song 1",
                artist = "Repo Artist",
                album = "Repo Album",
                duration = 100000L,
                uri = android.net.Uri.parse("content://audio/repo/20"),
                albumId = 1L
            ),
            Track(
                id = 21L,
                title = "Repo Song 2",
                artist = "Repo Artist",
                album = "Repo Album",
                duration = 200000L,
                uri = android.net.Uri.parse("content://audio/repo/21"),
                albumId = 1L
            )
        )

        libraryRepository.syncMediaStoreTracks(tracks)
        assertEquals(2, libraryRepository.getAllTracks().size)

        favoriteRepository.add(20L)
        assertTrue(favoriteRepository.isFavorite(20L))

        historyRepository.recordPlay(20L, 5000L)
        assertEquals(1, historyRepository.getRecent(10).size)
    }

    @Test
    fun phone_music_rows_are_preserved_but_never_surfaced_as_aurora() {
        val tracks = listOf(
            Track(
                id = 30L,
                title = "Phone Song",
                artist = "Phone Artist",
                album = "Phone Album",
                duration = 100000L,
                uri = android.net.Uri.parse("content://audio/phone/30"),
                albumId = 1L
            )
        )

        libraryRepository.syncMediaStoreTracks(tracks)

        // Existing device rows are preserved (never deleted) ...
        assertEquals(1, libraryRepository.getAllTracks().size)
        // ... but Aurora never surfaces phone music as its own library.
        assertTrue(libraryRepository.getAuroraTracks().isEmpty())
        assertTrue(libraryRepository.getAuroraTrackEntities().isEmpty())
        assertTrue(libraryRepository.getAuroraArtists().isEmpty())
        assertTrue(libraryRepository.getAuroraAlbums().isEmpty())
    }
}
