package com.aurora.app.source

import android.content.Context
import android.net.Uri
import com.aurora.app.BuildConfig
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Spotify integration built against the official Spotify Web API.
 *
 * Reference material (the only endpoints used here):
 *  - Authorization Code + PKCE: https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
 *  - Search:                     https://developer.spotify.com/documentation/web-api/reference/search
 *  - Get Track:                  https://developer.spotify.com/documentation/web-api/reference/get-track
 *
 * There is deliberately **no client secret**: Spotify's Android flow is
 * Authorization Code + PKCE. Only the (public) Client ID is needed, and it is
 * read from build configuration or user settings — never hardcoded.
 *
 * Spotify audio is DRM-protected and cannot be streamed or downloaded by
 * Aurora. Tracks are therefore catalog items with [SourceCapability.EXTERNAL_PLAYBACK]:
 * the UI offers to open them in the Spotify app, and Aurora never claims they
 * are locally playable.
 */
internal const val SPOTIFY_API_BASE = "https://api.spotify.com/v1"
internal const val SPOTIFY_ACCOUNTS_BASE = "https://accounts.spotify.com"
internal const val SPOTIFY_AUTHORIZE_URL = "$SPOTIFY_ACCOUNTS_BASE/authorize"
internal const val SPOTIFY_TOKEN_URL = "$SPOTIFY_ACCOUNTS_BASE/api/token"
internal const val SPOTIFY_DEFAULT_REDIRECT_URI = "aurora://spotify/callback"
private const val SPOTIFY_SCOPE = "user-read-private"

/** User-supplied Spotify app configuration. No secret is ever part of this. */
data class SpotifyConfig(
    val clientId: String,
    val redirectUri: String = SPOTIFY_DEFAULT_REDIRECT_URI
) {
    override fun toString(): String =
        "SpotifyConfig(clientId=${clientId.take(4)}···, redirectUri=$redirectUri)"
}

/** OAuth token bundle. Redacted so tokens never leak into logs. */
data class SpotifyToken(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long = 0L,
    val tokenType: String = "Bearer",
    val scope: String? = null
) {
    override fun toString(): String = buildString {
        append("SpotifyToken(accessToken=")
        append(if (accessToken.isNotBlank()) "···" else "<blank>")
        append(", refreshToken=")
        append(if (refreshToken != null) "···" else "null")
        append(", expiresAtEpochMs=").append(expiresAtEpochMs)
        append(")")
    }
}

data class SpotifyAuthResult(
    val success: Boolean,
    val message: String = "",
    val requiresReauth: Boolean = false
)

/**
 * App-private, encrypted storage for the Spotify session and PKCE material.
 * Reuses the same AES/GCM + Android Keystore protection as SoundCloud so no
 * token or client id is ever stored in plaintext.
 */
class SpotifyTokenStore(private val context: Context?) {
    private val prefs = context?.getSharedPreferences("aurora_spotify_repo", Context.MODE_PRIVATE)
    private val pkcePrefs = context?.getSharedPreferences("aurora_spotify_pkce", Context.MODE_PRIVATE)
    private val memory = mutableMapOf<String, String>()

    fun saveSession(token: SpotifyToken) = putPref(SESSION_KEY, tokenToJson(token))
    fun loadSession(): SpotifyToken? = getPref(SESSION_KEY)?.let { tokenFromJson(it) }
    fun hasSession(): Boolean = loadSession() != null
    fun clearSession() = removePref(SESSION_KEY)

    fun saveConfig(config: SpotifyConfig) = putPref(CONFIG_KEY, configToJson(config))
    fun loadConfig(): SpotifyConfig? = getPref(CONFIG_KEY)?.let { configFromJson(it) }
    fun clearConfig() = removePref(CONFIG_KEY)

    fun savePkce(codeVerifier: String, state: String?) {
        pkcePrefs?.edit()?.apply {
            putString(VERIFIER_KEY, codeVerifier)
            if (state == null) remove(STATE_KEY) else putString(STATE_KEY, state)
        }?.apply()
        memory[VERIFIER_KEY] = codeVerifier
        if (state == null) memory.remove(STATE_KEY) else memory[STATE_KEY] = state
    }

    fun loadPkceVerifier(): String? = pkcePrefs?.getString(VERIFIER_KEY, null) ?: memory[VERIFIER_KEY]

    fun loadPkceState(): String? = pkcePrefs?.getString(STATE_KEY, null) ?: memory[STATE_KEY]

    fun clearPkce() {
        pkcePrefs?.edit()?.remove(VERIFIER_KEY)?.remove(STATE_KEY)?.apply()
        memory.remove(VERIFIER_KEY)
        memory.remove(STATE_KEY)
    }

    private fun putPref(key: String, plain: String) {
        val encrypted = SoundCloudCipher.encrypt(context, plain)
        memory[key] = encrypted
        prefs?.edit()?.putString(key, encrypted)?.apply()
    }

    private fun getPref(key: String): String? {
        val encrypted = prefs?.getString(key, null) ?: memory[key] ?: return null
        return SoundCloudCipher.decrypt(context, encrypted)
    }

    private fun removePref(key: String) {
        memory.remove(key)
        prefs?.edit()?.remove(key)?.apply()
    }

    private companion object {
        const val SESSION_KEY = "spotify_oauth_token"
        const val CONFIG_KEY = "spotify_config"
        const val VERIFIER_KEY = "spotify_pkce_verifier"
        const val STATE_KEY = "spotify_pkce_state"
    }
}

/** Converts the canonical `spotify:<kind>:<id>` URI to a universal web link. */
internal fun spotifyWebUri(externalUri: Uri?): Uri? {
    val raw = externalUri?.toString() ?: return null
    val parts = raw.removePrefix("spotify:").split(":")
    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
    val kind = if (parts[0] == "track" || parts[0] == "album" || parts[0] == "artist") {
        parts[0]
    } else {
        "track"
    }
    return Uri.parse("https://open.spotify.com/$kind/${parts[1]}")
}

class SpotifySource(
    private val context: Context? = null,
    private val clientIdProvider: () -> String = { BuildConfig.SPOTIFY_CLIENT_ID },
    private val redirectUriProvider: () -> String = { BuildConfig.SPOTIFY_REDIRECT_URI },
    private val httpClient: SoundCloudHttpClient = DefaultSoundCloudHttpClient()
) : MusicSource {

    override val sourceId: String = "spotify"
    override val capabilities: Set<SourceCapability> = setOf(SourceCapability.EXTERNAL_PLAYBACK)

    private val tokenStore = SpotifyTokenStore(context)

    // ── Configuration ─────────────────────────────────────────────────────

    private fun config(): SpotifyConfig? {
        val saved = tokenStore.loadConfig()
        val clientId = (saved?.clientId ?: clientIdProvider()).trim()
        val redirectUri = (saved?.redirectUri ?: redirectUriProvider()).trim()
            .ifBlank { SPOTIFY_DEFAULT_REDIRECT_URI }
        return if (clientId.isNotBlank()) SpotifyConfig(clientId, redirectUri) else null
    }

    fun isConfigured(): Boolean = config() != null

    fun isSignedIn(): Boolean = tokenStore.hasSession()

    fun savedConfiguration(): SpotifyConfig? = tokenStore.loadConfig()

    fun saveConfiguration(clientId: String, redirectUri: String = SPOTIFY_DEFAULT_REDIRECT_URI) {
        tokenStore.saveConfig(
            SpotifyConfig(clientId.trim(), redirectUri.trim().ifBlank { SPOTIFY_DEFAULT_REDIRECT_URI })
        )
    }

    fun clearConfiguration() {
        tokenStore.clearConfig()
        clearAuthentication()
    }

    /** Local sign-out. Local Aurora music, downloads and the library are untouched. */
    fun clearAuthentication() {
        tokenStore.clearSession()
        tokenStore.clearPkce()
    }

    /** Test-only hook to seed a session without the network. */
    internal fun seedSessionForTesting(token: SpotifyToken) {
        tokenStore.saveSession(token)
    }

    // ── OAuth: authorization code + PKCE (no client secret) ───────────────

    fun buildAuthorizationUrl(state: String = randomNonce()): Uri {
        val cfg = config() ?: throw IllegalStateException("Spotify is not configured")
        val codeVerifier = randomNonce()
        tokenStore.savePkce(codeVerifier, state)
        return Uri.parse(SPOTIFY_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", cfg.clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", cfg.redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", sha256Base64Url(codeVerifier))
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SPOTIFY_SCOPE)
            .build()
    }

    /**
     * Exchanges the authorization [code] for a token using the stored PKCE
     * verifier. The client secret is intentionally never sent. Runs network I/O;
     * call off the main thread.
     */
    fun exchangeAuthorizationCode(code: String, state: String?): SpotifyAuthResult {
        val cfg = config() ?: return SpotifyAuthResult(false, "Spotify is not configured")
        val storedState = tokenStore.loadPkceState()
        if (storedState != null && state != storedState) {
            tokenStore.clearPkce()
            return SpotifyAuthResult(false, "Spotify sign-in state mismatch; please try again", requiresReauth = true)
        }
        val verifier = tokenStore.loadPkceVerifier()
        if (verifier.isNullOrBlank()) {
            return SpotifyAuthResult(false, "No pending Spotify sign-in; please try again")
        }
        val response = httpClient.postForm(
            SPOTIFY_TOKEN_URL,
            linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to cfg.redirectUri,
                "client_id" to cfg.clientId,
                "code_verifier" to verifier
            ),
            emptyMap()
        )
        tokenStore.clearPkce()
        val token = parseTokenResponse(response)
            ?: return SpotifyAuthResult(false, "Spotify rejected the authorization code; please try again")
        tokenStore.saveSession(token)
        return SpotifyAuthResult(true, "Signed in to Spotify")
    }

    // ── Token lifecycle ───────────────────────────────────────────────────

    private fun bearer(token: SpotifyToken): Map<String, String> =
        mapOf("Authorization" to "Bearer ${token.accessToken}")

    private fun parseTokenResponse(response: SoundCloudResponse): SpotifyToken? {
        if (response.statusCode !in 200..299) return null
        val body = response.body ?: return null
        val accessToken = extractJsonString(body, "access_token")
        if (accessToken.isBlank()) return null
        val refreshToken = extractJsonString(body, "refresh_token")
        val expiresIn = extractJsonLong(body, "expires_in").coerceAtLeast(0L)
        return SpotifyToken(
            accessToken = accessToken,
            refreshToken = refreshToken.ifBlank { null },
            expiresAtEpochMs = if (expiresIn > 0L) System.currentTimeMillis() + expiresIn * 1000L else 0L,
            tokenType = extractJsonString(body, "token_type").ifBlank { "Bearer" },
            scope = extractJsonString(body, "scope").ifBlank { null }
        )
    }

    /** Returns a usable session token, refreshing it when near expiry. */
    private fun resolveToken(): SpotifyToken? {
        val now = System.currentTimeMillis()
        val cfg = config() ?: return null
        val session = tokenStore.loadSession() ?: return null
        if (session.expiresAtEpochMs <= 0L || session.expiresAtEpochMs > now + 60_000L) return session
        val refreshed = refreshTokenRequest(session.refreshToken, cfg)
        if (refreshed != null) {
            tokenStore.saveSession(refreshed)
            return refreshed
        }
        // The refresh token is no longer valid: return to the disconnected state.
        tokenStore.clearSession()
        return null
    }

    private fun refreshTokenRequest(refreshToken: String?, cfg: SpotifyConfig): SpotifyToken? {
        if (refreshToken.isNullOrBlank()) return null
        val response = httpClient.postForm(
            SPOTIFY_TOKEN_URL,
            linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to cfg.clientId
            ),
            emptyMap()
        )
        val parsed = parseTokenResponse(response) ?: return null
        // Spotify may omit a new refresh token; keep the existing one.
        return if (parsed.refreshToken == null) parsed.copy(refreshToken = refreshToken) else parsed
    }

    // ── MusicSource: search ───────────────────────────────────────────────

    override fun search(query: String): List<SourceMetadata> =
        when (val outcome = searchDetailed(query)) {
            is SourceSearchResult.Success -> outcome.results
            else -> emptyList()
        }

    override fun searchDetailed(query: String): SourceSearchResult {
        if (query.isBlank()) return SourceSearchResult.Empty()
        if (!isConfigured()) {
            return SourceSearchResult.NotConfigured(
                "Spotify is not configured in this build, so its catalog cannot be searched."
            )
        }
        val cfg = config()!!
        var token = resolveToken()
            ?: return SourceSearchResult.Error(
                "Sign in to Spotify in Settings to search the Spotify catalog.",
                401
            )

        val url = apiUrl(
            "/search",
            linkedMapOf(
                "q" to query.trim(),
                "type" to "track,album,artist",
                "limit" to "10"
            )
        )
        var response = httpClient.get(url, bearer(token))
        if (response.statusCode == 401) {
            // Access token rejected: refresh exactly once before giving up.
            val refreshed = refreshTokenRequest(token.refreshToken, cfg)
            if (refreshed != null) {
                tokenStore.saveSession(refreshed)
                response = httpClient.get(url, bearer(refreshed))
            } else {
                tokenStore.clearSession()
            }
        }

        return when {
            response.statusCode == -1 ->
                SourceSearchResult.Error("No network connection or Spotify is unreachable.")
            response.statusCode == 401 || response.statusCode == 403 ->
                SourceSearchResult.Error(
                    "Spotify authorization expired or was rejected. Reconnect in Settings.",
                    response.statusCode
                )
            response.statusCode == 429 ->
                SourceSearchResult.Error("Spotify rate limit reached. Please try again later.", 429)
            response.statusCode !in 200..299 ->
                SourceSearchResult.Error("Spotify returned HTTP ${response.statusCode}.", response.statusCode)
            response.body.isNullOrBlank() ->
                SourceSearchResult.Error("Spotify returned an empty response.")
            else -> {
                val items = try {
                    parseSearch(response.body!!)
                } catch (_: Exception) {
                    null
                }
                when {
                    items == null ->
                        SourceSearchResult.Error("Spotify returned a response that could not be read.")
                    items.isEmpty() ->
                        SourceSearchResult.Empty("No Spotify results for \"${query.trim()}\"")
                    else -> SourceSearchResult.Success(items)
                }
            }
        }
    }

    override fun getTrack(id: SourceTrackId): SourceMetadata? {
        if (id.source != sourceId || id.value.isBlank() || !isConfigured()) return null
        val token = resolveToken() ?: return null
        val response = httpClient.get("$SPOTIFY_API_BASE/tracks/${id.value}", bearer(token))
        if (response.statusCode !in 200..299) return null
        val body = response.body ?: return null
        return try {
            mapItem(body, SourceKind.TRACK)
        } catch (_: Exception) {
            null
        }
    }

    // ── Response parsing ──────────────────────────────────────────────────

    /**
     * Parses a Spotify `search` response. Returns null when the payload is not a
     * recognizable search object, so the caller can report malformed data
     * instead of silently showing "no results".
     */
    private fun parseSearch(body: String): List<SourceMetadata>? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        val hasTracks = trimmed.contains("\"tracks\"")
        val hasAlbums = trimmed.contains("\"albums\"")
        val hasArtists = trimmed.contains("\"artists\"")
        if (!hasTracks && !hasAlbums && !hasArtists) return null

        val result = mutableListOf<SourceMetadata>()
        if (hasTracks) result += parseCollection(trimmed, "tracks", SourceKind.TRACK)
        if (hasAlbums) result += parseCollection(trimmed, "albums", SourceKind.ALBUM)
        if (hasArtists) result += parseCollection(trimmed, "artists", SourceKind.ARTIST)
        return result
    }

    private fun parseCollection(body: String, container: String, kind: SourceKind): List<SourceMetadata> {
        val containerObject = JsonScan.topLevelValue(body, container) ?: return emptyList()
        return JsonScan.topLevelArrayObjects(containerObject, "items")
            .mapNotNull { mapItem(it, kind) }
    }

    private fun mapItem(item: String, kind: SourceKind): SourceMetadata? {
        val id = JsonScan.topLevelString(item, "id")
        if (id.isBlank()) return null
        val title = JsonScan.topLevelString(item, "name")
        return when (kind) {
            SourceKind.TRACK -> {
                val albumObject = JsonScan.topLevelValue(item, "album") ?: ""
                SourceMetadata(
                    trackId = SourceTrackId(sourceId, id),
                    title = title.ifBlank { "Unknown track" },
                    artist = artistsFrom(item).ifBlank { "Unknown artist" },
                    album = if (albumObject.isNotBlank()) JsonScan.topLevelString(albumObject, "name") else "",
                    durationMs = JsonScan.topLevelLong(item, "duration_ms").coerceAtLeast(0L),
                    artworkUri = if (albumObject.isNotBlank()) artworkFrom(albumObject) else null,
                    sourceCapabilities = setOf(SourceCapability.EXTERNAL_PLAYBACK),
                    kind = SourceKind.TRACK,
                    externalUri = spotifyUri(SourceKind.TRACK, id)
                )
            }
            SourceKind.ALBUM -> SourceMetadata(
                trackId = SourceTrackId(sourceId, id),
                title = title.ifBlank { "Unknown album" },
                artist = artistsFrom(item),
                album = "",
                durationMs = 0L,
                artworkUri = artworkFrom(item),
                sourceCapabilities = setOf(SourceCapability.EXTERNAL_PLAYBACK),
                kind = SourceKind.ALBUM,
                externalUri = spotifyUri(SourceKind.ALBUM, id)
            )
            SourceKind.ARTIST -> SourceMetadata(
                trackId = SourceTrackId(sourceId, id),
                title = title.ifBlank { "Unknown artist" },
                artist = "",
                album = "",
                durationMs = 0L,
                artworkUri = artworkFrom(item),
                sourceCapabilities = setOf(SourceCapability.EXTERNAL_PLAYBACK),
                kind = SourceKind.ARTIST,
                externalUri = spotifyUri(SourceKind.ARTIST, id)
            )
        }
    }

    private fun artistsFrom(item: String): String =
        JsonScan.topLevelArrayObjects(item, "artists")
            .map { JsonScan.topLevelString(it, "name") }
            .filter { it.isNotBlank() }
            .joinToString(", ")

    /** Largest available image, so a remote cover is crisp on the full player. */
    private fun artworkFrom(container: String): Uri? {
        val images = JsonScan.topLevelArrayObjects(container, "images")
        if (images.isEmpty()) return null
        val best = images.maxByOrNull { JsonScan.topLevelLong(it, "height") } ?: images.first()
        return safeUri(JsonScan.topLevelString(best, "url"))
    }

    private fun spotifyUri(kind: SourceKind, id: String): Uri {
        val type = when (kind) {
            SourceKind.TRACK -> "track"
            SourceKind.ALBUM -> "album"
            SourceKind.ARTIST -> "artist"
        }
        return Uri.parse("spotify:$type:$id")
    }

    private fun apiUrl(path: String, queryParams: Map<String, String> = emptyMap()): String {
        val base = "$SPOTIFY_API_BASE$path"
        if (queryParams.isEmpty()) return base
        val joined = queryParams.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$base?$joined"
    }

    private fun safeUri(rawUrl: String?): Uri? = try {
        rawUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Base64Url(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ── MusicSource: external playback only ───────────────────────────────

    override fun stream(track: SourceMetadata): StreamResult = StreamResult(
        uri = null,
        metadata = track,
        isPreview = false,
        error = "Spotify tracks play in the Spotify app. Aurora does not stream Spotify audio."
    )

    override fun checkDownloadAvailability(track: SourceMetadata): DownloadCapability =
        DownloadCapability(
            DownloadAvailability.BLOCKED,
            "Spotify does not allow downloading through Aurora."
        )

    override fun download(track: SourceMetadata, targetDir: File): DownloadResult =
        DownloadResult(success = false, error = "Spotify does not allow downloading through Aurora.")
}

private fun tokenToJson(token: SpotifyToken): String = buildString {
    append("{\"access_token\":\"").append(escapeJson(token.accessToken)).append("\"")
    append(",\"refresh_token\":\"").append(escapeJson(token.refreshToken ?: "")).append("\"")
    append(",\"expires_at_ms\":").append(token.expiresAtEpochMs)
    append(",\"token_type\":\"").append(escapeJson(token.tokenType)).append("\"")
    append(",\"scope\":\"").append(escapeJson(token.scope ?: "")).append("\"}")
}

private fun tokenFromJson(raw: String): SpotifyToken = SpotifyToken(
    accessToken = extractJsonString(raw, "access_token"),
    refreshToken = extractJsonString(raw, "refresh_token").ifBlank { null },
    expiresAtEpochMs = extractJsonLong(raw, "expires_at_ms"),
    tokenType = extractJsonString(raw, "token_type").ifBlank { "Bearer" },
    scope = extractJsonString(raw, "scope").ifBlank { null }
)

private fun configToJson(config: SpotifyConfig): String = buildString {
    append("{\"client_id\":\"").append(escapeJson(config.clientId)).append("\"")
    append(",\"redirect_uri\":\"").append(escapeJson(config.redirectUri)).append("\"}")
}

private fun configFromJson(raw: String): SpotifyConfig = SpotifyConfig(
    clientId = extractJsonString(raw, "client_id"),
    redirectUri = extractJsonString(raw, "redirect_uri").ifBlank { SPOTIFY_DEFAULT_REDIRECT_URI }
)
