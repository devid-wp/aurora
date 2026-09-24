package com.aurora.app.source

/**
 * Shared helpers for sources that write downloaded audio into Aurora storage.
 * Kept here so Audius and SoundCloud build file names the same way instead of
 * carrying two subtly different copies.
 */

/** Maps an HTTP Content-Type to a safe audio file extension. */
internal fun audioExtensionForContentType(contentType: String?): String = when {
    contentType == null -> ".mp3"
    contentType.contains("flac") -> ".flac"
    contentType.contains("wav") || contentType.contains("wave") -> ".wav"
    contentType.contains("aac") -> ".aac"
    contentType.contains("ogg") || contentType.contains("opus") -> ".ogg"
    contentType.contains("mp4") || contentType.contains("m4a") -> ".m4a"
    else -> ".mp3"
}

/** Sanitizes a track title into a filesystem-safe base name. */
internal fun sanitizeAudioFilename(name: String, fallback: String): String {
    val cleaned = name.replace(Regex("[\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), "_").trim('_')
    return if (cleaned.isBlank()) fallback else cleaned
}
