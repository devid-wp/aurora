package com.aurora.app.database.repositories

import com.aurora.app.Track
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.FavoriteEntity
import com.aurora.app.favorites.favoriteKey
import com.aurora.app.favorites.isOnlineTrack
import com.aurora.app.source.SourceCapability
import com.aurora.app.source.SourceMetadata
import com.aurora.app.source.SourceTrackId
import com.aurora.app.source.stableSourceTrackId

/**
 * The single source of truth for Aurora favorites, backed by Room.
 *
 * One favorite exists per logical track regardless of where it came from:
 *  - local/imported rows (key `local:<trackId>`), and
 *  - online rows (key `<source>:<sourceTrackId>`), which are also saved into
 *    the library so a favorited search result appears under Library/Favorites.
 *
 * SharedPreferences no longer stores favorites. Downloads preserve the online
 * source identity on the local row, so a favorited track stays one favorite
 * after it is downloaded.
 */
class FavoriteRepository(private val database: AuroraDatabase) {
    private val favoriteDao = database.favoriteDao()
    private val trackDao = database.trackDao()
    private val libraryRepository = LibraryRepository(database)

    fun isFavorite(track: Track): Boolean = isFavoriteKey(track.favoriteKey)

    fun isFavoriteKey(key: String): Boolean = favoriteDao.getByKey(key) != null

    fun favoriteKeys(): Set<String> = favoriteDao.getAll().map { it.favoriteKey }.toSet()

    fun getAll(): List<FavoriteEntity> = favoriteDao.getAll()

    fun count(): Int = favoriteDao.getAll().size

    /**
     * Favorites a local/imported or online track. Online tracks are also saved
     * into the library (reusing the existing stable-identity save path) so the
     * favorite shows up under Library/Favorites. Idempotent: re-favoriting the
     * same identity never creates a second row.
     */
    fun add(track: Track, metadata: SourceMetadata? = null): Boolean {
        if (track.isOnlineTrack) {
            ensureLibraryEntry(track, metadata)
        }
        favoriteDao.upsert(
            FavoriteEntity(
                favoriteKey = track.favoriteKey,
                trackId = track.id,
                source = track.source,
                sourceTrackId = track.sourceTrackId
            )
        )
        return true
    }

    /**
     * Favorites an online track by its source identity alone, for callers that
     * only know the source ids (e.g. mirroring the signed-in SoundCloud likes).
     */
    fun addSourceFavorite(source: String, sourceTrackId: String): Boolean {
        if (source.isBlank() || sourceTrackId.isBlank()) return false
        favoriteDao.upsert(
            FavoriteEntity(
                favoriteKey = favoriteKey(source, sourceTrackId, 0L),
                trackId = stableSourceTrackId(source, sourceTrackId),
                source = source,
                sourceTrackId = sourceTrackId
            )
        )
        return true
    }

    /**
     * Removes the favorite. Audio is never touched: a downloaded or imported
     * file stays on disk. A saved-online entry that only existed because it was
     * favorited is unsaved (it has no local file), while a downloaded copy is
     * left in the library.
     */
    fun remove(track: Track): Boolean {
        favoriteDao.removeByKey(track.favoriteKey)
        if (track.isOnlineTrack) {
            val row = trackDao.getBySource(track.source, track.sourceTrackId)
            if (row != null && row.isSaved && row.localPath.isNullOrBlank()) {
                libraryRepository.unsaveOnlineTrack(row.id)
            }
        }
        return favoriteDao.getByKey(track.favoriteKey) == null
    }

    /** Toggles a favorite and returns the new state (true = favorited). */
    fun toggle(track: Track, metadata: SourceMetadata? = null): Boolean =
        if (isFavorite(track)) {
            remove(track)
            false
        } else {
            add(track, metadata)
        }

    /**
     * Favorite tracks resolved back to their library rows. Online favorites are
     * always saved to the library, so they resolve; a mirrored remote like with
     * no known metadata is simply omitted until its real row appears.
     */
    fun getFavoriteTracks(): List<Track> {
        val allTracks = trackDao.getAll()
        val tracksById = allTracks.associateBy { it.id }
        return favoriteDao.getAll().mapNotNull { favorite ->
            val direct = tracksById[favorite.trackId]
            // A downloaded copy shares the online identity but has a different
            // id: prefer the file-backed row so the favorite resolves to a
            // playable local track instead of the hidden remote entry.
            val resolved = if (favorite.source.isNotBlank() && favorite.sourceTrackId.isNotBlank()) {
                allTracks.firstOrNull {
                    it.source == favorite.source &&
                        it.sourceTrackId == favorite.sourceTrackId &&
                        !it.localPath.isNullOrBlank()
                } ?: direct
            } else {
                direct
            }
            resolved?.toTrack()
        }
    }

    fun clearAll(): Int = favoriteDao.clearAll()

    private fun ensureLibraryEntry(track: Track, metadata: SourceMetadata?) {
        if (trackDao.getBySource(track.source, track.sourceTrackId) != null) return
        libraryRepository.saveOnlineTrack(metadata ?: metadataFromTrack(track))
    }

    private fun metadataFromTrack(track: Track): SourceMetadata = SourceMetadata(
        trackId = SourceTrackId(track.source, track.sourceTrackId),
        title = track.title,
        artist = track.artist,
        album = track.album,
        durationMs = track.duration,
        artworkUri = track.artworkUri,
        sourceCapabilities = setOf(SourceCapability.STREAM)
    )
}
