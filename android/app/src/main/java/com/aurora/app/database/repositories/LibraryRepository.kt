package com.aurora.app.database.repositories

import android.net.Uri
import com.aurora.app.Track
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.AlbumEntity
import com.aurora.app.database.entities.ArtistEntity
import com.aurora.app.database.entities.TrackEntity
import java.io.File

class LibraryRepository(private val database: AuroraDatabase) {
    private val artistDao = database.artistDao()
    private val albumDao = database.albumDao()
    private val trackDao = database.trackDao()

    fun syncMediaStoreTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return

        val artistNames = tracks.map { it.artist }.distinct()
        val artists = artistNames.map { artistName ->
            ArtistEntity(name = artistName, sortName = artistName)
        }
        artistDao.upsertAll(artists)

        val artistLookup = artistDao.getAll().associateBy { it.name }

        val albumNames = tracks.map { it.album }.distinct()
        val albums = albumNames.map { albumName ->
            val artistName = tracks.firstOrNull { it.album == albumName }?.artist
            AlbumEntity(
                title = albumName,
                artistId = artistLookup[artistName]?.artistId,
                artworkUri = null
            )
        }
        albumDao.upsertAll(albums)

        val albumLookup = albumDao.getAll().associateBy { it.title }

        val entities = tracks.map { track ->
            val artistId = artistLookup[track.artist]?.artistId
            val albumId = albumLookup[track.album]?.albumId
            TrackEntity(
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.duration,
                uri = track.uri.toString(),
                mediaStoreId = track.id,
                artistId = artistId,
                albumId = albumId,
                sourceType = "media_store",
                isAuroraImported = false
            )
        }

        trackDao.upsertAll(entities)
    }

    fun getAllTracks(): List<TrackEntity> = trackDao.getAll()

    fun getAuroraTracks(): List<Track> = trackDao.getAll()
        .filter { it.isAuroraImported || it.sourceType == "aurora_imported" }
        .map { it.toTrack() }

    fun getDownloadedTracks(): List<Track> = getAuroraTracks()
        .sortedByDescending { it.duration }

    fun getRecentlyAdded(): List<Track> = getAuroraTracks()
        .sortedByDescending { it.id }

    fun getAuroraAlbums(): List<String> = trackDao.getAll()
        .filter { it.isAuroraImported || it.sourceType == "aurora_imported" }
        .map { it.album }
        .distinct()
        .sortedBy { it.lowercase() }

    fun getAuroraArtists(): List<String> = trackDao.getAll()
        .filter { it.isAuroraImported || it.sourceType == "aurora_imported" }
        .map { it.artist }
        .distinct()
        .sortedBy { it.lowercase() }

    fun getTrack(trackId: Long): TrackEntity? = trackDao.getById(trackId)

    private fun TrackEntity.toTrack(): Track {
        val resolvedUri = if (!localPath.isNullOrBlank() && File(localPath).exists()) {
            Uri.fromFile(File(localPath))
        } else {
            uri.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: Uri.EMPTY
        }

        return Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = durationMs,
            uri = resolvedUri,
            albumId = albumId ?: 0L
        )
    }
}
