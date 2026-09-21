package com.aurora.app.transfer

import android.content.Context
import android.net.Uri
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.AlbumEntity
import com.aurora.app.database.entities.ArtistEntity
import com.aurora.app.database.entities.TrackEntity
import com.aurora.app.database.repositories.DownloadRepository
import com.aurora.app.source.DownloadAvailability
import com.aurora.app.source.DownloadCapability
import com.aurora.app.source.DownloadResult
import com.aurora.app.source.MusicSource
import com.aurora.app.source.SourceMetadata
import com.aurora.app.storage.AuroraStorageManager
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class DownloadState {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    PROCESSING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED
}

data class DownloadProgress(
    val jobId: Long,
    val source: String,
    val sourceTrackId: String,
    val title: String,
    val artist: String,
    val state: DownloadState,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Float = 0f,
    val etaSeconds: Long = 0L,
    val currentFile: String? = null,
    val error: String? = null
)

/**
 * Executes real downloads through the configured [MusicSource] implementations.
 *
 * Jobs run off the main thread, report live byte progress, honour
 * cancellation, verify + extract metadata from the finished file, persist a
 * [TrackEntity] so the track appears under Library → Downloaded, and persist
 * the job itself so it can be restored after an app restart. No download is
 * ever faked: a job only reaches COMPLETED after bytes exist on disk.
 */
class DownloadManager(
    private val context: Context,
    private val database: AuroraDatabase,
    private val storage: AuroraStorageManager = AuroraStorageManager(context.applicationContext)
) {
    private val downloadRepository = DownloadRepository(database)
    private val listeners = CopyOnWriteArrayList<(DownloadProgress) -> Unit>()
    private val jobs = ConcurrentHashMap<Long, DownloadProgress>()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()
    private val abortReasons = ConcurrentHashMap<Long, String>()
    private val lastNotifyMs = ConcurrentHashMap<Long, Long>()
    private val lastBytePersistMs = ConcurrentHashMap<Long, Long>()
    private val executor = Executors.newFixedThreadPool(2)
    private val idCounter = AtomicLong(System.currentTimeMillis())

    init {
        // Restore persisted jobs without touching the main thread (Room forbids it).
        executor.execute { restorePersistedJobs() }
    }

    fun subscribe(listener: (DownloadProgress) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (DownloadProgress) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Queues a real download. Returns immediately with the QUEUED job; progress
     * and terminal states are delivered through [subscribe]. A duplicate
     * request for a track already downloading returns the existing job.
     */
    fun queue(source: MusicSource, track: SourceMetadata, associatedTrackId: Long? = null): DownloadProgress {
        jobs.values.firstOrNull {
            it.source == source.sourceId && it.sourceTrackId == track.trackId.value && !isTerminal(it.state)
        }?.let { return it }

        val jobId = idCounter.incrementAndGet()
        val initial = DownloadProgress(
            jobId = jobId,
            source = source.sourceId,
            sourceTrackId = track.trackId.value,
            title = track.title.ifBlank { "Download" },
            artist = track.artist,
            state = DownloadState.QUEUED,
            currentFile = track.localPath ?: track.title
        )
        jobs[jobId] = initial
        notify(initial)
        executor.execute { runJob(jobId, source, track, associatedTrackId) }
        return initial
    }

    private fun runJob(jobId: Long, source: MusicSource, track: SourceMetadata, associatedTrackId: Long?) {
        persistJob(jobId, source, track, associatedTrackId)

        updateState(jobId, DownloadState.RESOLVING)
        val availability = try {
            source.checkDownloadAvailability(track)
        } catch (e: Exception) {
            DownloadCapability(DownloadAvailability.BLOCKED, e.message ?: "Download availability could not be checked")
        }
        if (availability.availability != DownloadAvailability.AVAILABLE) {
            fail(jobId, availability.reason.ifBlank { "This track cannot be downloaded from this source" })
            return
        }
        if (cancelled.contains(jobId)) {
            updateState(jobId, DownloadState.CANCELLED)
            return
        }

        updateState(jobId, DownloadState.DOWNLOADING)
        val startedMs = System.currentTimeMillis()
        val result: DownloadResult = try {
            source.downloadWithProgress(
                track = track,
                targetDir = storage.tracksDir,
                onProgress = { done, total ->
                    if (total > 0L && total > storage.tracksDir.usableSpace && !abortReasons.containsKey(jobId)) {
                        abortReasons[jobId] = "Not enough storage space to finish this download"
                    }
                    val elapsedMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(1L)
                    val speed = done * 1000f / elapsedMs
                    val eta = if (total > 0L && speed > 0f) ((total - done).coerceAtLeast(0L) / speed).toLong() else 0L
                    updateProgress(
                        jobId = jobId,
                        state = DownloadState.DOWNLOADING,
                        progress = if (total > 0L) (done.toFloat() / total.toFloat()) else 0f,
                        downloadedBytes = done,
                        totalBytes = total,
                        speedBytesPerSecond = speed,
                        etaSeconds = eta
                    )
                    // Persist byte counts periodically so an app restart can show
                    // honest progress instead of zeros.
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - (lastBytePersistMs[jobId] ?: 0L) >= 1000L) {
                        lastBytePersistMs[jobId] = nowMs
                        try {
                            downloadRepository.updateProgress(jobId, done, total)
                        } catch (_: Exception) {
                        }
                    }
                },
                isCancelled = { cancelled.contains(jobId) || abortReasons.containsKey(jobId) }
            )
        } catch (e: Exception) {
            DownloadResult(success = false, error = e.message ?: "Download failed")
        }

        val abortReason = abortReasons.remove(jobId)
        if (cancelled.contains(jobId)) {
            deleteFileQuietly(result.localPath)
            updateState(jobId, DownloadState.CANCELLED)
            return
        }
        if (abortReason != null) {
            deleteFileQuietly(result.localPath)
            fail(jobId, abortReason)
            return
        }
        if (!result.success || result.localPath.isNullOrBlank()) {
            deleteFileQuietly(result.localPath)
            fail(jobId, result.error ?: "Download failed")
            return
        }

        val file = File(result.localPath)
        if (!file.exists() || file.length() <= 0L) {
            file.delete()
            fail(jobId, "Downloaded file is empty or missing")
            return
        }

        updateState(jobId, DownloadState.PROCESSING)
        val metadata = try {
            storage.readAudioMetadata(file)
        } catch (_: Exception) {
            null
        }

        updateState(jobId, DownloadState.VERIFYING)
        val persistedTrackId = try {
            persistTrack(track, file, metadata)
        } catch (_: Exception) {
            null
        }
        if (persistedTrackId == null) {
            fail(jobId, "The downloaded file could not be verified or added to the library")
            return
        }

        try {
            downloadRepository.complete(jobId, persistedTrackId, file.absolutePath, file.length(), file.length())
        } catch (_: Exception) {
            // The in-memory state remains accurate even if the status write fails.
        }
        val completed = (jobs[jobId] ?: return).copy(
            state = DownloadState.COMPLETED,
            progress = 1f,
            downloadedBytes = file.length(),
            totalBytes = file.length(),
            speedBytesPerSecond = 0f,
            etaSeconds = 0L,
            currentFile = file.absolutePath,
            error = null
        )
        jobs[jobId] = completed
        notify(completed)
    }

    /** Moves a verified file's bytes into a durable TrackEntity (deduped by hash). */
    private fun persistTrack(
        track: SourceMetadata,
        file: File,
        metadata: com.aurora.app.storage.AudioFileMetadata?
    ): Long? {
        val hash = try {
            storage.calculateContentHash(file)
        } catch (_: Exception) {
            null
        }
        val title = metadata?.title?.takeIf { it.isNotBlank() } ?: track.title.ifBlank { file.nameWithoutExtension }
        val artist = metadata?.artist?.takeIf { it.isNotBlank() && it != "Unknown Artist" }
            ?: track.artist.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        val album = metadata?.album?.takeIf { it.isNotBlank() && it != "Unknown Album" }
            ?: track.album.takeIf { it.isNotBlank() } ?: "Unknown Album"
        val duration = metadata?.durationMs?.takeIf { it > 0L } ?: track.durationMs
        val artworkPath = metadata?.artworkPath

        if (!hash.isNullOrBlank()) {
            val existing = database.trackDao().getByContentHash(hash)
            if (existing != null) {
                // Same audio already in Aurora storage: keep the existing copy.
                val existingPath = existing.localPath
                if (existingPath == null || File(existingPath).absolutePath != file.absolutePath) {
                    file.delete()
                }
                // Backfill the online origin so a favorite keyed by
                // (source, sourceTrackId) still resolves to this row.
                if (existing.source.isBlank() && track.trackId.source.isNotBlank()) {
                    database.trackDao().upsert(
                        existing.copy(
                            source = track.trackId.source,
                            sourceTrackId = track.trackId.value,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                return existing.id
            }
        }

        val artistId = database.artistDao().getByName(artist)?.artistId
            ?: database.artistDao().upsert(ArtistEntity(name = artist, sortName = artist))
        val albumId = database.albumDao().getByTitle(album)?.albumId
            ?: database.albumDao().upsert(
                AlbumEntity(title = album, artistId = artistId, artworkUri = artworkPath)
            )

        val durableId = if (!hash.isNullOrBlank()) {
            val digest = MessageDigest.getInstance("SHA-256").digest(hash.toByteArray(Charsets.UTF_8))
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (digest[i].toLong() and 0xFFL)
            }
            val normalized = value and Long.MAX_VALUE
            if (normalized == 0L) 1L else normalized
        } else {
            System.currentTimeMillis()
        }

        val entity = TrackEntity(
            id = durableId,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration.coerceAtLeast(0L),
            uri = Uri.fromFile(file).toString(),
            mediaStoreId = null,
            artistId = artistId,
            albumId = albumId,
            sourceType = "aurora_imported",
            isAuroraImported = true,
            localPath = file.absolutePath,
            artworkPath = artworkPath,
            contentHash = hash,
            // Keep the online origin so the downloaded row and the remote
            // favorite share one source-aware identity.
            source = track.trackId.source,
            sourceTrackId = track.trackId.value,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val id = database.trackDao().upsert(entity)
        return if (id > 0L) id else null
    }

    private fun persistJob(jobId: Long, source: MusicSource, track: SourceMetadata, associatedTrackId: Long?) {
        try {
            downloadRepository.createJob(
                downloadId = jobId,
                source = source.sourceId,
                sourceTrackId = track.trackId.value,
                title = track.title,
                artist = track.artist,
                uri = track.localUri?.toString() ?: track.trackId.value,
                localPath = track.localPath,
                trackId = associatedTrackId
            )
        } catch (_: Exception) {
            // In-memory state still works when persistence is unavailable.
        }
    }

    private fun restorePersistedJobs() {
        val rows = try {
            downloadRepository.getAll()
        } catch (_: Exception) {
            return
        }
        rows.forEach { row ->
            val terminal = terminalStateFor(row.status)
            val interrupted = terminal == null
            val state = terminal ?: DownloadState.PAUSED
            val progress = DownloadProgress(
                jobId = row.downloadId,
                source = row.source.ifBlank { "unknown" },
                sourceTrackId = row.sourceTrackId,
                title = row.title.ifBlank { "Download" },
                artist = row.artist,
                state = state,
                progress = if (state == DownloadState.COMPLETED) 1f else 0f,
                downloadedBytes = row.downloadedBytes,
                totalBytes = row.totalBytes,
                currentFile = row.localPath,
                error = if (interrupted) "Interrupted — the app was closed during this download" else row.errorMessage
            )
            jobs[row.downloadId] = progress
            notify(progress)
            if (interrupted) {
                try {
                    downloadRepository.updateStatus(row.downloadId, "paused", errorMessage = progress.error)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun updateProgress(
        jobId: Long,
        state: DownloadState,
        progress: Float = 0f,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        speedBytesPerSecond: Float = 0f,
        etaSeconds: Long = 0L,
        currentFile: String? = null,
        error: String? = null
    ): DownloadProgress {
        val current = jobs[jobId] ?: DownloadProgress(
            jobId = jobId,
            source = "unknown",
            sourceTrackId = "",
            title = "",
            artist = "",
            state = state
        )
        val updated = current.copy(
            state = state,
            progress = progress.coerceIn(0f, 1f),
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speedBytesPerSecond = speedBytesPerSecond,
            etaSeconds = etaSeconds,
            currentFile = currentFile ?: current.currentFile,
            error = error
        )
        jobs[jobId] = updated
        notifyThrottled(jobId, updated)
        return updated
    }

    private fun updateState(jobId: Long, state: DownloadState) {
        val current = jobs[jobId] ?: return
        val updated = current.copy(state = state, error = if (state == DownloadState.RESOLVING) null else current.error)
        jobs[jobId] = updated
        notify(updated)
        try {
            downloadRepository.updateStatus(jobId, state.name.lowercase())
        } catch (_: Exception) {
        }
    }

    private fun fail(jobId: Long, message: String) {
        val current = jobs[jobId] ?: return
        val updated = current.copy(state = DownloadState.FAILED, error = message)
        jobs[jobId] = updated
        notify(updated)
        try {
            downloadRepository.updateStatus(jobId, "failed", errorMessage = message)
        } catch (_: Exception) {
        }
    }

    fun getProgress(jobId: Long): DownloadProgress? = jobs[jobId]

    fun getAll(): List<DownloadProgress> = jobs.values.sortedBy { it.jobId }

    fun cancel(jobId: Long): DownloadProgress? {
        val current = jobs[jobId] ?: return null
        if (isTerminal(current.state)) return current
        cancelled.add(jobId)
        val cancelledProgress = current.copy(state = DownloadState.CANCELLED, speedBytesPerSecond = 0f, etaSeconds = 0L)
        jobs[jobId] = cancelledProgress
        notify(cancelledProgress)
        return cancelledProgress
    }

    /** Drops a finished/failed job from the Downloads list and persistence. */
    fun remove(jobId: Long) {
        jobs.remove(jobId)
        cancelled.remove(jobId)
        abortReasons.remove(jobId)
        lastNotifyMs.remove(jobId)
        lastBytePersistMs.remove(jobId)
        executor.execute {
            try {
                downloadRepository.delete(jobId)
            } catch (_: Exception) {
            }
        }
    }

    /** Terminal states are safe to show as finished; in-flight statuses are not. */
    private fun terminalStateFor(status: String): DownloadState? = when (status) {
        "completed" -> DownloadState.COMPLETED
        "failed" -> DownloadState.FAILED
        "cancelled" -> DownloadState.CANCELLED
        "paused" -> DownloadState.PAUSED
        else -> null
    }

    private fun isTerminal(state: DownloadState): Boolean =
        state == DownloadState.COMPLETED || state == DownloadState.FAILED || state == DownloadState.CANCELLED

    private fun deleteFileQuietly(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    private fun notifyThrottled(jobId: Long, progress: DownloadProgress) {
        val now = System.currentTimeMillis()
        val last = lastNotifyMs[jobId] ?: 0L
        if (now - last >= 250L) {
            lastNotifyMs[jobId] = now
            notify(progress)
        }
    }

    private fun notify(progress: DownloadProgress) {
        listeners.forEach { listener -> listener(progress) }
    }
}
