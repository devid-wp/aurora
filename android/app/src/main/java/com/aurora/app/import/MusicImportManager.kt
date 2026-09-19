package com.aurora.app.import

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.AlbumEntity
import com.aurora.app.database.entities.ArtistEntity
import com.aurora.app.database.entities.TrackEntity
import com.aurora.app.database.repositories.TrackRepository
import com.aurora.app.storage.AuroraStorageManager
import java.io.File
import java.security.MessageDigest

open class MusicImportManager(
    private val context: Context,
    private val database: AuroraDatabase = AuroraDatabase.getInstance(context.applicationContext),
    private val storage: AuroraStorageManager = AuroraStorageManager(context.applicationContext)
) {
    private val trackRepository = TrackRepository(database)

    protected open fun databaseIsAvailable(): Boolean {
        return try {
            database.query("SELECT 1", null).use { cursor -> cursor.moveToFirst() }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun importFromUri(sourceUri: Uri): ImportResult {
        return try {
            if (!databaseIsAvailable()) {
                return ImportResult.DatabaseFailed("Database is closed or unavailable", sourceUri)
            }
            if (!canReadUri(sourceUri)) {
                return ImportResult.PermissionDenied(sourceUri)
            }
            if (!isAudioUri(sourceUri)) {
                return ImportResult.InvalidFile("Selected file is not an audio file", sourceUri)
            }

            val contentHash = storage.calculateContentHash(sourceUri)
            if (contentHash == null) {
                return ImportResult.CopyFailed("Could not read audio content for duplicate detection", sourceUri)
            }

            val existing = trackRepository.getByContentHash(contentHash)
            if (existing != null) {
                return ImportResult.Duplicate(existing.id, existing.title, sourceUri)
            }

            val copied = storage.importAudioFile(sourceUri)
                ?: return ImportResult.CopyFailed("Failed to copy file into Aurora storage", sourceUri)

            val metadata = copied.metadata
            var insertedTrackId: Long? = null
            var localFile: File? = copied.localFile

            try {
                val artistName = metadata.artist.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                val albumName = metadata.album.takeIf { it.isNotBlank() } ?: "Unknown Album"
                val title = metadata.title.takeIf { it.isNotBlank() } ?: copied.localFile.nameWithoutExtension

                val artistId = database.artistDao().getByName(artistName)?.artistId
                    ?: database.artistDao().upsert(ArtistEntity(name = artistName, sortName = artistName))
                val albumId = database.albumDao().getByTitle(albumName)?.albumId
                    ?: database.albumDao().upsert(
                        AlbumEntity(
                            title = albumName,
                            artistId = artistId,
                            artworkUri = metadata.artworkPath
                        )
                    )

                val durableId = when {
                    contentHash.isNotBlank() -> generateDurableTrackId(contentHash)
                    else -> System.currentTimeMillis()
                }

                val trackEntity = TrackEntity(
                    id = durableId,
                    title = title,
                    artist = artistName,
                    album = albumName,
                    durationMs = metadata.durationMs.coerceAtLeast(0L),
                    uri = copied.localUri.toString(),
                    mediaStoreId = null,
                    artistId = artistId,
                    albumId = albumId,
                    sourceType = "aurora_imported",
                    isAuroraImported = true,
                    localPath = copied.localFile.absolutePath,
                    artworkPath = metadata.artworkPath,
                    contentHash = contentHash
                )

                val trackId = database.trackDao().upsert(trackEntity)
                insertedTrackId = trackId
                if (trackId <= 0L) {
                    throw IllegalStateException("Track insert returned invalid row id")
                }

                ImportResult.Success(trackId = trackId, title = title, source = sourceUri)
            } catch (dbFailure: Exception) {
                insertedTrackId?.let { database.trackDao().deleteById(it) }
                localFile?.let { file ->
                    kotlin.runCatching { storage.removeTrack(file.name) }
                }
                ImportResult.DatabaseFailed("Database insert failed: ${dbFailure.message}", sourceUri)
            }
        } catch (e: Exception) {
            ImportResult.DatabaseFailed("Import failed: ${e.message ?: "unknown error"}", sourceUri)
        }
    }

    fun importFromUris(sourceUris: List<Uri>): List<ImportResult> {
        return sourceUris.map { uri -> importFromUri(uri) }
    }

    internal fun generateDurableTrackId(contentHash: String): Long {
        if (contentHash.isBlank()) {
            return System.currentTimeMillis()
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(contentHash.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (digest[index].toLong() and 0xFFL)
        }

        val normalized = value and Long.MAX_VALUE
        return if (normalized == 0L) 1L else normalized
    }

    private fun canReadUri(uri: Uri): Boolean {
        return try {
            val resolver = context.contentResolver
            val stream = resolver.openInputStream(uri)
            stream?.close()
            stream != null
        } catch (_: Exception) {
            false
        }
    }

    private fun isAudioUri(uri: Uri): Boolean {
        val resolver: ContentResolver = context.contentResolver
        val type = resolver.getType(uri)
        if (type != null && type.startsWith("audio/")) return true

        val filename = uri.lastPathSegment ?: ""
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma") ||
            MimeTypeMap.getFileExtensionFromUrl(filename)?.lowercase() in setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma")
    }

}
