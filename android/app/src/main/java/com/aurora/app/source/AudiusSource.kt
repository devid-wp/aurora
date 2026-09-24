package com.aurora.app.source

import android.net.Uri
import com.aurora.app.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Audius integration built against the official Audius REST API.
 */
internal const val AUDIUS_API_BASE = "https://api.audius.co/v1"

class AudiusSource(
    private val apiKeyProvider: () -> String = { BuildConfig.AUDIUS_API_KEY },
    private val httpClient: SoundCloudHttpClient = DefaultSoundCloudHttpClient()
) : MusicSource {

    override val sourceId: String = "audius"
    override val capabilities: Set<SourceCapability> = setOf(
        SourceCapability.STREAM,
        SourceCapability.DOWNLOAD
    )

    private fun headers(): Map<String, String> {
        val key = apiKeyProvider().trim()
        return if (key.isNotBlank()) mapOf("x-api-key" to key) else emptyMap()
    }

    private fun apiUrl(path: String, queryParams: Map<String, String> = emptyMap()): String {
        val base = "$AUDIUS_API_BASE$path"
        if (queryParams.isEmpty()) return base
        val joined = queryParams.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$base?$joined"
    }

    override fun search(query: String): List<SourceMetadata> {
        return when (val outcome = searchDetailed(query)) {
            is SourceSearchResult.Success -> outcome.results
            else -> emptyList()
        }
    }

    override fun searchDetailed(query: String): SourceSearchResult {
        if (query.isBlank()) return SourceSearchResult.Empty()
        val url = apiUrl("/tracks/search", linkedMapOf(
            "query" to query.trim(),
            "limit" to "20"
        ))
        val response = try {
            httpClient.get(url, headers())
        } catch (e: Exception) {
            return SourceSearchResult.Error(e.message ?: "Search failed")
        }
        return when {
            response.statusCode == -1 ->
                SourceSearchResult.Error("No network connection or Audius is unreachable.")
            response.statusCode !in 200..299 ->
                SourceSearchResult.Error("Audius returned HTTP ${response.statusCode}.", response.statusCode)
            response.body.isNullOrBlank() ->
                SourceSearchResult.Error("Audius returned an empty response.")
            else -> {
                val tracks = try {
                    if (!hasJsonKey(response.body!!, "data")) {
                        return SourceSearchResult.Error("Audius returned a response that could not be read.")
                    }
                    extractTrackObjects(response.body!!).mapNotNull { metadataForTrackObject(it) }
                } catch (_: Exception) {
                    return SourceSearchResult.Error("Audius returned a response that could not be read.")
                }
                if (tracks.isEmpty()) {
                    SourceSearchResult.Empty("No Audius results for \"${query.trim()}\"")
                } else {
                    SourceSearchResult.Success(tracks)
                }
            }
        }
    }

    override fun getTrack(id: SourceTrackId): SourceMetadata? {
        if (id.source != sourceId || id.value.isBlank()) return null
        val raw = try {
            val response = httpClient.get(apiUrl("/tracks/${id.value.trim()}"), headers())
            if (response.statusCode !in 200..299) return null
            response.body ?: return null
        } catch (_: Exception) {
            return null
        }
        return try {
            extractSingleTrackObject(raw)?.let { metadataForTrackObject(it) }
        } catch (_: Exception) {
            null
        }
    }

    override fun stream(track: SourceMetadata): StreamResult {
        if (track.sourceCapabilities.contains(SourceCapability.BLOCKED)) {
            return StreamResult(uri = null, metadata = track, isPreview = false, error = "This Audius track is unavailable for streaming")
        }
        val alreadyResolved = track.localUri
        if (alreadyResolved != null && (alreadyResolved.scheme == "http" || alreadyResolved.scheme == "https")) {
            return StreamResult(uri = alreadyResolved, metadata = track.copy(localUri = alreadyResolved), isPreview = false)
        }
        val resolved = resolveStreamUrl(track) ?: return StreamResult(
            uri = null,
            metadata = track,
            isPreview = false,
            error = "Audius stream is unavailable for this track"
        )
        return StreamResult(uri = resolved, metadata = track.copy(localUri = resolved), isPreview = false)
    }

    private fun resolveStreamUrl(track: SourceMetadata): Uri? {
        val url = apiUrl("/tracks/${track.trackId.value}/stream", linkedMapOf("no_redirect" to "true"))
        val raw = try {
            val response = httpClient.get(url, headers())
            if (response.statusCode !in 200..299) return null
            response.body ?: return null
        } catch (_: Exception) {
            return null
        }
        return try {
            safeUri(extractJsonString(raw, "data"))
        } catch (_: Exception) {
            null
        }
    }

    override fun checkDownloadAvailability(track: SourceMetadata): DownloadCapability = when {
        track.sourceCapabilities.contains(SourceCapability.BLOCKED) ->
            DownloadCapability(DownloadAvailability.BLOCKED, "This Audius track is unavailable")
        track.sourceCapabilities.contains(SourceCapability.DOWNLOAD) ->
            DownloadCapability(DownloadAvailability.AVAILABLE, "Audius explicitly permits downloading this track")
        else ->
            DownloadCapability(DownloadAvailability.UNAVAILABLE, "The artist has not enabled downloads for this Audius track")
    }

    /**
     * Persistent download through the official `GET /tracks/{id}/download`
     * endpoint, which redirects to the file Audius is willing to serve. This is
     * only reached when the track explicitly carries [SourceCapability.DOWNLOAD],
     * and the live track is re-checked first so a stale capability can never
     * authorise a download. No URL is ever guessed from the stream URL.
     */
    override fun download(track: SourceMetadata, targetDir: File): DownloadResult {
        val verified = verifyDownloadPermitted(track)
            ?: return DownloadResult(success = false, error = "Audius does not permit downloading this track")
        val response = try {
            httpClient.getBytes(downloadEndpoint(verified.trackId.value), headers())
        } catch (e: Exception) {
            return DownloadResult(success = false, error = e.message ?: "Audius download failed")
        }
        if (response.statusCode !in 200..299) {
            return DownloadResult(success = false, error = "Audius download failed (HTTP ${response.statusCode})")
        }
        val bytes = response.bytes
        if (bytes == null || bytes.isEmpty()) {
            return DownloadResult(success = false, error = "Audius returned no download data")
        }
        val extension = audioExtensionForContentType(response.contentType)
        val target = File(targetDir, sanitizeAudioFilename(verified.title, "audius_track") + extension)
        return try {
            targetDir.mkdirs()
            target.writeBytes(bytes)
            DownloadResult(success = true, localUri = Uri.fromFile(target), localPath = target.absolutePath)
        } catch (e: Exception) {
            DownloadResult(success = false, error = e.message ?: "Audius download failed")
        }
    }

    /**
     * Streaming download of the official download file with byte progress and
     * cooperative cancellation, mirroring the SoundCloud pipeline. Reads the
     * response incrementally so a large original upload never has to be held in
     * memory, writes to a temp file and only renames it once complete.
     */
    override fun downloadWithProgress(
        track: SourceMetadata,
        targetDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
    ): DownloadResult {
        val verified = verifyDownloadPermitted(track)
            ?: return DownloadResult(success = false, error = "Audius does not permit downloading this track")
        val safeBase = sanitizeAudioFilename(verified.title, "audius_track")
        var connection: HttpURLConnection? = null
        var temp: File? = null
        return try {
            connection = URL(downloadEndpoint(verified.trackId.value)).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.doInput = true
            connection.setRequestProperty("Accept", "application/octet-stream")
            headers().forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                return DownloadResult(success = false, error = "Audius download failed (HTTP $code)")
            }
            val contentType = connection.getHeaderField("Content-Type")
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: 0L
            val extension = audioExtensionForContentType(contentType)
            targetDir.mkdirs()
            val target = File(targetDir, "$safeBase$extension")
            temp = File(targetDir, "$safeBase$extension.part").apply { delete() }
            connection.inputStream.use { input ->
                FileOutputStream(temp!!).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        if (isCancelled()) {
                            temp!!.delete()
                            connection.disconnect()
                            return DownloadResult(success = false, error = "Download cancelled")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }
            connection.disconnect()
            if (isCancelled()) {
                temp!!.delete()
                return DownloadResult(success = false, error = "Download cancelled")
            }
            if (temp!!.length() <= 0L) {
                temp!!.delete()
                return DownloadResult(success = false, error = "Audius returned no download data")
            }
            if (target.exists()) target.delete()
            if (!temp!!.renameTo(target)) {
                temp!!.copyTo(target, overwrite = true)
                temp!!.delete()
            }
            onProgress(target.length(), target.length())
            DownloadResult(success = true, localUri = Uri.fromFile(target), localPath = target.absolutePath)
        } catch (e: Exception) {
            temp?.delete()
            DownloadResult(success = false, error = e.message ?: "Audius download failed")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Re-fetches the live track and returns it only when the source still
     * explicitly permits a download. Null means "do not download".
     */
    private fun verifyDownloadPermitted(track: SourceMetadata): SourceMetadata? {
        if (!track.sourceCapabilities.contains(SourceCapability.DOWNLOAD)) return null
        val fresh = getTrack(track.trackId) ?: return null
        return fresh.takeIf { it.sourceCapabilities.contains(SourceCapability.DOWNLOAD) }
    }

    private fun downloadEndpoint(trackId: String): String = apiUrl("/tracks/$trackId/download")

    private fun extractTrackObjects(rawJson: String): List<String> {
        val array = extractDataValue(rawJson)?.trim() ?: return emptyList()
        if (!array.startsWith("[")) return emptyList()
        return splitTopLevelObjects(array)
    }

    private fun extractSingleTrackObject(rawJson: String): String? {
        val value = extractDataValue(rawJson)?.trim() ?: return null
        return if (value.startsWith("{")) value else null
    }

    private fun extractDataValue(rawJson: String): String? {
        val match = Regex("\"data\"\\s*:\\s*").find(rawJson) ?: return null
        val start = match.range.last + 1
        if (start >= rawJson.length) return null
        val opening = rawJson[start]
        if (opening != '{' && opening != '[') return null
        val closing = if (opening == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until rawJson.length) {
            val ch = rawJson[i]
            if (escape) {
                escape = false
                continue
            }
            if (inString) {
                if (ch == '\\') escape = true else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                opening -> depth++
                closing -> {
                    depth--
                    if (depth == 0) return rawJson.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun splitTopLevelObjects(arrayJson: String): List<String> {
        val trimmed = arrayJson.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val content = trimmed.substring(1, trimmed.length - 1).trim()
        if (content.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        var startIndex = -1
        for ((index, ch) in content.withIndex()) {
            if (escape) {
                escape = false
                continue
            }
            if (inString) {
                if (ch == '\\') escape = true else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) startIndex = index
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && startIndex >= 0) {
                        result.add(content.substring(startIndex, index + 1))
                        startIndex = -1
                    }
                }
            }
        }
        return result
    }

    private fun metadataForTrackObject(rawJson: String): SourceMetadata? {
        val id = extractJsonString(rawJson, "id")
            .ifBlank { extractJsonLong(rawJson, "track_id").takeIf { it > 0L }?.toString().orEmpty() }
        if (id.isBlank()) return null

        val title = extractJsonString(rawJson, "title").ifBlank { "Unknown track" }
        val userObject = extractJsonObject(rawJson, "user")
        val artist = when {
            userObject.isNotBlank() -> extractJsonString(userObject, "name")
                .ifBlank { extractJsonString(userObject, "handle") }
            else -> ""
        }.ifBlank { "Unknown artist" }

        val album = extractJsonString(rawJson, "genre").ifBlank { "Audius" }
        val durationMs = extractJsonLong(rawJson, "duration").coerceAtLeast(0L) * 1000L
        val artworkObject = extractJsonObject(rawJson, "artwork")
        
        // Priority: 1000x1000 -> 480x480 -> 150x150
        val artUrl = extractJsonString(artworkObject, "1000x1000")
            .ifBlank { extractJsonString(artworkObject, "480x480") }
            .ifBlank { extractJsonString(artworkObject, "150x150") }
        val art = resolveArtworkUrl(artUrl)

        val blocked = extractJsonBoolean(rawJson, "is_delete") ||
            (hasJsonKey(rawJson, "is_available") && !extractJsonBoolean(rawJson, "is_available")) ||
            extractJsonBoolean(rawJson, "is_unlisted") ||
            (hasJsonKey(rawJson, "is_streamable") && !extractJsonBoolean(rawJson, "is_streamable")) ||
            extractJsonBoolean(rawJson, "is_stream_gated") ||
            streamAccessDenied(rawJson)

        val capabilities = linkedSetOf<SourceCapability>()
        if (blocked) {
            capabilities += SourceCapability.BLOCKED
        } else {
            capabilities += SourceCapability.STREAM
            // Download is offered only when the API explicitly grants it. The
            // artist flag (`is_downloadable`), the current user's access
            // (`access.download`) and the absence of download gating are all
            // required; a missing/unknown flag means "not downloadable".
            if (downloadPermitted(rawJson)) capabilities += SourceCapability.DOWNLOAD
        }

        return SourceMetadata(
            trackId = SourceTrackId(sourceId, id),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkUri = art,
            localUri = null,
            sourceCapabilities = capabilities
        )
    }

    private fun streamAccessDenied(rawJson: String): Boolean {
        val access = extractJsonObject(rawJson, "access")
        return hasJsonKey(access, "stream") && !extractJsonBoolean(access, "stream")
    }

    /**
     * True only when the track object explicitly permits a persistent download:
     * the artist enabled downloads (`is_downloadable`), the current user's
     * `access.download` is true, and the track is not download-gated. Aurora
     * never infers download permission from the presence of a stream URL.
     */
    private fun downloadPermitted(rawJson: String): Boolean {
        if (!extractJsonBoolean(rawJson, "is_downloadable")) return false
        if (extractJsonBoolean(rawJson, "is_download_gated")) return false
        val access = extractJsonObject(rawJson, "access")
        return hasJsonKey(access, "download") && extractJsonBoolean(access, "download")
    }

    private fun hasJsonKey(rawJson: String, key: String): Boolean =
        Regex("\"${Regex.escape(key)}\"\\s*:").containsMatchIn(rawJson)

    private fun safeUri(rawUrl: String?): Uri? = try {
        rawUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun resolveArtworkUrl(rawUrl: String?): Uri? {
        val candidate = rawUrl?.takeIf { it.isNotBlank() } ?: return null
        val normalized = if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            candidate
        } else {
            "https:$candidate"
        }
        return safeUri(normalized)
    }
}
