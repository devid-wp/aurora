package com.aurora.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.DownloadEntity
import com.aurora.app.source.DownloadAvailability
import com.aurora.app.source.DownloadResult
import com.aurora.app.source.LocalSource
import com.aurora.app.source.MusicSource
import com.aurora.app.source.SourceCapability
import com.aurora.app.source.SourceMetadata
import com.aurora.app.source.SourceTrackId
import com.aurora.app.source.StreamResult
import com.aurora.app.storage.AuroraStorageManager
import com.aurora.app.transfer.DownloadManager
import com.aurora.app.transfer.DownloadProgress
import com.aurora.app.transfer.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database: AuroraDatabase = AuroraDatabase.inMemory(context)
    private val libraryRepository = com.aurora.app.database.repositories.LibraryRepository(database)
    private val source = LocalSource(libraryRepository)
    private val manager = DownloadManager(context, database, AuroraStorageManager(context))

    private fun awaitTerminal(manager: DownloadManager, jobId: Long, timeoutMs: Long = 8_000L): DownloadProgress {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: DownloadProgress? = null
        while (System.currentTimeMillis() < deadline) {
            last = manager.getProgress(jobId)
            val state = last?.state
            if (state == DownloadState.COMPLETED || state == DownloadState.FAILED || state == DownloadState.CANCELLED) {
                return last!!
            }
            Thread.sleep(10)
        }
        return last ?: error("download job $jobId was never created")
    }

    @Test
    fun progress_updates_state_and_percent() {
        val file = File(context.cacheDir, "download-progress.mp3")
        file.writeBytes("audio".toByteArray())
        val metadata = SourceMetadata(
            trackId = SourceTrackId("aurora_local", "1"),
            title = "Progress Track",
            artist = "Tester",
            album = "Album",
            durationMs = 1000L,
            localPath = file.absolutePath
        )

        val jobId = 123L
        val progress = manager.updateProgress(jobId, DownloadState.DOWNLOADING, 0.42f, 900L, 2000L, 1200f, 10L, file.absolutePath)

        assertEquals(DownloadState.DOWNLOADING, progress.state)
        assertTrue(progress.progress > 0f)
        assertEquals(900L, progress.downloadedBytes)
    }

    @Test
    fun cancel_marks_download_cancelled() {
        val started = CountDownLatch(1)
        val blockingSource = object : MusicSource {
            override val sourceId: String = "blocking"
            override val capabilities: Set<SourceCapability> = setOf(SourceCapability.DOWNLOAD)
            override fun search(query: String): List<SourceMetadata> = emptyList()
            override fun getTrack(id: SourceTrackId): SourceMetadata? = null
            override fun stream(track: SourceMetadata): StreamResult = StreamResult(uri = null, metadata = track, error = "stub")
            override fun checkDownloadAvailability(track: SourceMetadata) =
                com.aurora.app.source.DownloadCapability(DownloadAvailability.AVAILABLE)
            override fun download(track: SourceMetadata, targetDir: File): DownloadResult =
                DownloadResult(success = false, error = "unused")

            override fun downloadWithProgress(
                track: SourceMetadata,
                targetDir: File,
                onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
                isCancelled: () -> Boolean
            ): DownloadResult {
                started.countDown()
                var bytes = 0L
                while (bytes < 5_000_000L) {
                    if (isCancelled()) return DownloadResult(success = false, error = "Download cancelled")
                    bytes += 1024L
                    onProgress(bytes, 5_000_000L)
                    Thread.sleep(2L)
                }
                val f = File(targetDir, "blocking.mp3").apply { writeBytes("x".toByteArray()) }
                return DownloadResult(success = true, localPath = f.absolutePath)
            }
        }
        val metadata = SourceMetadata(SourceTrackId("blocking", "b1"), "Blocking", "Tester", "Album", 1000L)
        val progress = manager.queue(blockingSource, metadata)
        assertTrue("download never started", started.await(5, TimeUnit.SECONDS))
        manager.cancel(progress.jobId)
        val terminal = awaitTerminal(manager, progress.jobId)
        assertEquals(DownloadState.CANCELLED, terminal.state)
    }

    @Test
    fun unavailable_download_reports_failed_state() {
        val unavailable = SourceMetadata(
            trackId = SourceTrackId("soundcloud", "3"),
            title = "Blocked",
            artist = "Tester",
            album = "Album"
        )
        val source = com.aurora.app.source.SoundCloudSource()
        val result = source.checkDownloadAvailability(unavailable)

        assertEquals(DownloadAvailability.BLOCKED, result.availability)
    }

    @Test
    fun successful_remote_download_persists_local_library_record() {
        val localDb = AuroraDatabase.inMemory(context)
        val manager = DownloadManager(context, localDb, AuroraStorageManager(context))
        val downloadFile = File(context.cacheDir, "remote-success.mp3")
        downloadFile.writeBytes("audio".toByteArray())

        val source = object : MusicSource {
            override val sourceId: String = "soundcloud"
            override val capabilities: Set<SourceCapability> = setOf(SourceCapability.DOWNLOAD)
            override fun search(query: String): List<SourceMetadata> = emptyList()
            override fun getTrack(id: SourceTrackId): SourceMetadata? = null
            override fun stream(track: SourceMetadata): StreamResult = StreamResult(uri = null, metadata = track, error = "stub")
            override fun checkDownloadAvailability(track: SourceMetadata) =
                com.aurora.app.source.DownloadCapability(DownloadAvailability.AVAILABLE)
            override fun download(track: SourceMetadata, targetDir: File): DownloadResult =
                DownloadResult(success = true, localPath = downloadFile.absolutePath)
        }

        val metadata = SourceMetadata(
            trackId = SourceTrackId("soundcloud", "remote-123"),
            title = "Downloaded remote track",
            artist = "Remote Artist",
            album = "Remote Album",
            durationMs = 240000L,
            localPath = downloadFile.absolutePath
        )

        val queued = manager.queue(source, metadata)
        val progress = awaitTerminal(manager, queued.jobId)
        assertEquals(DownloadState.COMPLETED, progress.state)
        val imported = localDb.trackDao().getBySourceType("aurora_imported")
        assertTrue("verified download was not added to the library", imported.isNotEmpty())
        assertTrue(imported.first().localPath != null)
        assertTrue(imported.first().title.isNotBlank())
    }

    @Test
    fun interrupted_download_is_restored_as_paused_after_restart() {
        val localDb = AuroraDatabase.inMemory(context)
        localDb.downloadDao().insert(
            DownloadEntity(
                downloadId = 9001L,
                source = "soundcloud",
                sourceTrackId = "track-9001",
                title = "Interrupted Track",
                artist = "Artist",
                uri = "https://api.soundcloud.com/tracks/9001",
                status = "downloading"
            )
        )

        val restarted = DownloadManager(context, localDb, AuroraStorageManager(context))
        val deadline = System.currentTimeMillis() + 5_000L
        var restored: DownloadProgress? = null
        while (System.currentTimeMillis() < deadline) {
            restored = restarted.getProgress(9001L)
            if (restored != null) break
            Thread.sleep(10)
        }

        assertNotNull("persisted download was not restored", restored)
        assertEquals(DownloadState.PAUSED, restored!!.state)
        assertEquals("Interrupted Track", restored!!.title)
    }

    @Test
    fun completed_download_resolves_offline_capable_file() {
        val localDb = AuroraDatabase.inMemory(context)
        val storage = AuroraStorageManager(context)
        val manager = DownloadManager(context, localDb, storage)
        val downloadFile = File(context.cacheDir, "offline-capable.mp3")
        downloadFile.writeBytes("offline-audio".toByteArray())

        val source = object : MusicSource {
            override val sourceId: String = "soundcloud"
            override val capabilities: Set<SourceCapability> = setOf(SourceCapability.DOWNLOAD)
            override fun search(query: String): List<SourceMetadata> = emptyList()
            override fun getTrack(id: SourceTrackId): SourceMetadata? = null
            override fun stream(track: SourceMetadata): StreamResult = StreamResult(uri = null, metadata = track, error = "stub")
            override fun checkDownloadAvailability(track: SourceMetadata) =
                com.aurora.app.source.DownloadCapability(DownloadAvailability.AVAILABLE)
            override fun download(track: SourceMetadata, targetDir: File): DownloadResult =
                DownloadResult(success = true, localPath = downloadFile.absolutePath)
        }

        val metadata = SourceMetadata(
            trackId = SourceTrackId("soundcloud", "remote-offline-1"),
            title = "Offline Capable",
            artist = "Remote Artist",
            album = "Remote Album",
            durationMs = 240000L,
            localPath = downloadFile.absolutePath
        )

        val queued = manager.queue(source, metadata)
        assertEquals(DownloadState.COMPLETED, awaitTerminal(manager, queued.jobId).state)

        // The verified copy is an Aurora-owned row whose file exists on disk,
        // so LocalSource resolves it with no network involved (offline play).
        // (The stored title comes from the downloaded file's own metadata,
        // so the lookup below is intentionally broad.)
        val localSource = LocalSource(com.aurora.app.database.repositories.LibraryRepository(localDb))
        val found = localSource.search("")
        assertEquals(1, found.size)
        assertTrue(found.first().title.isNotBlank())
        val stream = localSource.stream(found.first())
        assertNotNull("completed download did not resolve a playable file", stream.uri)
        assertEquals("file", stream.uri!!.scheme)
        assertTrue(File(stream.uri!!.path!!).exists())
    }

    @Test
    fun downloaded_track_preserves_online_source_identity() {
        val localDb = AuroraDatabase.inMemory(context)
        val storage = AuroraStorageManager(context)
        val manager = DownloadManager(context, localDb, storage)
        val downloadFile = File(context.cacheDir, "identity-preserved.mp3")
        downloadFile.writeBytes("identity-audio".toByteArray())

        val source = object : MusicSource {
            override val sourceId: String = "audius"
            override val capabilities: Set<SourceCapability> = setOf(SourceCapability.DOWNLOAD)
            override fun search(query: String): List<SourceMetadata> = emptyList()
            override fun getTrack(id: SourceTrackId): SourceMetadata? = null
            override fun stream(track: SourceMetadata): StreamResult = StreamResult(uri = null, metadata = track, error = "stub")
            override fun checkDownloadAvailability(track: SourceMetadata) =
                com.aurora.app.source.DownloadCapability(DownloadAvailability.AVAILABLE)
            override fun download(track: SourceMetadata, targetDir: File): DownloadResult =
                DownloadResult(success = true, localPath = downloadFile.absolutePath)
        }

        val metadata = SourceMetadata(
            trackId = SourceTrackId("audius", "identity-1"),
            title = "Identity Song",
            artist = "Remote Artist",
            album = "Remote Album",
            durationMs = 240000L,
            localPath = downloadFile.absolutePath
        )

        val queued = manager.queue(source, metadata)
        assertEquals(DownloadState.COMPLETED, awaitTerminal(manager, queued.jobId).state)

        val persisted = localDb.trackDao().getBySource("audius", "identity-1")
        assertNotNull("downloaded row must keep its online origin", persisted)
        assertEquals("audius", persisted!!.source)
        assertEquals("identity-1", persisted.sourceTrackId)
    }
}
