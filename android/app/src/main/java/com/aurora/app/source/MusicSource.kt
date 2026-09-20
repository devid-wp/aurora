package com.aurora.app.source

import android.net.Uri
import java.io.File

enum class SourceCapability {
    LOCAL,
    STREAM,
    DOWNLOAD,
    PREVIEW,
    BLOCKED
}

enum class DownloadAvailability {
    AVAILABLE,
    UNAVAILABLE,
    PREVIEW_ONLY,
    BLOCKED
}

data class SourceTrackId(
    val source: String,
    val value: String
)

data class SourceMetadata(
    val trackId: SourceTrackId,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long = 0L,
    val artworkUri: Uri? = null,
    val localUri: Uri? = null,
    val localPath: String? = null,
    val sourceCapabilities: Set<SourceCapability> = emptySet()
)

data class DownloadCapability(
    val availability: DownloadAvailability,
    val reason: String = ""
)

data class StreamResult(
    val uri: Uri?,
    val metadata: SourceMetadata? = null,
    val isPreview: Boolean = false,
    val error: String? = null
)

data class DownloadResult(
    val success: Boolean,
    val localUri: Uri? = null,
    val localPath: String? = null,
    val error: String? = null
)

/**
 * Outcome of a like/unlike request. A local favorite mirror is always kept
 * by the caller; [remoteSynced] tells whether SoundCloud itself accepted the
 * change so the UI never pretends a local favorite is a SoundCloud like.
 */
data class LikeResult(
    val success: Boolean,
    val liked: Boolean,
    val remoteSynced: Boolean = false,
    val message: String = ""
)

/**
 * Rich outcome of a search request, so the UI can distinguish "no matches",
 * "source not configured", and hard failures (network / HTTP / auth) instead
 * of treating everything as an empty result set.
 */
sealed class SourceSearchResult {
    data class Success(val results: List<SourceMetadata>) : SourceSearchResult()
    data class Empty(val message: String = "No results") : SourceSearchResult()
    data class NotConfigured(val message: String) : SourceSearchResult()
    data class Error(val message: String, val statusCode: Int? = null) : SourceSearchResult()
}

interface MusicSource {
    val sourceId: String
    val capabilities: Set<SourceCapability>

    fun search(query: String): List<SourceMetadata>
    fun getTrack(id: SourceTrackId): SourceMetadata?
    fun stream(track: SourceMetadata): StreamResult
    fun checkDownloadAvailability(track: SourceMetadata): DownloadCapability
    fun download(track: SourceMetadata, targetDir: File): DownloadResult

    /**
     * Detailed search used by the Search UI. Defaults to wrapping [search],
     * which is enough for local sources that cannot fail with network errors.
     */
    fun searchDetailed(query: String): SourceSearchResult {
        val results = search(query)
        return if (results.isEmpty()) SourceSearchResult.Empty() else SourceSearchResult.Success(results)
    }

    /**
     * Streaming download with byte progress and cooperative cancellation.
     * Default implementations fall back to [download]; sources that can perform
     * a real transfer override this so the UI can show live progress.
     * Implementations must write only complete files (temp + rename) and must
     * delete partial data when [isCancelled] turns true.
     */
    fun downloadWithProgress(
        track: SourceMetadata,
        targetDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
    ): DownloadResult = download(track, targetDir)

    /**
     * Likes ([liked]=true) or unlikes ([liked]=false) a track on the source.
     * Sources without account-backed likes keep the default, which reports
     * "not supported" so callers keep the change local-only and stay honest.
     */
    fun setTrackLiked(track: SourceMetadata, liked: Boolean): LikeResult =
        LikeResult(success = false, liked = liked, message = "Likes are not supported by this source")

    /**
     * Track-id values the signed-in user liked on the source, or null when
     * unknown (not signed in, offline, auth failure). Null means "unknown",
     * never "no likes".
     */
    fun getLikedTrackIds(): Set<String>? = null
}
