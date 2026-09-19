package com.aurora.app

import com.aurora.app.source.DownloadAvailability
import com.aurora.app.source.SoundCloudHttpClient
import com.aurora.app.source.SoundCloudResponse
import com.aurora.app.source.SoundCloudSource
import com.aurora.app.source.SoundCloudToken
import com.aurora.app.source.SourceCapability
import com.aurora.app.source.SourceMetadata
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
import java.util.Base64

/**
 * Unit tests for the official-API SoundCloud integration.
 *
 * Every network interaction goes through a fake [SoundCloudHttpClient], so
 * the token lifecycle (client_credentials / authorization_code / refresh),
 * request headers, response parsing, and the download gate can be asserted
 * without a network. All fixtures use the current OpenAPI response shapes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundCloudSourceTest {

    // ── Fixtures ──────────────────────────────────────────────────────────

    private fun tokenJson(access: String = "access-token", refresh: String? = "refresh-token", expiresIn: Long = 3600): String {
        val sb = StringBuilder("{\"access_token\": \"$access\", \"token_type\": \"bearer\"")
        if (refresh != null) sb.append(", \"refresh_token\": \"$refresh\"")
        sb.append(", \"expires_in\": $expiresIn}")
        return sb.toString()
    }

    private fun collectionSearch(vararg lines: String): String =
        """{"collection": [${lines.joinToString(",")}], "next_href": "https://api.soundcloud.com/tracks?q=x&offset=10"}"""

    private fun playableTrackJson(): String = """
        {"urn": "soundcloud:tracks:123", "title": "Night Shift", "duration": 245000,
         "access": "playable", "streamable": true,
         "downloadable": true, "download_url": "/tracks/123/download",
         "artwork_url": "https://i1.sndcdn.com/artworks-abc-large.jpg",
         "user": {"username": "Leona", "permalink": "leona"},
         "genre": "Electronic"}
    """.trimIndent()

    private fun previewTrackJson(): String = """
        {"urn": "soundcloud:tracks:456", "title": "Snippet", "duration": 30000,
         "access": "preview", "streamable": true,
         "downloadable": false, "download_url": null,
         "user": {"username": "Mason"}, "genre": "Ambient"}
    """.trimIndent()

    private fun blockedTrackJson(): String = """
        {"urn": "soundcloud:tracks:789", "title": "Blocked Track", "duration": 100000,
         "access": "blocked", "streamable": false, "downloadable": false,
         "user": {"username": "Who"}, "genre": "Rock"}
    """.trimIndent()

    private fun configuredSource(client: FakeSoundCloudHttpClient): SoundCloudSource = SoundCloudSource(
        clientIdProvider = { "demo_client_id" },
        clientSecretProvider = { "demo_secret" },
        redirectUriProvider = { "aurora://soundcloud/callback" },
        httpClient = client
    )

    private fun standardTokenHandler(): (String, Map<String, String>, Map<String, String>) -> SoundCloudResponse {
        return { grantType, _, _ ->
            when (grantType) {
                "client_credentials" -> SoundCloudResponse(200, body = tokenJson(access = "app-token", refresh = null))
                else -> SoundCloudResponse(200, body = tokenJson())
            }
        }
    }

    private class FakeSoundCloudHttpClient : SoundCloudHttpClient {
        val getRequests = mutableListOf<Pair<String, Map<String, String>>>()
        val byteRequests = mutableListOf<Pair<String, Map<String, String>>>()
        val posts = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()
        val textResponses = mutableMapOf<String, SoundCloudResponse>()
        val byteResponses = mutableMapOf<String, SoundCloudResponse>()
        var tokenHandler: ((String, Map<String, String>, Map<String, String>) -> SoundCloudResponse)? = null

        private fun match(entries: Map<String, SoundCloudResponse>, url: String): SoundCloudResponse? =
            entries.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { url.contains(it.key) }
                ?.value

        override fun get(url: String, headers: Map<String, String>): SoundCloudResponse {
            getRequests.add(url to headers)
            return match(textResponses, url) ?: SoundCloudResponse(404, body = "{\"error\":\"not found\"}")
        }

        override fun getBytes(url: String, headers: Map<String, String>): SoundCloudResponse {
            byteRequests.add(url to headers)
            return match(byteResponses, url) ?: SoundCloudResponse(404, body = "{\"error\":\"not found\"}")
        }

        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SoundCloudResponse {
            posts.add(Triple(url, form, headers))
            return tokenHandler?.invoke(form["grant_type"] ?: "", form, headers)
                ?: SoundCloudResponse(400, body = "{\"error\":\"no token handler\"}")
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    @Test
    fun search_maps_official_collection_response() {
        val client = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
            textResponses["/tracks"] = SoundCloudResponse(200, body = collectionSearch(playableTrackJson()))
        }
        val source = configuredSource(client)

        val results = source.search("night")

        assertEquals(1, results.size)
        val first = results[0]
        assertEquals("Night Shift", first.title)
        assertEquals("Leona", first.artist)
        assertEquals("Electronic", first.album)
        assertEquals("soundcloud:tracks:123", first.trackId.value)
        assertTrue(first.sourceCapabilities.contains(SourceCapability.STREAM))
        assertTrue(first.sourceCapabilities.contains(SourceCapability.DOWNLOAD))

        // No user session exists, so an app token was requested via client_credentials.
        val tokenPost = client.posts.first()
        assertEquals("client_credentials", tokenPost.second["grant_type"])
    }

    @Test
    fun search_handles_legacy_bare_array_response() {
        val client = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
            textResponses["/tracks"] = SoundCloudResponse(200, body = "[${playableTrackJson()}]")
        }
        val results = configuredSource(client).search("night")

        assertEquals(1, results.size)
        assertEquals("Night Shift", results[0].title)
    }

    @Test
    fun unconfigured_source_reports_blocked_and_empty() {
        val client = FakeSoundCloudHttpClient()
        val source = SoundCloudSource(
            clientIdProvider = { "" },
            clientSecretProvider = { "" },
            redirectUriProvider = { "" },
            httpClient = client
        )
        val track = SourceMetadata(
            trackId = SourceTrackId("soundcloud", "1"),
            title = "Any",
            artist = "Artist",
            album = "Album",
            sourceCapabilities = setOf(SourceCapability.PREVIEW)
        )

        assertEquals(DownloadAvailability.BLOCKED, source.checkDownloadAvailability(track).availability)
        assertTrue(source.search("anything").isEmpty())
        assertNotNull(source.stream(track).error)
        assertFalse(source.download(track, File(System.getProperty("java.io.tmpdir"))).success)
        assertTrue(client.getRequests.isEmpty())
        assertTrue(client.posts.isEmpty())
    }

    @Test
    fun search_detailed_reports_success_empty_error_and_not_configured() {
        val successClient = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
            textResponses["/tracks"] = SoundCloudResponse(200, body = collectionSearch(playableTrackJson()))
        }
        assertTrue(configuredSource(successClient).searchDetailed("night") is com.aurora.app.source.SourceSearchResult.Success)

        val emptyClient = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
            textResponses["/tracks"] = SoundCloudResponse(200, body = collectionSearch())
        }
        assertTrue(configuredSource(emptyClient).searchDetailed("nothing") is com.aurora.app.source.SourceSearchResult.Empty)

        val errorClient = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
            textResponses["/tracks"] = SoundCloudResponse(500, body = "{\"error\":\"boom\"}")
        }
        val error = configuredSource(errorClient).searchDetailed("night")
        assertTrue(error is com.aurora.app.source.SourceSearchResult.Error)
        assertEquals(500, (error as com.aurora.app.source.SourceSearchResult.Error).statusCode)

        val notConfigured = SoundCloudSource(
            clientIdProvider = { "" },
            clientSecretProvider = { "" },
            redirectUriProvider = { "" },
            httpClient = FakeSoundCloudHttpClient()
        ).searchDetailed("night")
        assertTrue(notConfigured is com.aurora.app.source.SourceSearchResult.NotConfigured)
    }

    // ── Access states: playable / preview / blocked ───────────────────────

    @Test
    fun playable_track_streams_via_hls_endpoints() {
        val client = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
            textResponses["/tracks"] = SoundCloudResponse(200, body = collectionSearch(playableTrackJson()))
            textResponses["/streams"] = SoundCloudResponse(
                200,
                body = """{"hls_aac_160_url": "https://cf-hls.sndcdn.com/abc/playlist.m3u8?sig=x", "hls_mp3_128_url": "https://cf-hls.sndcdn.com/abc/playlist.mp3.m3u8?sig=y"}"""
            )
        }
        val source = configuredSource(client)

        val track = source.search("night").first()
        val result = source.stream(track)

        assertNotNull(result.uri)
        assertTrue(result.uri.toString().contains("playlist"))
        assertFalse(result.isPreview)
        // The stream request carried the resolved access token.
        assertTrue(client.getRequests.any { it.first.contains("/streams") })
        assertEquals("OAuth app-token", client.getRequests.last().second["Authorization"])
    }

    @Test
    fun preview_track_resolves_preview_stream() {
        val client = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
            textResponses["/tracks"] = SoundCloudResponse(200, body = collectionSearch(previewTrackJson()))
            textResponses["/streams"] = SoundCloudResponse(
                200,
                body = """{"preview_mp3_128_url": "https://cf-preview.sndcdn.com/preview.mp3?sig=abc"}"""
            )
        }
        val source = configuredSource(client)

        val track = source.search("preview").first()
        assertTrue(track.sourceCapabilities.contains(SourceCapability.PREVIEW))
        assertFalse(track.sourceCapabilities.contains(SourceCapability.STREAM))
        assertFalse(track.sourceCapabilities.contains(SourceCapability.DOWNLOAD))

        val result = source.stream(track)
        assertNotNull(result.uri)
        assertTrue(result.uri.toString().contains("preview"))
        assertTrue(result.isPreview)
    }

    @Test
    fun blocked_track_is_blocked_and_unstreamable() {
        val client = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
            textResponses["/tracks"] = SoundCloudResponse(200, body = collectionSearch(blockedTrackJson()))
        }
        val source = configuredSource(client)

        val track = source.search("blocked").first()
        assertTrue(track.sourceCapabilities.contains(SourceCapability.BLOCKED))
        assertFalse(track.sourceCapabilities.contains(SourceCapability.STREAM))
        assertFalse(track.sourceCapabilities.contains(SourceCapability.DOWNLOAD))

        val streamResult = source.stream(track)
        assertNull(streamResult.uri)
        assertEquals(DownloadAvailability.BLOCKED, source.checkDownloadAvailability(track).availability)
    }

    // ── Downloads are strictly gated by the API ───────────────────────────

    @Test
    fun downloadable_false_with_download_url_does_not_enable_download() {
        val gated = """
            {"urn": "soundcloud:tracks:321", "title": "Gate Kept", "access": "playable",
             "downloadable": false, "download_url": "/tracks/321/download",
             "user": {"username": "Artist"}, "genre": "Pop"}
        """.trimIndent()
        val client = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
            textResponses["/tracks"] = SoundCloudResponse(200, body = collectionSearch(gated))
        }
        val source = configuredSource(client)

        val track = source.search("gate").first()
        assertFalse(track.sourceCapabilities.contains(SourceCapability.DOWNLOAD))

        val result = source.download(track, File(System.getProperty("java.io.tmpdir")))
        assertFalse(result.success)
        assertTrue(client.byteRequests.isEmpty())
    }

    @Test
    fun download_uses_official_gate_and_fetches_authorized_bytes() {
        val client = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
            textResponses["/tracks"] = SoundCloudResponse(200, body = collectionSearch(playableTrackJson()))
            byteResponses["/tracks/123/download"] = SoundCloudResponse(
                200,
                bytes = "fake-audio-mp3".toByteArray(),
                contentType = "audio/mpeg"
            )
        }
        val source = configuredSource(client)

        val track = source.search("night").first()
        assertTrue(track.sourceCapabilities.contains(SourceCapability.DOWNLOAD))

        val targetDir = File(System.getProperty("java.io.tmpdir"), "sc-test-${System.nanoTime()}").apply { mkdirs() }
        val result = source.download(track, targetDir)

        assertTrue(result.success)
        assertNotNull(result.localPath)
        assertTrue(File(result.localPath).exists())

        // The download bytes were fetched with the OAuth header, and the
        // official `downloadable`/`download_url` fields were re-checked first.
        val downloadRequest = client.byteRequests.firstOrNull { it.first.contains("/tracks/123/download") }
        assertNotNull(downloadRequest)
        assertEquals("OAuth app-token", downloadRequest?.second?.get("Authorization"))
        assertTrue(client.getRequests.count { it.first.contains("/tracks") && !it.first.contains("streams") } >= 2)
    }

    // ── OAuth: authorization code + PKCE ──────────────────────────────────

    @Test
    fun authorization_url_uses_pkce_and_popup_display() {
        val source = configuredSource(FakeSoundCloudHttpClient())

        val url = source.buildAuthorizationUrl("state-123").toString()

        assertTrue(url.startsWith("https://secure.soundcloud.com/authorize"))
        assertTrue(url.contains("client_id=demo_client_id"))
        assertTrue(url.contains("redirect_uri=aurora%3A%2F%2Fsoundcloud%2Fcallback"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("state=state-123"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge="))
        assertTrue(url.contains("display=popup"))
    }

    @Test
    fun exchange_authorization_code_posts_pkce_and_saves_session() {
        val client = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
        }
        val source = configuredSource(client)

        source.buildAuthorizationUrl("state-abc")
        val result = source.exchangeAuthorizationCode("the-auth-code", "state-abc")

        assertTrue(result.message, result.success)
        val exchange = client.posts.first { it.second["grant_type"] == "authorization_code" }
        assertEquals("the-auth-code", exchange.second["code"])
        assertEquals("demo_client_id", exchange.second["client_id"])
        assertEquals("demo_secret", exchange.second["client_secret"])
        assertEquals("aurora://soundcloud/callback", exchange.second["redirect_uri"])
        val verifier = exchange.second["code_verifier"]
        assertNotNull("PKCE verifier was not posted", verifier)
        assertTrue("PKCE verifier must be 43-128 chars (RFC 7636), was ${verifier?.length}", verifier!!.length in 43..128)
        assertTrue(source.isSignedIn())
    }

    @Test
    fun state_mismatch_rejects_the_exchange() {
        val client = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
        }
        val source = configuredSource(client)

        source.buildAuthorizationUrl("state-abc")
        val result = source.exchangeAuthorizationCode("the-auth-code", "state-OTHER")

        assertFalse(result.success)
        assertFalse(source.isSignedIn())
    }

    @Test
    fun expired_session_refreshes_via_refresh_token_grant() {
        val client = FakeSoundCloudHttpClient().apply {
            textResponses["/tracks"] = SoundCloudResponse(200, body = collectionSearch(playableTrackJson()))
            tokenHandler = { grantType, _, _ ->
                when (grantType) {
                    "refresh_token" -> SoundCloudResponse(200, body = tokenJson(access = "refreshed-token", refresh = "second-refresh", expiresIn = 3600))
                    else -> SoundCloudResponse(200, body = tokenJson())
                }
            }
        }
        val source = configuredSource(client)

        // A user session whose access token already expired 5 minutes ago.
        source.seedSessionForTesting(
            SoundCloudToken(
                accessToken = "initial-token",
                refreshToken = "first-refresh",
                expiresAtEpochMs = System.currentTimeMillis() - 300_000L
            )
        )
        assertTrue(source.isSignedIn())

        val results = source.search("night")
        assertEquals(1, results.size)

        val refreshPost = client.posts.first { it.second["grant_type"] == "refresh_token" }
        assertEquals("first-refresh", refreshPost.second["refresh_token"])
        assertEquals("OAuth refreshed-token", client.getRequests.first().second["Authorization"])
    }

    @Test
    fun client_credentials_uses_http_basic_when_no_session() {
        val client = FakeSoundCloudHttpClient().apply {
            tokenHandler = standardTokenHandler()
            textResponses["/tracks"] = SoundCloudResponse(200, body = collectionSearch(playableTrackJson()))
        }
        val source = configuredSource(client)

        source.search("night")

        val tokenPost = client.posts.first { it.second["grant_type"] == "client_credentials" }
        val expectedBasic = "Basic " + Base64.getEncoder().encodeToString("demo_client_id:demo_secret".toByteArray())
        assertEquals(expectedBasic, tokenPost.third["Authorization"])
    }

    @Test
    fun unauthorized_response_clears_cached_tokens_and_retries_once() {
        val client = FakeSoundCloudHttpClient().apply {
            tokenHandler = { grantType, _, _ ->
                when (grantType) {
                    "client_credentials" -> SoundCloudResponse(200, body = tokenJson(access = "app-token-${posts.size}", refresh = null))
                    else -> SoundCloudResponse(200, body = tokenJson())
                }
            }
        }
        val source = SoundCloudSource(
            clientIdProvider = { "demo_client_id" },
            clientSecretProvider = { "demo_secret" },
            redirectUriProvider = { "aurora://soundcloud/callback" },
            httpClient = object : SoundCloudHttpClient {
                private var firstTrackRequest = true

                override fun get(url: String, headers: Map<String, String>): SoundCloudResponse {
                    val isTrackRequest = url.contains("/tracks") && !url.contains("streams")
                    if (isTrackRequest && firstTrackRequest) {
                        firstTrackRequest = false
                        return SoundCloudResponse(401, body = "{\"error\":\"unauthorized\"}")
                    }
                    if (isTrackRequest) {
                        return SoundCloudResponse(200, body = collectionSearch(playableTrackJson()))
                    }
                    return client.get(url, headers)
                }

                override fun getBytes(url: String, headers: Map<String, String>): SoundCloudResponse = client.getBytes(url, headers)
                override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SoundCloudResponse = client.postForm(url, form, headers)
            }
        )

        val results = source.search("night")

        assertEquals(1, results.size)
        // The rejected app token was dropped and a fresh one requested for the retry.
        assertEquals(2, client.posts.count { it.second["grant_type"] == "client_credentials" })
    }
}