package com.aurora.app.storage

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class AudioFileMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mimeType: String?,
    val fileSizeBytes: Long = 0L,
    val artworkPath: String? = null
)

data class ImportedAudioFile(
    val sourceUri: Uri,
    val localUri: Uri,
    val localFile: File,
    val metadata: AudioFileMetadata
)

class AuroraStorageManager(private val context: Context) {
    private val rootDir: File by lazy {
        File(context.filesDir, "Music/Aurora").apply { mkdirs() }
    }

    val tracksDir: File by lazy {
        File(rootDir, "tracks").apply { mkdirs() }
    }

    val artworkDir: File by lazy {
        File(rootDir, "artwork").apply { mkdirs() }
    }

    fun importAudioFile(sourceUri: Uri): ImportedAudioFile? {
        val sourceName = resolveDisplayName(sourceUri) ?: "track_${UUID.randomUUID()}"
        val ext = when {
            sourceName.contains(".") -> ".${sourceName.substringAfterLast('.', "")}".lowercase()
            else -> ".mp3"
        }
        val destination = File(tracksDir, "${UUID.randomUUID()}$ext")
        try {
            val input = context.contentResolver.openInputStream(sourceUri) ?: return null
            val output = FileOutputStream(destination)
            input.use { inStream ->
                output.use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            val metadata = readAudioMetadata(destination)
            return ImportedAudioFile(
                sourceUri = sourceUri,
                localUri = Uri.fromFile(destination),
                localFile = destination,
                metadata = metadata
            )
        } catch (_: Exception) {
            return null
        }
    }

    fun calculateContentHash(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun calculateContentHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun removeTrack(trackId: String): Boolean {
        val file = File(tracksDir, trackId)
        return if (file.exists()) file.delete() else false
    }

    fun localCopyExists(trackId: String): Boolean {
        return File(tracksDir, trackId).exists()
    }

    fun getLocalUri(trackId: String): Uri? {
        val file = File(tracksDir, trackId)
        return if (file.exists()) Uri.fromFile(file) else null
    }

    fun storeArtwork(trackId: String, sourceUri: Uri): Uri? {
        return try {
            val destination = File(artworkDir, "$trackId.jpg")
            val input = context.contentResolver.openInputStream(sourceUri) ?: return null
            val output = FileOutputStream(destination)
            input.use { inStream ->
                output.use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            Uri.fromFile(destination)
        } catch (_: Exception) {
            null
        }
    }

    fun readAudioMetadata(file: File): AudioFileMetadata {
        val retriever = MediaMetadataRetriever()
        var artworkPath: String? = null
        return try {
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown Album"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val picture = retriever.embeddedPicture
            if (picture != null && picture.isNotEmpty()) {
                val destination = File(artworkDir, "${file.nameWithoutExtension}_${UUID.randomUUID()}.jpg")
                destination.writeBytes(picture)
                artworkPath = destination.absolutePath
            }
            AudioFileMetadata(
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                mimeType = mimeType,
                fileSizeBytes = file.length(),
                artworkPath = artworkPath
            )
        } catch (_: Exception) {
            AudioFileMetadata(
                title = file.nameWithoutExtension,
                artist = "Unknown Artist",
                album = "Unknown Album",
                durationMs = 0L,
                mimeType = null,
                fileSizeBytes = file.length(),
                artworkPath = null
            )
        } finally {
            retriever.release()
        }
    }

    fun readAudioMetadata(uri: Uri): AudioFileMetadata? {
        val path = uri.path ?: return null
        val file = File(path)
        return if (file.exists()) readAudioMetadata(file) else null
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
        return name ?: uri.lastPathSegment
    }
}
