package com.aurora.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.TrackEntity
import com.aurora.app.database.repositories.FavoriteRepository
import com.aurora.app.database.repositories.LibraryRepository
import com.aurora.app.database.repositories.toTrack
import com.aurora.app.favorites.favoriteKey
import com.aurora.app.source.SourceCapability
import com.aurora.app.source.SourceMetadata
import com.aurora.app.source.SourceTrackId
import com.aurora.app.source.stableSourceTrackId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end tests for the unified favorites model: one Room-backed favorite
 * per logical track across local, Audius, SoundCloud and future sources, with
 * a source-aware identity and no SharedPreferences duplication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AuroraDatabase
    private lateinit var favorites: FavoriteRepository
    private lateinit var library: LibraryRepository

    @Before
    fun setUp() {
        db = AuroraDatabase.inMemory(context)
        favorites = FavoriteRepository(db)
        library = LibraryRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun metadata(source: String, value: String, title: String = "Online $value"): SourceMetadata =
        SourceMetadata(
            trackId = SourceTrackId(source, value),
            title = title,
            artist = "Remote Artist",
            album = "Remote Album",
            durationMs = 120_000L,
            sourceCapabilities = setOf(SourceCapability.STREAM)
        )

    private fun onlineTrack(source: String, value: String, title: String = "Online $value"): Track =
        Track(
            id = stableSourceTrackId(source, value),
            title = title,
            artist = "Remote Artist",
            album = "Remote Album",
            duration = 120_000L,
            uri = Uri.EMPTY,
            albumId = 0L,
            source = source,
            sourceTrackId = value
        )

    private fun seedLocalTrack(id: Long, title: String = "Local $id", hashSeed: String = "local-$id"): Track {
        val file = File(context.cacheDir, "fav-local-$id.mp3").apply { writeBytes("audio".toByteArray()) }
        db.trackDao().upsert(
            TrackEntity(
                id = id,
                title = title,
                artist = "Local Artist",
                album = "Local Album",
                durationMs = 1000L,
                uri = Uri.fromFile(file).toString(),
                sourceType = "aurora_imported",
                isAuroraImported = true,
                localPath = file.absolutePath,
                contentHash = hashSeed
            )
        )
        return db.trackDao().getById(id)!!.toTrack()
    }

    // ── Identity ──────────────────────────────────────────────────────────

    @Test
    fun favorite_identity_uses_source_and_source_track_id() {
        assertEquals("audius:abc", favoriteKey("audius", "abc", 0L))
        assertEquals("soundcloud:xyz", favoriteKey("soundcloud", "xyz", 0L))
        assertEquals("local:42", favoriteKey("", "", 42L))
        // Online identity must not depend on the (possibly colliding) numeric id.
        assertEquals(favoriteKey("audius", "abc", 1L), favoriteKey("audius", "abc", 999L))
    }

    @Test
    fun different_sources_with_same_id_or_title_do_not_collide() {
        val audius = onlineTrack("audius", "same", title = "Same Title")
        val soundcloud = onlineTrack("soundcloud", "same", title = "Same Title")

        favorites.add(audius, metadata("audius", "same", "Same Title"))
        favorites.add(soundcloud, metadata("soundcloud", "same", "Same Title"))

        assertEquals(2, favorites.count())
        assertTrue(favorites.isFavorite(audius))
        assertTrue(favorites.isFavorite(soundcloud))
        assertTrue(audius.favoriteKey != soundcloud.favoriteKey)
    }

    // ── Local / imported ──────────────────────────────────────────────────

    @Test
    fun favorite_local_track_is_persisted_and_resolvable() {
        val local = seedLocalTrack(7L)

        favorites.add(local)

        assertTrue(favorites.isFavorite(local))
        assertEquals(1, favorites.count())
        assertTrue("local favorite must resolve back to its library row", favorites.getFavoriteTracks().any { it.id == 7L })
        assertEquals("local:7", local.favoriteKey)
    }

    @Test
    fun favorite_is_idempotent() {
        val local = seedLocalTrack(8L)
        favorites.add(local)
        favorites.add(local)
        assertEquals(1, favorites.count())
    }

    // ── Online ────────────────────────────────────────────────────────────

    @Test
    fun favorite_online_track_is_saved_into_the_library() {
        val track = onlineTrack("audius", "abc", "Audius Song")

        favorites.add(track, metadata("audius", "abc", "Audius Song"))

        assertTrue(favorites.isFavorite(track))
        // Requirement: a favorited search result appears under Library/Favorites.
        val inLibrary = library.getAuroraTracks().any {
            it.title == "Audius Song" && it.source == "audius" && it.sourceTrackId == "abc"
        }
        assertTrue("favorited online track must appear in the library", inLibrary)
        assertTrue(favorites.getFavoriteTracks().any { it.title == "Audius Song" })
    }

    @Test
    fun favorite_search_result_appears_in_library() {
        // A search result is just an online Track + its source metadata.
        val result = onlineTrack("soundcloud", "urn:1", "Search Hit")
        favorites.add(result, metadata("soundcloud", "urn:1", "Search Hit"))

        assertTrue(library.getSavedOnlineTracks().any { it.title == "Search Hit" })
        assertTrue(library.getAuroraTracks().any { it.title == "Search Hit" })
    }

    @Test
    fun unfavorite_online_entry_removes_it_without_touching_files() {
        val track = onlineTrack("audius", "del", "To Remove")
        favorites.add(track, metadata("audius", "del", "To Remove"))
        assertNotNull(db.trackDao().getBySource("audius", "del"))

        favorites.remove(track)

        assertFalse(favorites.isFavorite(track))
        assertEquals(0, favorites.count())
        // The pure online entry only existed because it was favorited: unsaved.
        assertNull(db.trackDao().getBySource("audius", "del"))
    }

    // ── Download interaction ──────────────────────────────────────────────

    @Test
    fun favorite_then_download_does_not_create_a_second_favorite() {
        val track = onlineTrack("audius", "dl", "Downloaded Song")
        favorites.add(track, metadata("audius", "dl", "Downloaded Song"))

        // Simulate DownloadManager.persistTrack: a NEW content-hash row that
        // preserves the online source identity.
        val file = File(context.cacheDir, "fav-downloaded.mp3").apply { writeBytes("audio".toByteArray()) }
        db.trackDao().upsert(
            TrackEntity(
                id = 555_001L,
                title = "Downloaded Song",
                artist = "Remote Artist",
                album = "Remote Album",
                durationMs = 120_000L,
                uri = Uri.fromFile(file).toString(),
                sourceType = "aurora_imported",
                isAuroraImported = true,
                localPath = file.absolutePath,
                contentHash = "fav-downloaded-hash",
                source = "audius",
                sourceTrackId = "dl"
            )
        )
        val downloaded = db.trackDao().getById(555_001L)!!.toTrack()

        // The downloaded row shares the same identity, so it is already favorite...
        assertTrue(favorites.isFavorite(downloaded))
        // ...and re-favoriting it must not add a second one.
        favorites.add(downloaded)
        assertEquals(1, favorites.count())
        assertEquals(1, favorites.favoriteKeys().count { it == "audius:dl" })
    }

    @Test
    fun favorite_resolves_to_the_downloaded_file_after_download() {
        val track = onlineTrack("audius", "res", "Resolved Song")
        favorites.add(track, metadata("audius", "res", "Resolved Song"))

        val file = File(context.cacheDir, "fav-resolved.mp3").apply { writeBytes("audio".toByteArray()) }
        db.trackDao().upsert(
            TrackEntity(
                id = 777_001L,
                title = "Resolved Song",
                artist = "Remote Artist",
                album = "Remote Album",
                durationMs = 120_000L,
                uri = Uri.fromFile(file).toString(),
                sourceType = "aurora_imported",
                isAuroraImported = true,
                localPath = file.absolutePath,
                contentHash = "fav-resolved-hash",
                source = "audius",
                sourceTrackId = "res"
            )
        )

        val resolved = favorites.getFavoriteTracks().single { it.sourceTrackId == "res" }
        assertEquals("file", resolved.uri.scheme)
        assertTrue(File(resolved.uri.path!!).exists())
    }

    @Test
    fun unfavorite_downloaded_track_keeps_the_file_and_row() {
        val local = seedLocalTrack(31L, title = "Downloaded Local")
        favorites.add(local)
        assertTrue(favorites.isFavorite(local))
        val file = File(local.uri.path!!)

        favorites.remove(local)

        assertFalse(favorites.isFavorite(local))
        assertNotNull("downloaded/imported row must survive unfavorite", db.trackDao().getById(31L))
        assertTrue("actual audio file must never be deleted by unfavorite", file.exists())
    }

    // ── Restart persistence ───────────────────────────────────────────────

    @Test
    fun favorites_survive_a_database_restart() {
        val dbName = "favorites-restart-${System.nanoTime()}.db"
        val track = onlineTrack("soundcloud", "persist", "Persistent")
        try {
            val first = Room.databaseBuilder(context, AuroraDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            FavoriteRepository(first).add(track, metadata("soundcloud", "persist", "Persistent"))
            first.close()

            val second = Room.databaseBuilder(context, AuroraDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            val restarted = FavoriteRepository(second)
            try {
                assertTrue(restarted.isFavoriteKey(track.favoriteKey))
                assertTrue(restarted.favoriteKeys().contains(track.favoriteKey))
            } finally {
                second.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }
}
