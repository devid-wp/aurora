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

    @Test
    fun saved_remote_track_persists_source_identity_and_stable_id() {
        val metadata = SourceMetadata(
            trackId = SourceTrackId("audius", "track_stable"),
            title = "Stable Song",
            artist = "Artist",
            album = "Album",
            durationMs = 200000L,
            sourceCapabilities = setOf(com.aurora.app.source.SourceCapability.STREAM)
        )

        val id = libraryRepository.saveOnlineTrack(metadata)

        val row = libraryRepository.getTrack(id)!!
        assertEquals("audius", row.source)
        assertEquals("track_stable", row.sourceTrackId)
        assertEquals(com.aurora.app.source.stableSourceTrackId("audius", "track_stable"), row.id)
        assertTrue(row.isSaved)
        assertTrue(row.localPath == null)
    }

    @Test
    fun saved_downloadable_track_exposes_download_capability() {
        val metadata = SourceMetadata(
            trackId = SourceTrackId("audius", "track_dl"),
            title = "Downloadable",
            artist = "Artist",
            album = "Album",
            durationMs = 200000L,
            sourceCapabilities = setOf(
                com.aurora.app.source.SourceCapability.STREAM,
                com.aurora.app.source.SourceCapability.DOWNLOAD
            )
        )

        val id = libraryRepository.saveOnlineTrack(metadata)

        assertTrue(libraryRepository.getTrack(id)!!.downloadPermitted)
        val savedMeta = libraryRepository.getSavedOnlineMetadata().getValue(id)
        assertTrue(savedMeta.sourceCapabilities.contains(com.aurora.app.source.SourceCapability.DOWNLOAD))
    }

    @Test
    fun saved_stream_only_track_has_no_download_capability() {
        val metadata = SourceMetadata(
            trackId = SourceTrackId("audius", "track_stream"),
            title = "Stream Only",
            artist = "Artist",
            album = "Album",
            durationMs = 200000L,
            sourceCapabilities = setOf(com.aurora.app.source.SourceCapability.STREAM)
        )

        val id = libraryRepository.saveOnlineTrack(metadata)

        assertFalse(libraryRepository.getTrack(id)!!.downloadPermitted)
        val savedMeta = libraryRepository.getSavedOnlineMetadata().getValue(id)
        assertFalse(savedMeta.sourceCapabilities.contains(com.aurora.app.source.SourceCapability.DOWNLOAD))
    }

    @Test
    fun refresh_saved_metadata_updates_download_flag_without_resetting_file() {
        val metadata = SourceMetadata(
            trackId = SourceTrackId("audius", "track_refresh"),
            title = "Refresh",
            artist = "Artist",
            album = "Album",
            durationMs = 200000L,
            sourceCapabilities = setOf(com.aurora.app.source.SourceCapability.STREAM)
        )
        val id = libraryRepository.saveOnlineTrack(metadata)
        assertFalse(libraryRepository.getTrack(id)!!.downloadPermitted)

        val nowDownloadable = metadata.copy(
            sourceCapabilities = setOf(
                com.aurora.app.source.SourceCapability.STREAM,
                com.aurora.app.source.SourceCapability.DOWNLOAD
            )
        )
        assertTrue(libraryRepository.refreshSavedOnlineMetadata(nowDownloadable))

        val row = libraryRepository.getTrack(id)!!
        assertTrue(row.downloadPermitted)
        assertEquals(metadata.title, row.title)
    }

    @Test
    fun saved_entry_with_local_copy_is_excluded_from_visible_library() {
        val metadata = SourceMetadata(
            trackId = SourceTrackId("audius", "track_dup"),
            title = "Dup",
            artist = "Artist",
            album = "Album",
            durationMs = 200000L,
            sourceCapabilities = setOf(com.aurora.app.source.SourceCapability.STREAM)
        )
        val savedId = libraryRepository.saveOnlineTrack(metadata)

        val superseded = com.aurora.app.database.repositories.supersededSavedOnlineIds(
            savedMetadata = libraryRepository.getSavedOnlineMetadata(),
            downloads = emptyList(),
            localCopySourceKeys = setOf("audius" to "track_dup")
        )

        val visible = libraryRepository.getAuroraTracksExcluding(superseded)
        assertTrue("superseded saved entry must be hidden", superseded.contains(savedId))
        assertTrue(visible.none { it.id == savedId })
    }
}
