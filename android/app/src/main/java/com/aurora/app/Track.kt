package com.aurora.app

import android.net.Uri

/**
 * Represents a single audio track.
 * For local tracks, data is sourced from MediaStore.
 * For online tracks, it holds a resolved stream URI and remote metadata.
 *
 * [artworkUri] is kept last so positional construction stays source-compatible.
 * It may be an http(s) remote cover URL, a `file://` cover, or null when the
 * cover must be resolved from MediaStore / embedded tags / generative art.
 *
 * [source] and [sourceTrackId] carry the stable online identity
 * ("audius" + track id, "soundcloud" + urn, ...). They are blank for
 * local/imported tracks. Together they form the source-aware favorite identity
 * so the same logical track stays one favorite across search, library and
 * downloads.
 */
data class Track(
    val id       : Long,
    val title    : String,
    val artist   : String,
    val album    : String,
    val duration : Long,   // milliseconds
    val uri      : Uri,
    val albumId  : Long,
    val artworkUri: Uri? = null,
    val source   : String = "",
    val sourceTrackId: String = ""
) {
    /** Duration formatted as m:ss (e.g. "3:42"). */
    val durationLabel: String
        get() {
            val totalSec = (duration / 1000L).coerceAtLeast(0L)
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
}
