package com.aurora.app

import com.aurora.app.source.DownloadAvailability
import com.aurora.app.source.SoundCloudHttpClient
import com.aurora.app.source.SoundCloudResponse
import com.aurora.app.source.SourceCapability
import com.aurora.app.source.SourceKind
import com.aurora.app.source.SourceSearchResult
import com.aurora.app.source.SpotifySource
import com.aurora.app.source.SpotifyToken
import com.aurora.app.source.spotifyWebUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the Spotify Web API integration. All network I/O goes through
 * a fake [SoundCloudHttpClient] (the project's generic HTTP abstraction), so
 * mapping, auth states, error handling and PKCE can be asserted offline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpotifySourceTest {

    // ── Fakes / fixtures ──────────────────────────────────────────────────

    private class FakeHttpClient : SoundCloudHttpClient {
        val getUrls = mutableListOf<String>()
        val getHeaders = mutableListOf<Map<String, String>>()
        val postForms = mutableListOf<Map<String, String>>()
        var getHandler: ((String, Map<String, String>) -> SoundCloudResponse)? = null
        var postFormHandler: ((Map<String, String>) -> SoundCloudResponse)? = null

        override fun get(url: String, headers: Map<String, String>): SoundCloudResponse {
            getUrls.add(url)
            getHeaders.add(headers)
            return getHandler?.invoke(url, headers) ?: SoundCloudResponse(404, body = "{\"error\":\"not found\"}")
        }

        override fun getBytes(url: String, headers: Map<String, String>): SoundCloudResponse =
            SoundCloudResponse(404)

        override fun post(url: String, headers: Map<String, String>): SoundCloudResponse =
            SoundCloudResponse(404)

        override fun delete(url: String, headers: Map<String, String>): SoundCloudResponse =
            SoundCloudResponse(404)

        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SoundCloudResponse {
            postForms.add(form)
            return postFormHandler?.invoke(form) ?: SoundCloudResponse(404)
        }
    }

    private fun tokenJson(access: String = "spotify-access", refresh: String? = "spotify-refresh", expiresIn: Long = 3600): String {
        val sb = StringBuilder("{\"access_token\":\"$access\",\"token_type\":\"Bearer\"")
        if (refresh != null) sb.append(",\"refresh_token\":\"$refresh\"")
        sb.append(",\"expires_in\":$expiresIn}")
        return sb.toString()
    }

    private fun searchFixture(): String = """
        {
          "tracks": {
            "items": [
              {
                "album": {
                  "name": "Album Name",
                  "artists": [ {"name": "Album Artist"} ],
                  "images": [
                    {"url": "https://i.scdn.co/small.jpg", "height": 64},
                    {"url": "https://i.scdn.co/large.jpg", "height": 640}
                  ]
                },
                "artists": [ {"name": "Track Artist A"}, {"name": "Track Artist B"} ],
                "duration_ms": 210000,
                "id": "track123",
                "name": "Track Name",
                "uri": "spotify:track:track123"
              }
            ],
            "total": 1
          },
          "albums": {
            "items": [
              {
                "id": "album1",
                "name": "Some Album",
                "artists": [ {"name": "Album Artist X"} ],
                "images": [ {"url": "https://i.scdn.co/a.jpg", "height": 300} ],
                "uri": "spotify:album:album1"
              }
            ],
            "total": 1
          },
          "artists": {
            "items": [
              {
                "id": "artist1",
                "name": "Artist One",
                "images": [ {"url": "https://i.scdn.co/ar.jpg", "height": 300} ],
                "uri": "spotify:artist:artist1"
              }
            ],
            "total": 1
          }
        }
    """.trimIndent()

    private fun configuredSource(client: FakeHttpClient): SpotifySource = SpotifySource(
        clientIdProvider = { "demo_client_id" },
        redirectUriProvider = { "aurora://spotify/callback" },
        httpClient = client
    )

    private fun signedInSource(client: FakeHttpClient): SpotifySource =
        configuredSource(client).apply {
            seedSessionForTesting(
                SpotifyToken(
                    accessToken = "session-token",
                    refreshToken = "refresh-token",
                    expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L
                )
            )
        }

    // ── Mapping ───────────────────────────────────────────────────────────

    @Test
    fun searchMapsTrackFieldsWithSourceAwareIdentity() {
        val client = FakeHttpClient().apply {
            getHandler = { _, _ -> SoundCloudResponse(200, body = searchFixture()) }
        }
        val source = signedInSource(client)

        val result = source.searchDetailed("track name")

        assertTrue(result is SourceSearchResult.Success)
        val tracks = (result as SourceSearchResult.Success).results.filter { it.kind == SourceKind.TRACK }
        assertEquals(1, tracks.size)
        val track = tracks.first()
        assertEquals("spotify", track.trackId.source)
        assertEquals("track123", track.trackId.value)
        assertEquals("Track Name", track.title)
        assertEquals("Track Artist A, Track Artist B", track.artist)
        assertEquals("Album Name", track.album)
        assertEquals(210000L, track.durationMs)
        assertEquals("https://i.scdn.co/large.jpg", track.artworkUri?.toString())
        assertEquals(SourceKind.TRACK, track.kind)
        assertEquals("spotify:track:track123", track.externalUri?.toString())
        assertTrue(track.sourceCapabilities.contains(SourceCapability.EXTERNAL_PLAYBACK))
        assertFalse("Spotify must never be marked locally streamable", track.sourceCapabilities.contains(SourceCapability.STREAM))
    }

    @Test
    fun searchMapsAlbumsAndArtistsAsCatalogItems() {
        val client = FakeHttpClient().apply {
            getHandler = { _, _ -> SoundCloudResponse(200, body = searchFixture()) }
        }

        val results = (signedInSource(client).searchDetailed("query") as SourceSearchResult.Success).results

        val album = results.first { it.kind == SourceKind.ALBUM }
        assertEquals("Some Album", album.title)
        assertEquals("Album Artist X", album.artist)
        assertEquals("album1", album.trackId.value)
        assertEquals("spotify", album.trackId.source)
        assertEquals("spotify:album:album1", album.externalUri?.toString())

        val artist = results.first { it.kind == SourceKind.ARTIST }
        assertEquals("Artist One", artist.title)
        assertEquals("artist1", artist.trackId.value)
        assertEquals("spotify:artist:artist1", artist.externalUri?.toString())
    }

    @Test
    fun searchSendsBearerTokenAndCatalogTypes() {
        val client = FakeHttpClient().apply {
            getHandler = { _, _ -> SoundCloudResponse(200, body = searchFixture()) }
        }
        signedInSource(client).searchDetailed("daft punk")

        assertTrue(client.getUrls.first().contains("/search"))
        assertTrue(client.getUrls.first().contains("type=track%2Calbum%2Cartist"))
        assertEquals("Bearer session-token", client.getHeaders.first()["Authorization"])
    }

    // ── Empty / malformed / failure ───────────────────────────────────────

    @Test
    fun emptyCatalog_returnsEmpty() {
        val client = FakeHttpClient().apply {
            getHandler = { _, _ ->
                SoundCloudResponse(200, body = """{"tracks":{"items":[],"total":0},"albums":{"items":[]},"artists":{"items":[]}}""")
            }
        }
        val result = signedInSource(client).searchDetailed("nothing")
        assertTrue(result is SourceSearchResult.Empty)
    }

    @Test
    fun malformedResponse_returnsError() {
        val client = FakeHttpClient().apply {
            getHandler = { _, _ -> SoundCloudResponse(200, body = "{\"unexpected\":true}") }
        }
        val result = signedInSource(client).searchDetailed("query")
        assertTrue("malformed Spotify payload must be an error, not empty", result is SourceSearchResult.Error)
    }

    @Test
    fun nonJsonBody_returnsError() {
        val client = FakeHttpClient().apply {
            getHandler = { _, _ -> SoundCloudResponse(200, body = "<html>gateway error</html>") }
        }
        assertTrue(signedInSource(client).searchDetailed("query") is SourceSearchResult.Error)
    }

    @Test
    fun networkFailure_returnsError() {
        val client = FakeHttpClient().apply {
            getHandler = { _, _ -> SoundCloudResponse(-1) }
        }
        val result = signedInSource(client).searchDetailed("query")
        assertTrue(result is SourceSearchResult.Error)
        assertTrue((result as SourceSearchResult.Error).message.contains("network", ignoreCase = true))
    }

    @Test
    fun rateLimit_returnsError() {
        val client = FakeHttpClient().apply {
            getHandler = { _, _ -> SoundCloudResponse(429) }
        }
        val result = signedInSource(client).searchDetailed("query")
        assertTrue(result is SourceSearchResult.Error)
        assertEquals(429, (result as SourceSearchResult.Error).statusCode)
    }

    // ── Authentication states ─────────────────────────────────────────────

    @Test
    fun notConfigured_returnsNotConfigured() {
        val client = FakeHttpClient()
        val source = SpotifySource(
            clientIdProvider = { "" },
            redirectUriProvider = { "aurora://spotify/callback" },
            httpClient = client
        )
        assertTrue(source.searchDetailed("query") is SourceSearchResult.NotConfigured)
    }

    @Test
    fun notSignedIn_returnsAuthRequiredError() {
        val client = FakeHttpClient()
        val result = configuredSource(client).searchDetailed("query")
        assertTrue(result is SourceSearchResult.Error)
        assertEquals(401, (result as SourceSearchResult.Error).statusCode)
        assertTrue(result.message.contains("Sign in", ignoreCase = true))
    }

    @Test
    fun rejectedToken_refreshesOnceAndSucceeds() {
        val client = FakeHttpClient().apply {
            var calls = 0
            getHandler = { _, headers ->
                calls++
                if (calls == 1) SoundCloudResponse(401)
                else if (headers["Authorization"] == "Bearer refreshed-token") SoundCloudResponse(200, body = searchFixture())
                else SoundCloudResponse(401)
            }
            postFormHandler = { form ->
                if (form["grant_type"] == "refresh_token") SoundCloudResponse(200, body = tokenJson(access = "refreshed-token", refresh = null))
                else SoundCloudResponse(400)
            }
        }
        val source = signedInSource(client)

        val result = source.searchDetailed("query")

        assertTrue("a 401 should refresh once and retry", result is SourceSearchResult.Success)
        assertTrue(source.isSignedIn())
    }

    @Test
    fun rejectedRefresh_clearsSessionAndReportsAuthError() {
        val client = FakeHttpClient().apply {
            getHandler = { _, _ -> SoundCloudResponse(401) }
            postFormHandler = { _ -> SoundCloudResponse(400) }
        }
        val source = signedInSource(client)

        val result = source.searchDetailed("query")

        assertTrue(result is SourceSearchResult.Error)
        assertEquals(401, (result as SourceSearchResult.Error).statusCode)
        assertFalse("a failed refresh must return to the disconnected state", source.isSignedIn())
    }

    @Test
    fun expiredSession_isRefreshedBeforeSearching() {
        val client = FakeHttpClient().apply {
            getHandler = { _, headers ->
                if (headers["Authorization"] == "Bearer refreshed-token") SoundCloudResponse(200, body = searchFixture())
                else SoundCloudResponse(401)
            }
            postFormHandler = { SoundCloudResponse(200, body = tokenJson(access = "refreshed-token", refresh = null)) }
        }
        val source = configuredSource(client).apply {
            seedSessionForTesting(
                SpotifyToken(
                    accessToken = "old-token",
                    refreshToken = "refresh-abc",
                    expiresAtEpochMs = System.currentTimeMillis() - 1_000L
                )
            )
        }

        assertTrue(source.searchDetailed("query") is SourceSearchResult.Success)
        assertTrue(client.postForms.any { it["grant_type"] == "refresh_token" })
    }

    // ── PKCE / no secret ──────────────────────────────────────────────────

    @Test
    fun authorizationUrl_usesPkceWithoutClientSecret() {
        val source = configuredSource(FakeHttpClient())

        val url = source.buildAuthorizationUrl("state-123").toString()

        assertTrue(url.contains("accounts.spotify.com/authorize"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state-123"))
        assertTrue(url.contains("client_id=demo_client_id"))
        assertFalse("no client secret may appear in the auth URL", url.contains("client_secret"))
    }

    @Test
    fun exchangeSendsCodeVerifierAndNoClientSecret() {
        val client = FakeHttpClient().apply {
            postFormHandler = { SoundCloudResponse(200, body = tokenJson()) }
        }
        val source = configuredSource(client)
        source.buildAuthorizationUrl("state-xyz")

        val result = source.exchangeAuthorizationCode("auth-code", "state-xyz")

        assertTrue(result.success)
        val form = client.postForms.single()
        assertEquals("authorization_code", form["grant_type"])
        assertTrue("PKCE requires the code_verifier", !form["code_verifier"].isNullOrBlank())
        assertFalse("the client secret must never be sent", form.containsKey("client_secret"))
        assertTrue(source.isSignedIn())
    }

    @Test
    fun exchangeRejectsStateMismatch() {
        val source = configuredSource(FakeHttpClient())
        source.buildAuthorizationUrl("expected-state")

        val result = source.exchangeAuthorizationCode("auth-code", "wrong-state")

        assertFalse(result.success)
        assertTrue(result.requiresReauth)
    }

    // ── Playback / download safety ───────────────────────────────────────

    @Test
    fun spotifyTrackIsNotStreamableOrDownloadable() {
        val client = FakeHttpClient().apply {
            getHandler = { _, _ -> SoundCloudResponse(200, body = searchFixture()) }
        }
        val track = (signedInSource(client).searchDetailed("q") as SourceSearchResult.Success)
            .results.first { it.kind == SourceKind.TRACK }

        val stream = signedInSource(client).stream(track)
        assertNull("Aurora must not invent a Spotify audio URL", stream.uri)
        assertNotNull(stream.error)

        val download = signedInSource(client).checkDownloadAvailability(track)
        assertEquals(DownloadAvailability.BLOCKED, download.availability)
    }

    @Test
    fun spotifyWebUri_convertsCanonicalUri() {
        assertEquals(
            "https://open.spotify.com/track/abc",
            spotifyWebUri(android.net.Uri.parse("spotify:track:abc"))?.toString()
        )
        assertEquals(
            "https://open.spotify.com/album/xyz",
            spotifyWebUri(android.net.Uri.parse("spotify:album:xyz"))?.toString()
        )
        assertNull(spotifyWebUri(null))
    }
}
