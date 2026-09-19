package com.aurora.app.source

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aurora.app.database.entities.TrackEntity
import com.aurora.app.database.repositories.LibraryRepository
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * [MusicSource] over the device's local library.
 *
 * It exposes two kinds of local tracks through one interface:
 *  - device MediaStore tracks (`content://…`), which can be copied into
 *    Aurora-managed storage (a real, offline download), and
 *  - tracks already stored inside Aurora storage (`file://…Music/Aurora/tracks`),
 *    which are reported as already downloaded.
 *
 * Nothing here touches the network.
 */
class LocalSource(
    private val libraryRepository: LibraryRepository,
    private val localFileResolver: (String?) -> Uri? = { path ->
        if (path.isNullOrBlank()) null else File(path).takeIf { it.exists() }?.let(Uri::fromFile)
    },
    private val context: Context? = null
) : MusicSource {
    override val sourceId: String = "aurora_local"
    override val capabilities: Set<SourceCapability> = setOf(
        SourceCapability.LOCAL,
        SourceCapability.STREAM,
        SourceCapability.DOWNLOAD
    )

    override fun search(query: String): List<SourceMetadata> {
        val normalized = query.trim()
        return libraryRepository.getAllTracks()
            .filter { track ->
                normalized.isEmpty() || track.title.contains(normalized, ignoreCase = true) ||
                    track.artist.contains(normalized, ignoreCase = true) ||
                    track.album.contains(normalized, ignoreCase = true)
            }
            .map { it.toSourceMetadata() }
    }

    override fun getTrack(id: SourceTrackId): SourceMetadata? {
        if (id.source != sourceId) return null
        val trackId = id.value.toLongOrNull() ?: return null
        val track = libraryRepository.getTrack(trackId) ?: return null
        return track.toSourceMetadata()
    }

    override fun stream(track: SourceMetadata): StreamResult {
        val resolved = localFileResolver(track.localPath) ?: track.localUri
        return if (resolved != null && canOpen(resolved)) {
            StreamResult(uri = resolved, metadata = track, isPreview = false)
        } else {
            StreamResult(uri = null, metadata = track, error = "Local file is unavailable")
        }
    }

    override fun checkDownloadAvailability(track: SourceMetadata): DownloadCapability {
        val auroraFile = track.localPath?.let { File(it) }?.takeIf { it.exists() }
        if (auroraFile != null) {
            return DownloadCapability(DownloadAvailability.AVAILABLE, "Already stored in Aurora local storage")
        }
        val uri = track.localUri
        return if (uri != null && canOpen(uri)) {
            DownloadCapability(DownloadAvailability.AVAILABLE, "Copy this device track into Aurora storage")
        } else {
            DownloadCapability(DownloadAvailability.UNAVAILABLE, "Track is not readable from local storage")
        }
    }

    override fun download(track: SourceMetadata, targetDir: File): DownloadResult =
        downloadWithProgress(track, targetDir, onProgress = { _, _ -> }, isCancelled = { false })

    override fun downloadWithProgress(
        track: SourceMetadata,
        targetDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
    ): DownloadResult {
        val auroraFile = track.localPath?.let { File(it) }?.takeIf { it.exists() }
        if (auroraFile != null && sameDirectory(auroraFile.parentFile, targetDir)) {
            // Already inside Aurora-managed storage: nothing to transfer.
            onProgress(auroraFile.length(), auroraFile.length())
            return DownloadResult(success = true, localUri = Uri.fromFile(auroraFile), localPath = auroraFile.absolutePath)
        }

        val sourceUri = auroraFile?.let { Uri.fromFile(it) } ?: track.localUri
            ?: return DownloadResult(success = false, error = "Local file is not available for transfer")
        val sourceName = displayName(sourceUri) ?: auroraFile?.name
        val extension = extensionFor(sourceName, sourceUri)
        val safeName = sanitizeFilename(track.title.ifBlank { sourceName?.substringBeforeLast('.') ?: "aurora_track" }) + extension
        val target = File(targetDir, safeName)
        val temp = File(targetDir, "$safeName.part")

        return try {
            targetDir.mkdirs()
            temp.delete()
            val total = sourceSize(sourceUri, auroraFile)
            val input = openInput(sourceUri)
                ?: return DownloadResult(success = false, error = "Could not open local source")
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        if (isCancelled()) {
                            temp.delete()
                            return DownloadResult(success = false, error = "Download cancelled")
                        }
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }
            if (isCancelled()) {
                temp.delete()
                return DownloadResult(success = false, error = "Download cancelled")
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            onProgress(target.length(), target.length())
            DownloadResult(success = true, localUri = Uri.fromFile(target), localPath = target.absolutePath)
        } catch (e: Exception) {
            temp.delete()
            DownloadResult(success = false, error = e.message ?: "Failed to transfer local file")
        }
    }

    private fun TrackEntity.toSourceMetadata(): SourceMetadata {
        val isAurora = isAuroraImported || sourceType == "aurora_imported"
        val auroraFile = localPath?.let { File(it) }?.takeIf { it.exists() }
        val resolved = auroraFile?.let(Uri::fromFile)
            ?: uri.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val trackCapabilities = linkedSetOf(SourceCapability.LOCAL, SourceCapability.STREAM).apply {
            // Device tracks can be copied into Aurora storage; Aurora tracks are already local.
            if (!isAurora && resolved != null && (resolved.scheme == "content" || resolved.scheme == "file")) {
                add(SourceCapability.DOWNLOAD)
            }
        }
        return SourceMetadata(
            trackId = SourceTrackId(sourceId, id.toString()),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkUri = artworkPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { f -> f.exists() }?.let(Uri::fromFile),
            localUri = resolved,
            localPath = auroraFile?.absolutePath,
            sourceCapabilities = trackCapabilities
        )
    }

    private fun canOpen(uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "content", "android.resource" -> {
            val resolver = context?.contentResolver
            if (resolver == null) true else runCatching { resolver.openInputStream(uri)?.use { true } }.getOrNull() == true
        }
        else -> uri.path?.let { File(it).exists() } == true
    }

    private fun openInput(uri: Uri): InputStream? = try {
        when (uri.scheme?.lowercase()) {
            "content", "android.resource" -> context?.contentResolver?.openInputStream(uri)
            else -> uri.path?.let { File(it).takeIf { f -> f.exists() }?.inputStream() }
        }
    } catch (_: Exception) {
        null
    }

    private fun sourceSize(uri: Uri, file: File?): Long {
        file?.let { return it.length() }
        if (uri.scheme == "content") {
            context?.contentResolver?.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return 0L
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context?.contentResolver?.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun extensionFor(name: String?, uri: Uri): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext in AUDIO_EXTENSIONS) return ".$ext"
        // The display name often has no usable extension (e.g. Telegram audio),
        // so fall back to the provider's MIME type before guessing.
        val mime = runCatching { context?.contentResolver?.getType(uri) }.getOrNull()
        val fromMime = when (mime?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/aac-adts", "audio/vnd.dlna.adts" -> "m4a"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg", "application/ogg", "audio/opus", "audio/vorbis" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/wma", "audio/x-ms-wma" -> "wma"
            else -> null
        }
        return when {
            fromMime != null -> ".$fromMime"
            ext.isNotBlank() -> ".$ext"
            else -> ".mp3"
        }
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma")
    }

    private fun sanitizeFilename(name: String): String {
        val cleaned = name.replace(Regex("[\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), "_").trim('_')
        return if (cleaned.isBlank()) "aurora_track" else cleaned
    }

    private fun sameDirectory(a: File?, b: File): Boolean {
        if (a == null) return false
        return runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(false)
    }
}
