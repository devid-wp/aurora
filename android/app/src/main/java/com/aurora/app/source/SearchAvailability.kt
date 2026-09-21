package com.aurora.app.source

import android.net.Uri

/**
 * What a search hit is actually allowed to do, derived only from the source's
 * declared capabilities plus whether a local copy already exists.
 *
 * Kept pure (no Android, no UI) so the Search screen's decisions are unit
 * testable and the UI never invents permission the source did not grant.
 */
data class SearchAvailability(
    val playable: Boolean,
    val preview: Boolean,
    val downloadable: Boolean,
    val unavailable: Boolean,
    /** Catalog item that plays in an external app (Spotify) rather than Aurora. */
    val external: Boolean = false
)

/**
 * Resolves the actionable capabilities of one search result.
 *
 * A track that is [downloaded] (a verified local file exists) stays playable
 * even when its remote source now reports it as blocked or removed — the local
 * bytes are authoritative for playback. Downloading is never offered for a
 * track that is already local, and local device tracks with a real URI can be
 * copied into Aurora storage.
 */
fun resolveSearchAvailability(
    capabilities: Set<SourceCapability>,
    downloaded: Boolean,
    isLocal: Boolean,
    hasLocalUri: Boolean
): SearchAvailability {
    val streamable = capabilities.contains(SourceCapability.STREAM)
    val previewOnly = capabilities.contains(SourceCapability.PREVIEW) && !streamable
    val localPlayable = downloaded || (isLocal && hasLocalUri)
    val playable = streamable || previewOnly || localPlayable
    val downloadable = !downloaded &&
        (capabilities.contains(SourceCapability.DOWNLOAD) || (isLocal && hasLocalUri))
    // External-playback catalog items are neither locally playable nor
    // "unavailable": the UI offers to open them in their own app.
    val external = !playable && capabilities.contains(SourceCapability.EXTERNAL_PLAYBACK)
    return SearchAvailability(
        playable = playable,
        preview = previewOnly,
        downloadable = downloadable,
        unavailable = !playable && !external,
        external = external
    )
}

/**
 * Human label for a search hit's origin. Uses the source id so an unknown or
 * future provider is never mislabelled as SoundCloud.
 */
fun sourceDisplayName(sourceId: String): String = when (sourceId) {
    "soundcloud" -> "SoundCloud"
    "audius" -> "Audius"
    "spotify" -> "Spotify"
    "aurora_local" -> "On device"
    else -> sourceId.split('_', '-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        .ifBlank { "Online" }
}

/**
 * Capabilities for a saved online library entry, derived from its source id.
 * Spotify entries are external-playback catalog items; every other online
 * source streams through Aurora.
 */
fun capabilitiesForOnlineSource(sourceId: String): Set<SourceCapability> =
    if (sourceId == "spotify") setOf(SourceCapability.EXTERNAL_PLAYBACK)
    else setOf(SourceCapability.STREAM)

/** Canonical external URI for a saved online catalog item, or null when none applies. */
fun externalUriForOnlineSource(sourceId: String, sourceTrackId: String): Uri? =
    if (sourceId == "spotify" && sourceTrackId.isNotBlank()) {
        Uri.parse("spotify:track:$sourceTrackId")
    } else {
        null
    }
