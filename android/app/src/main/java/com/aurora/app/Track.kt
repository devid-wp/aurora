package com.aurora.app

import android.net.Uri

/**
 * Represents a single audio track discovered via MediaStore.
 * All fields are sourced from the device media database; no network involved.
 */
data class Track(
    val id       : Long,
    val title    : String,
    val artist   : String,
    val album    : String,
    val duration : Long,   // milliseconds
    val uri      : Uri,
    val albumId  : Long
) {
    /** Duration formatted as m:ss (e.g. "3:42"). */
    val durationLabel: String
        get() {
            val totalSec = (duration / 1000L).coerceAtLeast(0L)
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
}
