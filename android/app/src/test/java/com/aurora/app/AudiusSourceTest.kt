package com.aurora.app

import com.aurora.app.source.AudiusSource
import com.aurora.app.source.DownloadAvailability
import com.aurora.app.source.SoundCloudHttpClient
import com.aurora.app.source.SoundCloudResponse
import com.aurora.app.source.SourceCapability
import com.aurora.app.source.SourceMetadata
import com.aurora.app.source.SourceSearchResult
import com.aurora.app.source.SourceTrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for the Audius online source.
 *
 * Every network interaction goes through a fake [SoundCloudHttpClient] (the
 * shared project HTTP abstraction), so search mapping, stream resolution,
 * and error paths are asserted without a network. Fixtures mirror the real
 * `https://api.audius.co/v1` response shapes (probed live, then trimmed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudiusSourceTest {

    // ── Fixtures (real Audius shapes, trimmed) ────────────────────────────

    private fun playableTrackJson(id: String = "zKYMk7p"): String = """
        {"id": "$id", "title": "MIDNIGHT.", "duration": 185, "genre": "Electronic",
         "permalink": "/zaza/midnight",
         "is_streamable": true, "is_available": true, "is_delete": false,
         "is_unlisted": false, "is_downloadable": false, "is_stream_gated": false,
         "access": {"stream": true, "download": true},
         "artwork": {"150x150": "https://v.monophonic.digital/content/ABC/150x150.jpg",
                     "480x480": "https://v.monophonic.digital/content/ABC/480x480.jpg",
                     "1000x1000": "https://v.monophonic.digital/content/ABC/1000x1000.jpg",
                     "mirrors": ["https://creatornode.audius.co"]},
         "user": {"handle": "zaza", "name": "zaza", "id": "DNzo4"}}
    """.trimIndent()

    private fun deletedTrackJson(): String = """
        {"id": "deadbeef", "title": "Gone", "duration": 120, "genre": "",
         "is_streamable": false, "is_available": false, "is_delete": true,
         "is_unlisted": false, "is_stream_gated": false,
         "user": {"handle": "ghost", "name": "ghost"}}
    """.trimIndent()

    private fun collectionSearch(vararg lines: String): String =
        """{"data": [${lines.joinToString(",")}]}"""

    private fun singleTrack(body: String): String = """{"data": $body}"""

    private fun streamJson(url: String = "https://v.monophonic.digital/tracks/cidstream/ABC?signature=xyz"): String =
        """{"data": "$url"}"""

    /** Artist enabled downloads and the current user has access. */
    private fun downloadableTrackJson(id: String = "dl1"): String = """
        {"id": "$id", "title": "Free Download", "duration": 200, "genre": "House",
         "is_streamable": true, "is_available": true, "is_delete": false,
         "is_unlisted": false, "is_downloadable": true, "is_download_gated": false,
         "is_stream_gated": false,
         "access": {"stream": true, "download": true},
         "artwork": {"150x150": "https://cdn/150x150.jpg"},
         "user": {"handle": "artist", "name": "Artist"}}
    """.trimIndent()

    /** Artist enabled downloads but the current user is gated out. */
    private fun gatedDownloadTrackJson(id: String = "gate1"): String = """
        {"id": "$id", "title": "Gated", "duration": 200, "genre": "House",
         "is_streamable": true, "is_available": true, "is_delete": false,
         "is_unlisted": false, "is_downloadable": true, "is_download_gated": true,
         "is_stream_gated": false,
         "access": {"stream": true, "download": false},
         "user": {"handle": "artist", "name": "Artist"}}
    """.trimIndent()

    /** No download flags at all (older shape): download must stay unavailable. */
    private fun unknownDownloadTrackJson(id: String = "unk1"): String = """
        {"id": "$id", "title": "Unknown", "duration": 200,
         "is_streamable": true, "is_available": true,
         "user": {"handle": "artist", "name": "Artist"}}
    """.trimIndent()

    private fun source(client: FakeAudiusHttp, apiKey: String = ""): AudiusSource = AudiusSource(
        apiKeyProvider = { apiKey },
        httpClient = client
    )

    private fun playableMetadata(value: String = "zKYMk7p"): SourceMetadata = SourceMetadata(
        trackId = SourceTrackId("audius", value),
        title = "MIDNIGHT.",
        artist = "zaza",
        album = "Electronic",
        durationMs = 185000L,
        sourceCapabilities = setOf(SourceCapability.STREAM)
    )

    private class FakeAudiusHttp : SoundCloudHttpClient {
        val getRequests = mutableListOf<Pair<String, Map<String, String>>>()
        val textResponses = mutableMapOf<String, SoundCloudResponse>()
        val byteResponses = mutableMapOf<String, SoundCloudResponse>()
        val byteRequests = mutableListOf<Pair<String, Map<String, String>>>()
        var forcedGet: SoundCloudResponse? = null

        override fun get(url: String, headers: Map<String, String>): SoundCloudResponse {
            getRequests.add(url to headers)
            forcedGet?.let { return it }
            return textResponses.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { url.contains(it.key) }
                ?.value ?: SoundCloudResponse(404, body = "{\"error\":\"not found\"}")
        }

        override fun getBytes(url: String, headers: Map<String, String>): SoundCloudResponse {
            byteRequests.add(url to headers)
            return byteResponses.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { url.contains(it.key) }
                ?.value ?: SoundCloudResponse(404, body = "{\"error\":\"not found\"}")
        }

        override fun post(url: String, headers: Map<String, String>): SoundCloudResponse =
            SoundCloudResponse(404, body = "{\"error\":\"not found\"}")

        override fun delete(url: String, headers: Map<String, String>): SoundCloudResponse =
            SoundCloudResponse(404, body = "{\"error\":\"not found\"}")

        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SoundCloudResponse =
            SoundCloudResponse(404, body = "{\"error\":\"not found\"}")
    }

    // ── Search mapping ────────────────────────────────────────────────────

    @Test
    fun search_maps_real_track_shape() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = collectionSearch(playableTrackJson()))
        }

        val outcome = source(client).searchDetailed("midnight")

        assertTrue(outcome is SourceSearchResult.Success)
        val track = (outcome as SourceSearchResult.Success).results.single()
        assertEquals("zKYMk7p", track.trackId.value)
        assertEquals("audius", track.trackId.source)
        assertEquals("MIDNIGHT.", track.title)
        assertEquals("zaza", track.artist)
        // Audius reports whole seconds; Aurora works in milliseconds.
        assertEquals(185000L, track.durationMs)
        assertEquals("Electronic", track.album)
        assertEquals("https://v.monophonic.digital/content/ABC/1000x1000.jpg", track.artworkUri.toString())
        assertEquals(setOf(SourceCapability.STREAM), track.sourceCapabilities)
    }

    @Test
    fun search_uses_query_and_limit_params() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = collectionSearch())
        }

        source(client).searchDetailed("midnight drive")

        val url = client.getRequests.single().first
        assertTrue(url.contains("/v1/tracks/search"))
        assertTrue(url.contains("query=midnight+drive") || url.contains("query=midnight%20drive"))
        assertTrue(url.contains("limit="))
    }

    @Test
    fun search_empty_data_reports_empty() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = """{"data": []}""")
        }

        val outcome = source(client).searchDetailed("nothing matches this")

        assertTrue(outcome is SourceSearchResult.Empty)
    }

    @Test
    fun search_http_error_reports_error() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(500, body = "{}")
        }

        val outcome = source(client).searchDetailed("midnight")

        assertTrue(outcome is SourceSearchResult.Error)
        assertEquals(500, (outcome as SourceSearchResult.Error).statusCode)
    }

    @Test
    fun search_network_failure_reports_error() {
        val client = FakeAudiusHttp().apply {
            forcedGet = SoundCloudResponse(statusCode = -1)
        }

        val outcome = source(client).searchDetailed("midnight")

        assertTrue(outcome is SourceSearchResult.Error)
    }

    @Test
    fun search_malformed_response_reports_error() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = "this is not json")
        }

        val outcome = source(client).searchDetailed("midnight")

        // Malformed payloads surface as an honest error, never fake tracks.
        assertTrue(outcome is SourceSearchResult.Error)
    }

    @Test
    fun api_key_header_sent_when_provided() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = collectionSearch())
        }

        source(client, apiKey = "dev-key").searchDetailed("midnight")

        assertEquals("dev-key", client.getRequests.single().second["x-api-key"])
    }

    @Test
    fun no_api_key_header_when_unconfigured() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = collectionSearch(playableTrackJson()))
        }

        val outcome = source(client).searchDetailed("midnight")

        assertTrue(outcome is SourceSearchResult.Success)
        assertFalse(client.getRequests.single().second.containsKey("x-api-key"))
    }

    // ── Track lookup ──────────────────────────────────────────────────────

    @Test
    fun get_track_maps_single_object() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/zKYMk7p"] = SoundCloudResponse(200, body = singleTrack(playableTrackJson()))
        }

        val track = source(client).getTrack(SourceTrackId("audius", "zKYMk7p"))

        assertNotNull(track)
        assertEquals("MIDNIGHT.", track?.title)
        assertEquals("zaza", track?.artist)
    }

    @Test
    fun get_track_missing_returns_null() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/"] = SoundCloudResponse(404, body = "{\"error\":\"not found\"}")
        }

        assertNull(source(client).getTrack(SourceTrackId("audius", "nope")))
    }

    // ── Streaming ─────────────────────────────────────────────────────────

    @Test
    fun stream_resolves_signed_url_via_no_redirect() {
        val client = FakeAudiusHttp().apply {
            textResponses["/stream"] = SoundCloudResponse(200, body = streamJson())
        }

        val result = source(client).stream(playableMetadata())

        assertNotNull(result.uri)
        assertTrue(result.uri.toString().startsWith("https://"))
        assertFalse(result.isPreview)
        assertNull(result.error)
        val url = client.getRequests.single().first
        assertTrue(url.contains("/v1/tracks/zKYMk7p/stream"))
        assertTrue(url.contains("no_redirect=true"))
    }

    @Test
    fun stream_unavailable_track_reports_error() {
        val client = FakeAudiusHttp().apply {
            textResponses["/stream"] = SoundCloudResponse(404, body = "{\"error\":\"not found\"}")
        }

        val result = source(client).stream(playableMetadata())

        assertNull(result.uri)
        assertNotNull(result.error)
    }

    @Test
    fun blocked_deleted_track_is_blocked_and_unstreamable() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = collectionSearch(deletedTrackJson()))
        }
        val src = source(client)

        val outcome = src.searchDetailed("gone")
        assertTrue(outcome is SourceSearchResult.Success)
        val track = (outcome as SourceSearchResult.Success).results.single()
        assertEquals(setOf(SourceCapability.BLOCKED), track.sourceCapabilities)

        val result = src.stream(track)
        assertNull(result.uri)
        assertNotNull(result.error)
        assertTrue(client.getRequests.none { it.first.contains("/stream") })
    }

    // ── Downloads follow the official access flags ────────────────────────

    @Test
    fun stream_only_track_is_not_downloadable() {
        val src = source(FakeAudiusHttp())

        val capability = src.checkDownloadAvailability(playableMetadata())
        assertEquals(DownloadAvailability.UNAVAILABLE, capability.availability)

        val dir = tempDir()
        try {
            assertFalse(src.download(playableMetadata(), dir).success)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun explicitly_downloadable_track_carries_download_capability() {
        val src = source(FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = collectionSearch(downloadableTrackJson()))
        })

        val track = (src.searchDetailed("free") as SourceSearchResult.Success).results.single()

        assertEquals(setOf(SourceCapability.STREAM, SourceCapability.DOWNLOAD), track.sourceCapabilities)
        assertEquals(DownloadAvailability.AVAILABLE, src.checkDownloadAvailability(track).availability)
    }

    @Test
    fun download_gated_track_is_not_downloadable() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = collectionSearch(gatedDownloadTrackJson()))
        }
        val src = source(client)

        val track = (src.searchDetailed("gated") as SourceSearchResult.Success).results.single()

        assertFalse(track.sourceCapabilities.contains(SourceCapability.DOWNLOAD))
        assertEquals(DownloadAvailability.UNAVAILABLE, src.checkDownloadAvailability(track).availability)
        val dir = tempDir()
        try {
            assertFalse(src.download(track, dir).success)
        } finally {
            dir.deleteRecursively()
        }
        assertTrue("gated download must never hit the download endpoint",
            client.byteRequests.none { it.first.contains("/download") })
    }

    @Test
    fun unknown_download_access_is_not_downloadable() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = collectionSearch(unknownDownloadTrackJson()))
        }
        val src = source(client)

        val track = (src.searchDetailed("unknown") as SourceSearchResult.Success).results.single()

        assertFalse(track.sourceCapabilities.contains(SourceCapability.DOWNLOAD))
        assertEquals(DownloadAvailability.UNAVAILABLE, src.checkDownloadAvailability(track).availability)
    }

    @Test
    fun download_rechecks_live_permission_before_fetching() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = collectionSearch(downloadableTrackJson()))
            // The live lookup later reports the track is no longer downloadable.
            textResponses["/tracks/dl1"] = SoundCloudResponse(200, body = singleTrack(playableTrackJson("dl1")))
        }
        val src = source(client)
        val track = (src.searchDetailed("free") as SourceSearchResult.Success).results.single()

        val dir = tempDir()
        try {
            assertFalse(src.download(track, dir).success)
        } finally {
            dir.deleteRecursively()
        }
        assertTrue("a revoked track must not be fetched",
            client.byteRequests.none { it.first.contains("/download") })
    }

    @Test
    fun download_uses_official_endpoint_and_writes_file() {
        val client = FakeAudiusHttp().apply {
            textResponses["/tracks/search"] = SoundCloudResponse(200, body = collectionSearch(downloadableTrackJson()))
            textResponses["/tracks/dl1"] = SoundCloudResponse(200, body = singleTrack(downloadableTrackJson()))
            byteResponses["/tracks/dl1/download"] = SoundCloudResponse(
                statusCode = 200,
                bytes = "downloaded-audio".toByteArray(),
                contentType = "audio/mpeg"
            )
        }
        val src = source(client)
        val track = (src.searchDetailed("free") as SourceSearchResult.Success).results.single()

        val dir = tempDir()
        try {
            val result = src.download(track, dir)

            assertTrue(result.success)
            val file = File(result.localPath!!)
            assertTrue(file.exists())
            assertEquals("downloaded-audio", file.readText())
            assertTrue(result.localPath.endsWith(".mp3"))
            assertTrue("the official download endpoint must be used",
                client.byteRequests.any { it.first.contains("/tracks/dl1/download") })
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("audius-dl").toFile()
}
