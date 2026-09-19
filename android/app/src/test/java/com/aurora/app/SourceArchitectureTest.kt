package com.aurora.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.AlbumEntity
import com.aurora.app.database.entities.ArtistEntity
import com.aurora.app.database.entities.TrackEntity
import com.aurora.app.database.repositories.LibraryRepository
import com.aurora.app.source.DownloadAvailability
import com.aurora.app.source.LocalSource
import com.aurora.app.source.SourceCapability
import com.aurora.app.source.SourceMetadata
import com.aurora.app.source.SourceTrackId
import com.aurora.app.source.SoundCloudSource
import com.aurora.app.storage.AuroraStorageManager
import com.aurora.app.transfer.DownloadManager
import com.aurora.app.transfer.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceArchitectureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database: AuroraDatabase = AuroraDatabase.inMemory(context)
    private val libraryRepository = LibraryRepository(database)
    private val storage = AuroraStorageManager(context)
    private val manager = DownloadManager(context, database, storage)

    @Before
    fun seedAuroraLibrary() {
        val artistId = database.artistDao().upsert(ArtistEntity(name = "Demo Artist", sortName = "Demo Artist"))
        val albumId = database.albumDao().upsert(
            AlbumEntity(title = "Demo Album", artistId = artistId, artworkUri = null)
        )

        val file = File(context.cacheDir, "demo-track.mp3")
        file.writeBytes("demo-audio".toByteArray())

        database.trackDao().upsert(
            TrackEntity(
                id = 101L,
                title = "Demo Track",
                artist = "Demo Artist",
                album = "Demo Album",
                durationMs = 210000L,
                uri = Uri.fromFile(file).toString(),
                artistId = artistId,
                albumId = albumId,
                sourceType = "aurora_imported",
                isAuroraImported = true,
                localPath = file.absolutePath,
                contentHash = "demo-hash"
            )
        )
    }

    @Test
    fun local_source_reports_local_capabilities() {
        val source = LocalSource(libraryRepository)
        val results = source.search("Demo")

        assertFalse(results.isEmpty())
        assertTrue(source.capabilities.contains(SourceCapability.LOCAL))
        assertEquals(DownloadAvailability.AVAILABLE, source.checkDownloadAvailability(results.first()).availability)
    }

    @Test
    fun soundcloud_source_is_blocked() {
        val source = SoundCloudSource()
        val result = source.checkDownloadAvailability(
            SourceMetadata(
                trackId = SourceTrackId(source.sourceId, "sc-1"),
                title = "Preview Track",
                artist = "Artist",
                album = "Album"
            )
        )

        assertEquals(DownloadAvailability.BLOCKED, result.availability)
    }

    @Test
    fun download_manager_tracks_completed_state() {
        val source = LocalSource(libraryRepository)
        val track = source.search("Demo").first()

        val queued = manager.queue(source, track, associatedTrackId = 101L)
        val deadline = System.currentTimeMillis() + 8_000L
        var progress = manager.getProgress(queued.jobId)
        while (System.currentTimeMillis() < deadline) {
            progress = manager.getProgress(queued.jobId)
            if (progress?.state == DownloadState.COMPLETED || progress?.state == DownloadState.FAILED) break
            Thread.sleep(10)
        }

        assertEquals(DownloadState.COMPLETED, progress?.state)
        assertTrue(progress!!.progress >= 1f)
    }

    @Test
    fun library_repository_filters_aurora_local_data() {
        val tracks = libraryRepository.getAuroraTracks()

        assertEquals(1, tracks.size)
        assertTrue(libraryRepository.getAuroraArtists().contains("Demo Artist"))
        assertTrue(libraryRepository.getAuroraAlbums().contains("Demo Album"))
    }
}
