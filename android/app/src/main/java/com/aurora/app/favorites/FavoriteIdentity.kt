package com.aurora.app.favorites

import com.aurora.app.Track

/**
 * Stable, source-aware identity for a favorite.
 *
 * Online tracks use `<source>:<sourceTrackId>` so the same title/id from two
 * different providers can never collide and the same logical track stays one
 * favorite whether it is a search result, a saved library entry, or a
 * downloaded local file that still carries its origin.
 *
 * Local/imported tracks use `local:<trackId>` (their Room row id).
 */
fun favoriteKey(source: String, sourceTrackId: String, trackId: Long): String =
    if (source.isNotBlank() && sourceTrackId.isNotBlank()) "$source:$sourceTrackId"
    else "local:$trackId"

/** @see favoriteKey */
val Track.favoriteKey: String
    get() = favoriteKey(source, sourceTrackId, id)

/** True when this track comes from an online [com.aurora.app.source.MusicSource]. */
val Track.isOnlineTrack: Boolean
    get() = source.isNotBlank() && sourceTrackId.isNotBlank()
