package com.aurora.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.repositories.LibraryRepository
import com.aurora.app.source.SourceMetadata
import com.aurora.app.source.SourceTrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryOnlineSaveTest {
    private lateinit var db: AuroraDatabase
    private lateinit var libraryRepository: LibraryRepository

    @Before
    fun setup() {
        db = AuroraDatabase.inMemory(ApplicationProvider.getApplicationContext())
        libraryRepository = LibraryRepository(db)
    }

    @Test
    fun saved_remote_track_appears_in_library() {
        val metadata = SourceMetadata(
            trackId = SourceTrackId("audius", "track_123"),
            title = "Remote Song",
            artist = "Remote Artist",
            album = "Remote Album",
            durationMs = 180000L,
            artworkUri = Uri.parse("https://example.com/art.jpg"),
            sourceCapabilities = setOf(com.aurora.app.source.SourceCapability.STREAM)
        )

        val id = libraryRepository.saveOnlineTrack(metadata)
        
        assertTrue("Track should be saved", id > 0)
        assertTrue("Track should be in Aurora library", libraryRepository.getAuroraTracks().any { it.title == "Remote Song" })
        assertEquals(1, libraryRepository.getAuroraTracks().size)
    }

    @Test
    fun saved_remote_track_keeps_remote_artwork() {
        val metadata = SourceMetadata(
            trackId = SourceTrackId("audius", "track_art"),
            title = "Art Song",
            artist = "Artist",
            album = "Album",
            durationMs = 120000L,
            artworkUri = Uri.parse("https://example.com/cover.jpg"),
            sourceCapabilities = setOf(com.aurora.app.source.SourceCapability.STREAM)
        )
        libraryRepository.saveOnlineTrack(metadata)

        val saved = libraryRepository.getSavedOnlineTracks().single()
        assertEquals("https://example.com/cover.jpg", saved.artworkUri.toString())
    }

    @Test
    fun unsaved_remote_track_removed_from_library() {
        val metadata = SourceMetadata(
            trackId = SourceTrackId("audius", "track_123"),
            title = "Remote Song",
            artist = "Remote Artist",
            album = "Remote Album",
            durationMs = 180000L,
            artworkUri = Uri.parse("https://example.com/art.jpg"),
            sourceCapabilities = setOf(com.aurora.app.source.SourceCapability.STREAM)
        )

        val id = libraryRepository.saveOnlineTrack(metadata)
        libraryRepository.unsaveOnlineTrack(id)
        
        assertTrue("Library should be empty after unsaving", libraryRepository.getAuroraTracks().isEmpty())
    }

    @Test
    fun remote_track_is_not_counted_as_downloaded() {
        val metadata = SourceMetadata(
            trackId = SourceTrackId("audius", "track_123"),
            title = "Remote Song",
            artist = "Remote Artist",
            album = "Remote Album",
            durationMs = 180000L,
            artworkUri = Uri.parse("https://example.com/art.jpg"),
            sourceCapabilities = setOf(com.aurora.app.source.SourceCapability.STREAM)
        )

        libraryRepository.saveOnlineTrack(metadata)

        // A saved remote entry is in the library but has no local file, so it
        // must never be surfaced as a completed download.
        assertEquals(1, libraryRepository.getAuroraTracks().size)
        assertTrue(libraryRepository.getDownloadedTracks().isEmpty())
        assertEquals(1, libraryRepository.getSavedOnlineTracks().size)
    }
}
