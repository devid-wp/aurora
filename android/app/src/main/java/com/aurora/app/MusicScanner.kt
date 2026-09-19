package com.aurora.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Queries the device's MediaStore for audio tracks.
 * Supports every format the OS exposes (MP3, FLAC, OGG/Opus, AAC, …).
 * No network, no downloads – local files only.
 */
object MusicScanner {

    fun scan(context: Context): List<Track> {
        val tracks    = mutableListOf<Track>()
        val baseUri   = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        // Only real music tracks; skip short clips (< 30 s) and ringtones.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0" +
                        " AND ${MediaStore.Audio.Media.DURATION} > 30000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(baseUri, projection, selection, null, sortOrder)
                ?.use { cursor ->
                    val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durCol      = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val albumIdCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                    while (cursor.moveToNext()) {
                        val id      = cursor.getLong(idCol)
                        val title   = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown Track"
                        val artist  = cursor.getString(artistCol)?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                        val album   = cursor.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "Unknown Album"
                        val dur     = cursor.getLong(durCol)
                        val albumId = cursor.getLong(albumIdCol)
                        val uri     = Uri.withAppendedPath(baseUri, id.toString())
                        tracks.add(Track(id, title, artist, album, dur, uri, albumId))
                    }
                }
        } catch (_: Exception) {
            // Return whatever we have so far; permission issues surface via empty list.
        }

        return tracks
    }
}
