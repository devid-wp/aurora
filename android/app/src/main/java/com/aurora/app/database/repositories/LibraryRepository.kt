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

    /**
     * Aurora-owned tracks only: explicitly imported files and explicitly
     * downloaded tracks. Legacy device-wide rows (if any) are never surfaced
     * here — Aurora does not automatically adopt phone music.
     */
    fun getAuroraTrackEntities(): List<TrackEntity> = trackDao.getAll()
        .filter { it.isAuroraImported || it.sourceType == "aurora_imported" || it.isSaved }

    fun getAuroraTracks(): List<Track> = getAuroraTrackEntities()
        .map { it.toTrack() }

    fun getDownloadedTracks(): List<Track> = getAuroraTrackEntities()
        .filter { !it.localPath.isNullOrBlank() }
        .map { it.toTrack() }
        .sortedByDescending { it.duration }

    fun getRecentlyAdded(): List<Track> = getAuroraTracks()
        .sortedByDescending { it.id }

    fun getAuroraAlbums(): List<String> = trackDao.getAll()
        .filter { it.isAuroraImported || it.sourceType == "aurora_imported" || it.isSaved }
        .map { it.album }
        .distinct()
        .sortedBy { it.lowercase() }

    fun getAuroraArtists(): List<String> = trackDao.getAll()
        .filter { it.isAuroraImported || it.sourceType == "aurora_imported" || it.isSaved }
        .map { it.artist }
        .distinct()
        .sortedBy { it.lowercase() }

    fun getTrack(trackId: Long): TrackEntity? = trackDao.getById(trackId)

    /**
     * Saves an online search result into the Aurora library as a remote entry:
     * stable id, source origin, metadata, no local file. Re-saving the same
     * track replaces the same row. Never invents download permission.
     */
    fun saveOnlineTrack(metadata: com.aurora.app.source.SourceMetadata): Long {
        val artistName = metadata.artist.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        val albumName = metadata.album.takeIf { it.isNotBlank() } ?: "Unknown Album"
        val title = metadata.title.takeIf { it.isNotBlank() } ?: "Unknown track"
        val artistId = artistDao.getByName(artistName)?.artistId
            ?: artistDao.upsert(ArtistEntity(name = artistName, sortName = artistName))
        val albumId = albumDao.getByTitle(albumName)?.albumId
            ?: albumDao.upsert(
                AlbumEntity(title = albumName, artistId = artistId, artworkUri = null)
            )
        val now = System.currentTimeMillis()
        trackDao.upsert(
            TrackEntity(
                id = com.aurora.app.source.stableSourceTrackId(metadata.trackId.source, metadata.trackId.value),
                title = title,
                artist = artistName,
                album = albumName,
                durationMs = metadata.durationMs.coerceAtLeast(0L),
                uri = "",
                artistId = artistId,
                albumId = albumId,
                sourceType = metadata.trackId.source,
                isAuroraImported = false,
                localPath = null,
                artworkPath = null,
                contentHash = null,
                source = metadata.trackId.source,
                sourceTrackId = metadata.trackId.value,
                artworkUrl = metadata.artworkUri?.toString(),
                isSaved = true,
                createdAt = now,
                updatedAt = now
            )
        )
        return trackDao.getBySource(metadata.trackId.source, metadata.trackId.value)?.id
            ?: com.aurora.app.source.stableSourceTrackId(metadata.trackId.source, metadata.trackId.value)
    }

    /**
     * Removes a saved online entry from the library. File-backed rows are
     * never touched here: downloaded files are only ever removed through the
     * Downloads flow, never automatically.
     */
    fun unsaveOnlineTrack(trackId: Long): Boolean {
        val row = trackDao.getById(trackId) ?: return false
        if (!row.isSaved || !row.localPath.isNullOrBlank()) return false
        return trackDao.deleteById(trackId) > 0
    }

    fun isSaved(source: String, sourceTrackId: String): Boolean =
        trackDao.getBySource(source, sourceTrackId)?.isSaved == true

    fun getSavedOnlineTracks(): List<Track> = trackDao.getSavedOnline().map { it.toTrack() }

    /** Saved online entries as playable source metadata (fresh stream per play). */
    fun getSavedOnlineMetadata(): Map<Long, com.aurora.app.source.SourceMetadata> =
        trackDao.getSavedOnline().associate { entity ->
            entity.id to com.aurora.app.source.SourceMetadata(
                trackId = com.aurora.app.source.SourceTrackId(entity.source, entity.sourceTrackId),
                title = entity.title,
                artist = entity.artist,
                album = entity.album,
                durationMs = entity.durationMs,
                artworkUri = entity.artworkUrl?.takeIf { it.isNotBlank() }?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() },
                localUri = null,
                sourceCapabilities = setOf(com.aurora.app.source.SourceCapability.STREAM)
            )
        }

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
