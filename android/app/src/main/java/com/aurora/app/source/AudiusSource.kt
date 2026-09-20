package com.aurora.app.source

import android.net.Uri
import com.aurora.app.BuildConfig
import java.io.File
import java.net.URLEncoder

/**
 * Audius integration built against the official Audius REST API.
 *
 * Reference material (the only endpoints and shapes used here):
 *  - Docs: https://docs.audius.co/
 *  - API base: https://api.audius.co/v1 (stable entry; the old
 *    discoveryprovider host is deprecated, so no host discovery is needed)
 *  - Machine-readable contract: https://api.audius.co/v1/swagger.yaml
 *
 * Endpoints used:
 *   GET {api}/v1/tracks/search?query=..&limit=..   search (`{"data":[...]}`)
 *   GET {api}/v1/tracks/{track_id}                  track lookup (`{"data":{...}}`)
 *   GET {api}/v1/tracks/{track_id}/stream?no_redirect=true
 *     stream URL as JSON (`{"data":"https://..."}`); the signed URL expires,
 *     so it is resolved fresh on every play and never persisted.
 *
 * Authentication is an `x-api-key` header when a developer key is bundled
 * (BuildConfig, empty by default). Public catalog reads work without user
 * sign-in, so Audius is an online streaming source: STREAM capability only,
 * downloads are never offered.
 */
internal const val AUDIUS_API_BASE = "https://api.audius.co/v1"

class AudiusSource(
    private val apiKeyProvider: () -> String = { BuildConfig.AUDIUS_API_KEY },
    private val httpClient: SoundCloudHttpClient = DefaultSoundCloudHttpClient()
) : MusicSource {

    override val sourceId: String = "audius"
    override val capabilities: Set<SourceCapability> = setOf(SourceCapability.STREAM)

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

    // ── MusicSource: search ───────────────────────────────────────────────

    override fun search(query: String): List<SourceMetadata> {
        return when (val outcome = searchDetailed(query)) {
            is SourceSearchResult.Success -> outcome.results
            else -> emptyList()
        }
    }

    /**
     * Detailed search that surfaces the real outcome (network failure, HTTP
     * error, malformed response, empty, or success) so the UI never silently
     * shows an empty list when something actually failed.
     */
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

    // ── MusicSource: track lookup ─────────────────────────────────────────

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

    // ── MusicSource: streaming ────────────────────────────────────────────

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

    /**
     * Resolves a fresh signed stream URL via `no_redirect=true` (JSON
     * `{"data":"https://..."}`). The URL carries an expiring signature, so it
     * is never cached or persisted — every play resolves it again.
     */
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

    // ── MusicSource: downloads (never offered for Audius) ─────────────────

    override fun checkDownloadAvailability(track: SourceMetadata): DownloadCapability =
        DownloadCapability(DownloadAvailability.UNAVAILABLE, "Audius tracks stream online only — downloads are not offered")

    override fun download(track: SourceMetadata, targetDir: File): DownloadResult =
        DownloadResult(success = false, error = "Audius tracks stream online only — downloads are not offered")

    // ── Response parsing ──────────────────────────────────────────────────

    /** Extracts track object texts from a `{"data":[...]}` collection response. */
    private fun extractTrackObjects(rawJson: String): List<String> {
        val array = extractDataValue(rawJson)?.trim() ?: return emptyList()
        if (!array.startsWith("[")) return emptyList()
        return splitTopLevelObjects(array)
    }

    /** Extracts the single track object from a `{"data":{...}}` response. */
    private fun extractSingleTrackObject(rawJson: String): String? {
        val value = extractDataValue(rawJson)?.trim() ?: return null
        return if (value.startsWith("{")) value else null
    }

    /** Returns the raw JSON value of the top-level `data` key (object or array). */
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

    /**
     * Maps an Audius track object to [SourceMetadata].
     *
     * Availability is read from the real flags: deleted, unlisted,
     * unavailable, non-streamable, or stream-gated tracks are BLOCKED
     * (metadata only); everything else streams.
     */
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
        // Audius reports duration in whole seconds.
        val durationMs = extractJsonLong(rawJson, "duration").coerceAtLeast(0L) * 1000L
        val artworkObject = extractJsonObject(rawJson, "artwork")
        val art = resolveArtworkUrl(
            extractJsonString(artworkObject, "480x480")
                .ifBlank { extractJsonString(artworkObject, "150x150") }
        )

        val blocked = extractJsonBoolean(rawJson, "is_delete") ||
            (hasJsonKey(rawJson, "is_available") && !extractJsonBoolean(rawJson, "is_available")) ||
            extractJsonBoolean(rawJson, "is_unlisted") ||
            (hasJsonKey(rawJson, "is_streamable") && !extractJsonBoolean(rawJson, "is_streamable")) ||
            extractJsonBoolean(rawJson, "is_stream_gated") ||
            streamAccessDenied(rawJson)

        val capabilities = if (blocked) {
            setOf(SourceCapability.BLOCKED)
        } else {
            setOf(SourceCapability.STREAM)
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
