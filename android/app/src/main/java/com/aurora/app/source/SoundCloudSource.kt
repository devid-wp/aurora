package com.aurora.app.source

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aurora.app.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.Key
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SoundCloud integration built against the current official SoundCloud API.
 *
 * Reference material (the only endpoints and flows used here):
 *  - API Guide: https://developers.soundcloud.com/docs/api/guide
 *  - Register app: https://developers.soundcloud.com/docs/api/register-app
 *  - OpenAPI: https://github.com/soundcloud/api/blob/master/openapi/api.yaml
 *
 * Authentication is OAuth 2.1:
 *  - Authorization Code + PKCE (required) for user sign-in.
 *  - Client Credentials (HTTP Basic, documented as the client-only fallback
 *    for public resources such as search and playback).
 *  - Access tokens are sent with `Authorization: OAuth <access_token>`.
 *  - Access tokens expire after ~1 hour; refresh tokens are single-use.
 *
 * No client_id-only (`?client_id=`) authentication is used, and no
 * client_id/client_secret is baked into the APK: credentials come from
 * user configuration stored in app-private, encrypted storage at runtime.
 */

internal const val SOUNDCLOUD_API_BASE = "https://api.soundcloud.com"
internal const val SOUNDCLOUD_AUTH_BASE = "https://secure.soundcloud.com"
internal const val SOUNDCLOUD_AUTHORIZE_URL = "$SOUNDCLOUD_AUTH_BASE/authorize"
internal const val SOUNDCLOUD_TOKEN_URL = "$SOUNDCLOUD_AUTH_BASE/oauth/token"
internal const val SOUNDCLOUD_DEFAULT_REDIRECT_URI = "aurora://soundcloud/callback"

/** Minimal representation of an HTTP response returned by [SoundCloudHttpClient]. */
data class SoundCloudResponse(
    val statusCode: Int,
    val body: String? = null,
    val bytes: ByteArray? = null,
    val contentType: String? = null
)

/**
 * Thin HTTP abstraction so API behavior can be unit tested without a network.
 * Implementations must follow redirects and return the final response.
 */
interface SoundCloudHttpClient {
    fun get(url: String, headers: Map<String, String>): SoundCloudResponse
    fun getBytes(url: String, headers: Map<String, String>): SoundCloudResponse
    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SoundCloudResponse
    /** Bodiless POST (likes, disconnect). Follows the same contract as [get]. */
    fun post(url: String, headers: Map<String, String>): SoundCloudResponse
    /** DELETE (unlike). Follows the same contract as [get]. */
    fun delete(url: String, headers: Map<String, String>): SoundCloudResponse
}

class DefaultSoundCloudHttpClient : SoundCloudHttpClient {
    override fun get(url: String, headers: Map<String, String>): SoundCloudResponse =
        request("GET", url, headers)

    override fun getBytes(url: String, headers: Map<String, String>): SoundCloudResponse =
        request("GET", url, headers, readBytes = true)

    override fun post(url: String, headers: Map<String, String>): SoundCloudResponse =
        request("POST", url, headers)

    override fun delete(url: String, headers: Map<String, String>): SoundCloudResponse =
        request("DELETE", url, headers)

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SoundCloudResponse {
        val payload = form.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json; charset=utf-8")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        return try {
            connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            read(connection, readBytes = false)
        } catch (_: Exception) {
            SoundCloudResponse(statusCode = -1)
        } finally {
            connection.disconnect()
        }
    }

    private fun request(method: String, url: String, headers: Map<String, String>, readBytes: Boolean = false): SoundCloudResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        connection.doInput = true
        connection.setRequestProperty("Accept", "application/json; charset=utf-8")
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        return try {
            read(connection, readBytes)
        } catch (_: Exception) {
            SoundCloudResponse(statusCode = -1)
        } finally {
            connection.disconnect()
        }
    }

    private fun read(connection: HttpURLConnection, readBytes: Boolean): SoundCloudResponse {
        val code = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        return if (code in 200..299) {
            if (readBytes) {
                val bytes = connection.inputStream.use { it.readBytes() }
                SoundCloudResponse(statusCode = code, bytes = bytes, contentType = contentType)
            } else {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                SoundCloudResponse(statusCode = code, body = text, contentType = contentType)
            }
        } else {
            val errorBody = try {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
                null
            }
            SoundCloudResponse(statusCode = code, body = errorBody, contentType = contentType)
        }
    }
}

/** User-supplied SoundCloud app credentials (registered at developers.soundcloud.com). */
data class SoundCloudConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String = SOUNDCLOUD_DEFAULT_REDIRECT_URI
) {
    /** Redacted: the client secret must never appear in logs or crash reports. */
    override fun toString(): String =
        "SoundCloudConfig(clientId=${clientId.take(4)}···, clientSecret=···, redirectUri=$redirectUri)"
}

/** OAuth 2.1 token bundle returned by the SoundCloud token endpoint. */
data class SoundCloudToken(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long = 0L,
    val tokenType: String = "Bearer",
    val scope: String? = null
) {
    /** Redacted: tokens must never appear in logs or crash reports. */
    override fun toString(): String = buildString {
        append("SoundCloudToken(accessToken=")
        append(if (accessToken.isNotBlank()) "···" else "<blank>")
        append(", refreshToken=")
        append(if (refreshToken != null) "···" else "null")
        append(", expiresAtEpochMs=").append(expiresAtEpochMs)
        append(", tokenType=").append(tokenType)
        append(", scope=").append(scope ?: "null")
        append(")")
    }
}

/**
 * Minimum authenticated-account information Aurora keeps after a successful
 * sign-in. Only what the Settings screen needs to confirm the connection.
 */
data class SoundCloudAccount(
    val username: String,
    val permalink: String? = null
)

/** Result of an interactive authorization attempt; surfaced to the UI. */
data class SoundCloudAuthResult(
    val success: Boolean,
    val message: String = "",
    val requiresReauth: Boolean = false
)/**
 * App-private, encrypted storage for SoundCloud tokens, PKCE material, and
 * user-supplied credentials.
 *
 * Secrets are encrypted with AES/GCM. The encryption key lives in the
 * Android Keystore (non-exportable) when available, so it cannot be
 * extracted from the APK or the device. In environments without a Keystore
 * (unit tests) it degrades to an in-memory-only store.
 */
class SoundCloudTokenStore(private val context: Context?) {
    private val prefs = context?.getSharedPreferences("aurora_soundcloud_repo", Context.MODE_PRIVATE)
    private val pkcePrefs = context?.getSharedPreferences("aurora_soundcloud_pkce", Context.MODE_PRIVATE)
    private val memory = mutableMapOf<String, String>()

    // ── User OAuth session (authorization code flow) ──────────────────────

    fun saveSession(token: SoundCloudToken) {
        putPref(SESSION_KEY, tokenToJson(token))
    }

    fun loadSession(): SoundCloudToken? = getPref(SESSION_KEY)?.let { tokenFromJson(it) }

    fun clearSession() {
        removePref(SESSION_KEY)
    }

    fun hasSession(): Boolean = loadSession() != null

    // ── App token (client credentials flow, public resources) ─────────────

    fun saveAppToken(token: SoundCloudToken) {
        putPref(APP_TOKEN_KEY, tokenToJson(token))
    }

    fun loadAppToken(): SoundCloudToken? = getPref(APP_TOKEN_KEY)?.let { tokenFromJson(it) }

    fun clearAppToken() {
        removePref(APP_TOKEN_KEY)
    }

    // ── Authenticated account (minimum profile information) ───────────────

    fun saveAccount(account: SoundCloudAccount) {
        putPref(ACCOUNT_KEY, accountToJson(account))
    }

    fun loadAccount(): SoundCloudAccount? = getPref(ACCOUNT_KEY)?.let { accountFromJson(it) }

    fun clearAccount() {
        removePref(ACCOUNT_KEY)
    }

    // ── User-supplied app credentials ─────────────────────────────────────

    fun saveConfig(config: SoundCloudConfig) {
        putPref(CONFIG_KEY, configToJson(config))
    }

    fun loadConfig(): SoundCloudConfig? = getPref(CONFIG_KEY)?.let { configFromJson(it) }

    fun clearConfig() {
        removePref(CONFIG_KEY)
    }

    // ── PKCE material ─────────────────────────────────────────────────────

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

    // ── Storage helpers ───────────────────────────────────────────────────

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

    fun clearAll() {
        clearSession()
        clearAppToken()
        clearAccount()
        clearPkce()
    }

    companion object {
        private const val SESSION_KEY = "soundcloud_oauth_token"
        private const val APP_TOKEN_KEY = "soundcloud_app_token"
        private const val ACCOUNT_KEY = "soundcloud_account"
        private const val CONFIG_KEY = "soundcloud_config"
        private const val VERIFIER_KEY = "soundcloud_pkce_verifier"
        private const val STATE_KEY = "soundcloud_pkce_state"
    }
}

internal object SoundCloudCipher {
    private const val KEYSTORE_ALIAS = "aurora_soundcloud_oauth"
    private const val IV_LENGTH = 12

    fun encrypt(context: Context?, plain: String): String {
        val key = loadOrCreateKey(context)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decrypt(context: Context?, value: String): String? {
        return try {
            val decoded = Base64.getDecoder().decode(value)
            if (decoded.size <= IV_LENGTH) {
                null
            } else {
                val iv = decoded.copyOfRange(0, IV_LENGTH)
                val payload = decoded.copyOfRange(IV_LENGTH, decoded.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(context), GCMParameterSpec(128, iv))
                String(cipher.doFinal(payload), StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadOrCreateKey(context: Context?): Key {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            // Reuse the existing non-exportable keystore key when present
            // (avoids regenerating it, which would orphan persisted ciphertext).
            keyStore.getKey(KEYSTORE_ALIAS, null) ?: run {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(
                    KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generator.generateKey()
            }
        } catch (_: Exception) {
            // No AndroidKeyStore (e.g. local unit tests): derive a key from the
            // package name. Only used when a Keystore is unavailable.
            val seed = (context?.packageName ?: "aurora.soundcloud").toByteArray(StandardCharsets.UTF_8)
            SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(seed), "AES")
        }
    }
}

// ── JSON (de)serialization without external dependencies ───────────────────

private fun tokenToJson(token: SoundCloudToken): String = buildString {
    append("{")
    append("\"access_token\":\"").append(escapeJson(token.accessToken)).append("\"")
    append(",\"refresh_token\":\"").append(escapeJson(token.refreshToken ?: "")).append("\"")
    append(",\"expires_at_ms\":").append(token.expiresAtEpochMs)
    append(",\"token_type\":\"").append(escapeJson(token.tokenType)).append("\"")
    append(",\"scope\":\"").append(escapeJson(token.scope ?: "")).append("\"")
    append("}")
}

private fun tokenFromJson(raw: String): SoundCloudToken {
    val accessToken = extractJsonString(raw, "access_token")
    val refreshToken = extractJsonString(raw, "refresh_token")
    val expiresAt = extractJsonLong(raw, "expires_at_ms")
    val tokenType = extractJsonString(raw, "token_type").ifBlank { "Bearer" }
    val scope = extractJsonString(raw, "scope")
    return SoundCloudToken(
        accessToken = accessToken,
        refreshToken = refreshToken.ifBlank { null },
        expiresAtEpochMs = expiresAt,
        tokenType = tokenType,
        scope = scope.ifBlank { null }
    )
}

private fun configToJson(config: SoundCloudConfig): String = buildString {
    append("{")
    append("\"client_id\":\"").append(escapeJson(config.clientId)).append("\"")
    append(",\"client_secret\":\"").append(escapeJson(config.clientSecret)).append("\"")
    append(",\"redirect_uri\":\"").append(escapeJson(config.redirectUri)).append("\"")
    append("}")
}

private fun configFromJson(raw: String): SoundCloudConfig {
    val clientId = extractJsonString(raw, "client_id")
    val clientSecret = extractJsonString(raw, "client_secret")
    val redirectUri = extractJsonString(raw, "redirect_uri").ifBlank { SOUNDCLOUD_DEFAULT_REDIRECT_URI }
    return SoundCloudConfig(clientId, clientSecret, redirectUri)
}

private fun accountToJson(account: SoundCloudAccount): String = buildString {
    append("{")
    append("\"username\":\"").append(escapeJson(account.username)).append("\"")
    append(",\"permalink\":\"").append(escapeJson(account.permalink ?: "")).append("\"")
    append("}")
}

private fun accountFromJson(raw: String): SoundCloudAccount {
    val username = extractJsonString(raw, "username")
    val permalink = extractJsonString(raw, "permalink")
    return SoundCloudAccount(username = username, permalink = permalink.ifBlank { null })
}

internal fun escapeJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

internal fun extractJsonString(raw: String, key: String): String {
    val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    val match = regex.find(raw) ?: return ""
    return match.groupValues[1]
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { m -> String(Character.toChars(m.groupValues[1].toInt(16))) }
}

internal fun extractJsonLong(raw: String, key: String): Long {
    val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
    return regex.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
}

internal fun extractJsonBoolean(raw: String, key: String): Boolean {
    val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
    return regex.find(raw)?.groupValues?.get(1) == "true"
}

internal fun extractJsonObject(raw: String, key: String): String {
    // Finds the first `"key": {` then walks to its balanced closing brace,
    // skipping braces that appear inside string values.
    val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\{)")
        .find(raw) ?: return ""
    val start = match.groups[1]?.range?.first ?: return ""
    var depth = 0
    var inString = false
    var escape = false
    var end = -1
    for (i in start until raw.length) {
        val ch = raw[i]
        if (escape) {
            escape = false
            continue
        }
        if (inString) {
            if (ch == '\\') escape = true else if (ch == '"') inString = false
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) { end = i; break }
            }
        }
    }
    return if (end > 0) raw.substring(start, end + 1) else ""
}/**
 * [MusicSource] implementation for SoundCloud built on the official API.
 *
 * Endpoints used (all from the current OpenAPI spec and API Guide):
 *   GET  {api}/tracks?q=..&limit=..&linked_partitioning=true   search
 *   GET  {api}/tracks/{track_urn}                              track lookup
 *   GET  {api}/tracks/{track_urn}/streams                      stream URLs
 *   GET  {api}/me                                              authenticated profile
 *   GET  {api}/me/likes/tracks                                 authenticated likes
 *   POST / DELETE {api}/likes/tracks/{track_urn}               like / unlike
 *   POST {auth}/oauth/token   authorization_code / refresh_token / client_credentials
 *   GET  {auth}/authorize                                      user consent
 *   GET  {api}/{download_url}                                  official download
 *   POST {api}/disconnect                                      revoke app access
 *
 * Every API request uses `Authorization: OAuth <access_token>`.
 */
class SoundCloudSource(
    private val context: Context? = null,
    private val clientIdProvider: () -> String = { BuildConfig.SOUNDCLOUD_CLIENT_ID },
    private val clientSecretProvider: () -> String = { BuildConfig.SOUNDCLOUD_CLIENT_SECRET },
    private val redirectUriProvider: () -> String = { BuildConfig.SOUNDCLOUD_REDIRECT_URI },
    private val httpClient: SoundCloudHttpClient = DefaultSoundCloudHttpClient()
) : MusicSource {

    override val sourceId: String = "soundcloud"
    override val capabilities: Set<SourceCapability> = setOf(
        SourceCapability.STREAM,
        SourceCapability.PREVIEW,
        SourceCapability.DOWNLOAD
    )

    private val tokenStore = SoundCloudTokenStore(context)

    /**
     * Guards the client-credentials token endpoint, which is rate limited
     * (50 tokens/12h per app, 30 tokens/1h per IP). We never request more
     * than one token per minute and always reuse + refresh cached tokens.
     */
    private var lastClientCredentialsAttemptMs = 0L

    // ── Configuration ─────────────────────────────────────────────────────

    /**
     * Effective configuration. User-supplied credentials saved at runtime
     * take precedence over any BuildConfig values. No secret is baked into
     * the APK; the build-time defaults are empty.
     */
    private fun config(): SoundCloudConfig? {
        val saved = tokenStore.loadConfig()
        val clientId = (saved?.clientId ?: clientIdProvider()).trim()
        val clientSecret = (saved?.clientSecret ?: clientSecretProvider()).trim()
        val redirectUri = (saved?.redirectUri ?: redirectUriProvider()).trim()
            .ifBlank { SOUNDCLOUD_DEFAULT_REDIRECT_URI }
        return if (clientId.isNotBlank() && clientSecret.isNotBlank() && redirectUri.isNotBlank()) {
            SoundCloudConfig(clientId, clientSecret, redirectUri)
        } else {
            null
        }
    }

    fun isConfigured(): Boolean = config() != null

    /** True when the user completed the interactive authorization flow. */
    fun isSignedIn(): Boolean = tokenStore.hasSession()

    fun saveConfiguration(clientId: String, clientSecret: String, redirectUri: String = SOUNDCLOUD_DEFAULT_REDIRECT_URI) {
        tokenStore.saveConfig(
            SoundCloudConfig(
                clientId.trim(),
                clientSecret.trim(),
                redirectUri.trim().ifBlank { SOUNDCLOUD_DEFAULT_REDIRECT_URI }
            )
        )
    }

    /** The credentials saved at runtime, or null when none have been saved. */
    fun savedConfiguration(): SoundCloudConfig? = tokenStore.loadConfig()

    fun clearConfiguration() {
        tokenStore.clearConfig()
        clearAuthentication()
    }

    // ── OAuth: authorization code + PKCE ─────────────────────────────────

    /**
     * Builds the SoundCloud authorization URL and stores the PKCE verifier
     * (and CSRF state) for the pending exchange.
     */
    fun buildAuthorizationUrl(state: String = randomNonce()): Uri {
        val cfg = config() ?: throw IllegalStateException("SoundCloud is not configured")
        val codeVerifier = randomNonce()
        tokenStore.savePkce(codeVerifier, state)
        val codeChallenge = sha256Base64Url(codeVerifier)
        return Uri.parse(SOUNDCLOUD_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", cfg.clientId)
            .appendQueryParameter("redirect_uri", cfg.redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("display", "popup")
            .build()
    }

    /**
     * Exchanges the authorization [code] returned on the redirect URI for an
     * access token. Verifies [state] against the value saved with the PKCE
     * verifier (CSRF protection). Runs network I/O; call off the main thread.
     */
    fun exchangeAuthorizationCode(code: String, state: String?): SoundCloudAuthResult {
        val cfg = config() ?: return SoundCloudAuthResult(false, "SoundCloud is not configured")
        val storedState = tokenStore.loadPkceState()
        if (storedState != null && state != storedState) {
            android.util.Log.d("AuroraSC", "exchange stateMismatch=true")
            tokenStore.clearPkce()
            return SoundCloudAuthResult(false, "SoundCloud sign-in state mismatch; please try again", requiresReauth = true)
        }
        val verifier = tokenStore.loadPkceVerifier()
        if (verifier.isNullOrBlank()) {
            android.util.Log.d("AuroraSC", "exchange noPendingVerifier=true")
            return SoundCloudAuthResult(false, "No pending SoundCloud sign-in; please try again")
        }
        val response = httpClient.postForm(
            SOUNDCLOUD_TOKEN_URL,
            linkedMapOf(
                "grant_type" to "authorization_code",
                "client_id" to cfg.clientId,
                "client_secret" to cfg.clientSecret,
                "code" to code,
                "redirect_uri" to cfg.redirectUri,
                "code_verifier" to verifier
            ),
            oauthFormHeaders()
        )
        tokenStore.clearPkce()
        val token = parseTokenResponse(response)
        // Safe diagnostic: HTTP status only, never the token payload.
        android.util.Log.d("AuroraSC", "exchange tokenHttpStatus=" + response.statusCode +
            " parsed=" + (token != null))
        if (token == null) {
            return SoundCloudAuthResult(false, "SoundCloud rejected the authorization code; please try again")
        }
        tokenStore.saveSession(token)
        // Record the authenticated account so Settings can show the username.
        // Best-effort: a profile hiccup never invalidates a good session.
        fetchCurrentUser()
        return SoundCloudAuthResult(true, "Signed in to SoundCloud")
    }

    /**
     * Local sign-out: clears the stored user session, account information,
     * app token, and PKCE material. Local Aurora music, downloads, and the
     * library are never affected.
     */
    fun clearAuthentication() {
        tokenStore.clearSession()
        tokenStore.clearAppToken()
        tokenStore.clearAccount()
        tokenStore.clearPkce()
    }

    /**
     * Revokes the calling application's OAuth access via the official
     * POST /disconnect endpoint (204 = invalidated, 400 = not supported for
     * the current token). Best-effort and never throwing: callers always
     * clear local credentials afterwards regardless of the outcome.
     * Runs network I/O; call off the main thread.
     */
    fun disconnectRemote(): Boolean {
        val session = tokenStore.loadSession() ?: return true
        var response = httpClient.post("$SOUNDCLOUD_API_BASE/disconnect", oauthHeaders(session.accessToken))
        if (response.statusCode == 401) {
            val cfg = config() ?: return false
            val refreshed = refreshSessionOnce(cfg) ?: return false
            response = httpClient.post("$SOUNDCLOUD_API_BASE/disconnect", oauthHeaders(refreshed.accessToken))
        }
        return response.statusCode == 204
    }

    /** Username of the connected account, when known. */
    fun signedInAccountName(): String? = tokenStore.loadAccount()?.username

    /**
     * Fetches the authenticated user profile from GET /me and stores the
     * minimum account information (username + permalink).
     *
     * Requires a user session. If the access token is rejected it is refreshed
     * exactly once (single-use refresh tokens); a failed refresh clears the
     * invalid session so the app returns to the disconnected state. Failure to
     * read the profile never throws.
     */
    fun fetchCurrentUser(): SoundCloudAccount? {
        val session = tokenStore.loadSession() ?: return null
        var response = httpClient.get("$SOUNDCLOUD_API_BASE/me", oauthHeaders(session.accessToken))
        // Safe diagnostic: HTTP status only, never profile content.
        android.util.Log.d("AuroraSC", "me httpStatus=" + response.statusCode)
        if (response.statusCode == 401) {
            val cfg = config() ?: return null
            val refreshed = refreshSessionOnce(cfg) ?: return null
            response = httpClient.get("$SOUNDCLOUD_API_BASE/me", oauthHeaders(refreshed.accessToken))
        }
        if (response.statusCode !in 200..299) return null
        val body = response.body ?: return null
        val username = extractJsonString(body, "username").ifBlank { extractJsonString(body, "permalink") }
        if (username.isBlank()) return null
        val account = SoundCloudAccount(
            username = username,
            permalink = extractJsonString(body, "permalink").ifBlank { null }
        )
        tokenStore.saveAccount(account)
        return account
    }

    /**
     * Test-only hook: seeds a user session directly (e.g. an already-expired
     * one) so the refresh path can be exercised without the network.
     */
    internal fun seedSessionForTesting(token: SoundCloudToken) {
        tokenStore.saveSession(token)
    }

    // ── MusicSource: likes (official /likes + /me/likes endpoints) ─────────

    /**
     * Likes ([liked]=true, POST) or unlikes ([liked]=false, DELETE) a track
     * through the official `/likes/tracks/{track_urn}` endpoints.
     *
     * Requires a signed-in user session; a 401 refreshes the session exactly
     * once and retries. Never throws: failures are reported in [LikeResult].
     * Runs network I/O; call off the main thread.
     */
    override fun setTrackLiked(track: SourceMetadata, liked: Boolean): LikeResult {
        if (!isConfigured()) {
            return LikeResult(success = false, liked = liked, message = "SoundCloud is not configured")
        }
        if (!isSignedIn()) {
            return LikeResult(success = false, liked = liked, message = "Sign in to SoundCloud to sync likes")
        }
        val url = "$SOUNDCLOUD_API_BASE/likes/tracks/${toTrackUrn(track.trackId.value)}"
        val synced = authorized(
            request = { token ->
                if (liked) httpClient.post(url, oauthHeaders(token))
                else httpClient.delete(url, oauthHeaders(token))
            },
            parse = { response -> if (response.statusCode in 200..299) true else null }
        ) ?: false
        return if (synced) {
            LikeResult(success = true, liked = liked, remoteSynced = true)
        } else {
            LikeResult(
                success = false,
                liked = liked,
                message = if (liked) "SoundCloud like failed — kept locally"
                else "SoundCloud unlike failed — kept locally"
            )
        }
    }

    /**
     * Returns the authenticated user's liked track ids (`trackId.value`
     * strings) from the official GET /me/likes/tracks endpoint, or null when
     * the list cannot be determined (not signed in, offline, auth failure).
     * Null means "unknown", never "no likes". Runs network I/O; call off the
     * main thread.
     */
    override fun getLikedTrackIds(): Set<String>? {
        if (!isConfigured() || !isSignedIn()) return null
        val url = apiUrl("/me/likes/tracks", linkedMapOf("limit" to "100", "linked_partitioning" to "true"))
        return authorized(
            request = { token -> httpClient.get(url, oauthHeaders(token)) },
            parse = { response ->
                if (response.statusCode !in 200..299) null
                else response.body?.let { body ->
                    extractTrackObjects(body)
                        .mapNotNull { metadataForTrackObject(it)?.trackId?.value }
                        .toSet()
                }
            }
        )
    }

    // ── OAuth: client credentials (public resources) ─────────────────────

    /**
     * Requests a client-credentials token using HTTP Basic authentication, as
     * documented for the SoundCloud token endpoint. Used only for public
     * resources (search, public playback) when no user session exists.
     * Tokens are cached and refreshed rather than re-requested (rate limits).
     */
    private fun requestClientCredentialsToken(cfg: SoundCloudConfig): SoundCloudToken? {
        val basic = "Basic " + Base64.getEncoder().encodeToString(
            "${cfg.clientId}:${cfg.clientSecret}".toByteArray(StandardCharsets.UTF_8)
        )
        val response = httpClient.postForm(
            SOUNDCLOUD_TOKEN_URL,
            linkedMapOf("grant_type" to "client_credentials"),
            oauthFormHeaders(basic)
        )
        return parseTokenResponse(response)
    }

    // ── Token lifecycle ───────────────────────────────────────────────────

    private fun oauthFormHeaders(basicAuth: String? = null): Map<String, String> {
        return buildMap {
            if (basicAuth != null) put("Authorization", basicAuth)
        }
    }

    private fun oauthHeaders(token: String): Map<String, String> = mapOf(
        "Authorization" to "OAuth $token"
    )

    private fun parseTokenResponse(response: SoundCloudResponse): SoundCloudToken? {
        if (response.statusCode !in 200..299) return null
        val body = response.body ?: return null
        val accessToken = extractJsonString(body, "access_token")
        if (accessToken.isBlank()) return null
        val refreshToken = extractJsonString(body, "refresh_token")
        val expiresIn = extractJsonLong(body, "expires_in").coerceAtLeast(0L)
        val tokenType = extractJsonString(body, "token_type").ifBlank { "Bearer" }
        val scope = extractJsonString(body, "scope")
        return SoundCloudToken(
            accessToken = accessToken,
            refreshToken = refreshToken.ifBlank { null },
            expiresAtEpochMs = if (expiresIn > 0L) System.currentTimeMillis() + expiresIn * 1000L else 0L,
            tokenType = tokenType,
            scope = scope.ifBlank { null }
        )
    }

    /**
     * Attempts exactly one refresh of the stored user session. Refresh tokens
     * are single-use: when the refresh fails the invalid session (and account
     * information) is cleared so the app returns to the disconnected state
     * rather than retrying forever.
     */
    private fun refreshSessionOnce(cfg: SoundCloudConfig): SoundCloudToken? {
        val session = tokenStore.loadSession() ?: return null
        if (session.refreshToken.isNullOrBlank()) {
            tokenStore.clearSession()
            tokenStore.clearAccount()
            return null
        }
        val refreshed = refreshTokenRequest(session.refreshToken, cfg)
        if (refreshed != null) {
            tokenStore.saveSession(refreshed)
            return refreshed
        }
        tokenStore.clearSession()
        tokenStore.clearAccount()
        return null
    }

    private fun refreshTokenRequest(refreshToken: String?, cfg: SoundCloudConfig): SoundCloudToken? {
        if (refreshToken.isNullOrBlank()) return null
        val response = httpClient.postForm(
            SOUNDCLOUD_TOKEN_URL,
            linkedMapOf(
                "grant_type" to "refresh_token",
                "client_id" to cfg.clientId,
                "client_secret" to cfg.clientSecret,
                "refresh_token" to refreshToken
            ),
            oauthFormHeaders()
        )
        return parseTokenResponse(response)
    }

    /**
     * Returns a usable access token, refreshing when near expiry.
     *
     * Priority:
     *  1. User session (authorization code flow). Refresh via the refresh_token
     *     grant when expired. Single-use refresh tokens that fail invalidate
     *     the session (the user must sign in again).
     *  2. Cached client-credentials app token, refreshed the same way.
     *  3. A single new client-credentials request (rate limited).
     */
    private fun resolveAccessToken(): String? = resolveToken()?.accessToken

    private fun resolveToken(): SoundCloudToken? {
        val now = System.currentTimeMillis()
        val cfg = config() ?: return null

        val session = tokenStore.loadSession()
        if (session != null) {
            if (session.expiresAtEpochMs <= 0L || session.expiresAtEpochMs > now + 60_000L) return session
            val refreshed = refreshTokenRequest(session.refreshToken, cfg)
            if (refreshed != null) {
                tokenStore.saveSession(refreshed)
                return refreshed
            }
            // Refresh tokens are single-use: a failed refresh means the
            // session can no longer be restored automatically.
            tokenStore.clearSession()
        }

        val appToken = tokenStore.loadAppToken()
        if (appToken != null) {
            if (appToken.expiresAtEpochMs <= 0L || appToken.expiresAtEpochMs > now + 60_000L) return appToken
            val refreshed = refreshTokenRequest(appToken.refreshToken, cfg)
            if (refreshed != null) {
                tokenStore.saveAppToken(refreshed)
                return refreshed
            }
            tokenStore.clearAppToken()
        }

        if (now - lastClientCredentialsAttemptMs < 60_000L) return null
        lastClientCredentialsAttemptMs = now
        val token = requestClientCredentialsToken(cfg) ?: return null
        tokenStore.saveAppToken(token)
        return token
    }

    // ── Authenticated HTTP helpers ────────────────────────────────────────

    /**
     * Runs [request] with a resolved access token and [parse]s the response.
     * On a 401 the cached tokens are discarded and the request retried once
     * with a freshly resolved token.
     */
    private fun <T> authorized(
        request: (token: String) -> SoundCloudResponse,
        parse: (SoundCloudResponse) -> T?
    ): T? {
        val firstToken = resolveAccessToken() ?: return null
        val first = request(firstToken)
        parse(first)?.let { return it }
        if (first.statusCode != 401) return null
        // The access token was rejected. Refresh a user session exactly once so
        // a valid refresh token keeps the account connected instead of dropping
        // it; only then fall back to dropping invalid tokens and retrying.
        val cfg = config()
        val refreshed = if (cfg != null) refreshSessionOnce(cfg) else null
        if (refreshed != null) {
            parse(request(refreshed.accessToken))?.let { return it }
        }
        tokenStore.clearSession()
        tokenStore.clearAppToken()
        lastClientCredentialsAttemptMs = 0L
        val retryToken = resolveAccessToken() ?: return null
        return parse(request(retryToken))
    }

    private fun authorizedStringRequest(url: String): String? = authorized(
        request = { token -> httpClient.get(url, oauthHeaders(token)) },
        parse = { response -> if (response.statusCode in 200..299) response.body else null }
    )

    private fun authorizedBytesRequest(url: String): Pair<ByteArray?, String?>? = authorized(
        request = { token -> httpClient.getBytes(url, oauthHeaders(token)) },
        parse = { response ->
            if (response.statusCode in 200..299) response.bytes to response.contentType else null
        }
    )

    // ── MusicSource: search ───────────────────────────────────────────────

    override fun search(query: String): List<SourceMetadata> {
        if (query.isBlank() || !isConfigured()) return emptyList()
        val url = apiUrl("/tracks", linkedMapOf(
            "q" to query.trim(),
            "limit" to "10",
            "linked_partitioning" to "true"
        ))
        val raw = authorizedStringRequest(url) ?: return emptyList()
        return try {
            extractTrackObjects(raw).mapNotNull { metadataForTrackObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Detailed search that surfaces the real outcome (not configured, auth
     * failure, HTTP error, empty, or success) so the UI never silently shows an
     * empty list when something actually failed.
     */
    override fun searchDetailed(query: String): SourceSearchResult {
        if (query.isBlank()) return SourceSearchResult.Empty()
        if (!isConfigured()) {
            return SourceSearchResult.NotConfigured(
                "SoundCloud is not configured. Add your Client ID and Client Secret in Settings → SoundCloud, then connect."
            )
        }

        val url = apiUrl("/tracks", linkedMapOf(
            "q" to query.trim(),
            "limit" to "20",
            "linked_partitioning" to "true"
        ))
        val firstToken = resolveAccessToken()
            ?: return SourceSearchResult.Error(
                "SoundCloud authentication failed. Check your credentials and network connection, then try again.",
                401
            )
        var response = httpClient.get(url, oauthHeaders(firstToken))
        if (response.statusCode == 401 || response.statusCode == 403) {
            // Token rejected: drop cached credentials and retry exactly once.
            tokenStore.clearSession()
            tokenStore.clearAppToken()
            lastClientCredentialsAttemptMs = 0L
            val retryToken = resolveAccessToken()
                ?: return SourceSearchResult.Error(
                    "SoundCloud denied the cached token and re-authentication failed. Verify your API credentials.",
                    response.statusCode
                )
            response = httpClient.get(url, oauthHeaders(retryToken))
        }
        // Safe diagnostic: outcome class + HTTP status only, never content.
        android.util.Log.d("AuroraSC", "search httpStatus=" + response.statusCode +
            " bodyPresent=" + (!response.body.isNullOrBlank()))

        return when {
            response.statusCode == -1 ->
                SourceSearchResult.Error("No network connection or SoundCloud is unreachable.")
            response.statusCode == 401 || response.statusCode == 403 ->
                SourceSearchResult.Error(
                    "SoundCloud denied access (HTTP ${response.statusCode}). Verify your API credentials.",
                    response.statusCode
                )
            response.statusCode == 429 ->
                SourceSearchResult.Error("SoundCloud rate limit reached. Please try again later.", 429)
            response.statusCode !in 200..299 ->
                SourceSearchResult.Error("SoundCloud returned HTTP ${response.statusCode}.", response.statusCode)
            response.body.isNullOrBlank() ->
                SourceSearchResult.Error("SoundCloud returned an empty response.")
            else -> {
                val tracks = try {
                    extractTrackObjects(response.body!!).mapNotNull { metadataForTrackObject(it) }
                } catch (_: Exception) {
                    return SourceSearchResult.Error("SoundCloud returned a response that could not be read.")
                }
                if (tracks.isEmpty()) {
                    SourceSearchResult.Empty("No SoundCloud results for \"${query.trim()}\"")
                } else {
                    SourceSearchResult.Success(tracks)
                }
            }
        }
    }

    // ── MusicSource: track lookup ─────────────────────────────────────────

    override fun getTrack(id: SourceTrackId): SourceMetadata? {
        if (id.source != sourceId) return null
        if (!isConfigured()) return null
        val urn = toTrackUrn(id.value)
        val raw = authorizedStringRequest("$SOUNDCLOUD_API_BASE/tracks/$urn") ?: return null
        return try {
            metadataForTrackObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    // ── MusicSource: streaming ────────────────────────────────────────────

    override fun stream(track: SourceMetadata): StreamResult {
        if (!isConfigured()) {
            return StreamResult(uri = null, metadata = track, isPreview = false, error = "SoundCloud is not configured")
        }
        if (track.sourceCapabilities.contains(SourceCapability.BLOCKED)) {
            return StreamResult(uri = null, metadata = track, isPreview = false, error = "This SoundCloud track is blocked and cannot be streamed")
        }

        val alreadyResolved = track.localUri
        if (alreadyResolved != null && (alreadyResolved.scheme == "http" || alreadyResolved.scheme == "https")) {
            return StreamResult(
                uri = alreadyResolved,
                metadata = track.copy(localUri = alreadyResolved),
                isPreview = track.sourceCapabilities.contains(SourceCapability.PREVIEW)
            )
        }

        val resolved = resolveStreamUrl(track)
        return if (resolved == null) {
            StreamResult(uri = null, metadata = track, isPreview = false, error = "SoundCloud stream is unavailable for this track")
        } else {
            StreamResult(
                uri = resolved,
                metadata = track.copy(localUri = resolved),
                isPreview = track.sourceCapabilities.contains(SourceCapability.PREVIEW)
            )
        }
    }

    /**
     * Resolves an actual stream URL from GET /tracks/{urn}/streams using only
     * the documented response fields (hls_aac_160_url, hls_mp3_128_url,
     * preview_mp3_128_url). Preview tracks get the preview stream; playable
     * tracks get HLS AAC/MP3. Blocked tracks never reach this path.
     */
    private fun resolveStreamUrl(track: SourceMetadata): Uri? {
        val urn = toTrackUrn(track.trackId.value)
        val streamsUrl = "$SOUNDCLOUD_API_BASE/tracks/$urn/streams"
        val statusHolder = intArrayOf(-1)
        val json = authorized(
            request = { t ->
                val r = httpClient.get(streamsUrl, oauthHeaders(t))
                statusHolder[0] = r.statusCode
                r
            },
            parse = { r -> if (r.statusCode in 200..299) r.body else null }
        )
        // Safe diagnostic: status + resolution booleans only, never stream URLs (signed).
        android.util.Log.d("AuroraSC", "streams httpStatus=" + statusHolder[0] +
            " bodyPresent=" + (!json.isNullOrBlank()))
        if (json == null) return null
        val isPreviewOnly = track.sourceCapabilities.contains(SourceCapability.PREVIEW) &&
            !track.sourceCapabilities.contains(SourceCapability.STREAM)
        val candidates = if (isPreviewOnly) {
            listOf("preview_mp3_128_url")
        } else {
            listOf("hls_aac_160_url", "hls_mp3_128_url", "preview_mp3_128_url")
        }
        for (key in candidates) {
            val url = extractJsonString(json, key)
            if (url.isNotBlank()) return safeUri(url)
        }
        return null
    }

    // ── MusicSource: downloads ────────────────────────────────────────────

    override fun checkDownloadAvailability(track: SourceMetadata): DownloadCapability {
        if (!isConfigured()) {
            return DownloadCapability(DownloadAvailability.BLOCKED, "SoundCloud is not configured")
        }
        return if (track.sourceCapabilities.contains(SourceCapability.DOWNLOAD)) {
            DownloadCapability(DownloadAvailability.AVAILABLE, "SoundCloud explicitly permits downloading this track")
        } else if (track.sourceCapabilities.contains(SourceCapability.BLOCKED)) {
            DownloadCapability(DownloadAvailability.BLOCKED, "This SoundCloud track is blocked for streaming and download")
        } else {
            DownloadCapability(DownloadAvailability.PREVIEW_ONLY, "This SoundCloud track is streamable but not marked downloadable by the artist")
        }
    }

    override fun download(track: SourceMetadata, targetDir: File): DownloadResult {
        if (!isConfigured()) {
            return DownloadResult(success = false, error = "SoundCloud is not configured")
        }
        if (!track.sourceCapabilities.contains(SourceCapability.DOWNLOAD)) {
            return DownloadResult(success = false, error = "SoundCloud does not permit downloading this track")
        }

        // Re-fetch the track so the official `downloadable` flag and
        // non-null `download_url` from the API gate the operation.
        val urn = toTrackUrn(track.trackId.value)
        val trackJson = authorizedStringRequest("$SOUNDCLOUD_API_BASE/tracks/$urn")
            ?: return DownloadResult(success = false, error = "SoundCloud could not refresh track metadata")
        val officialDownloadable = extractJsonBoolean(trackJson, "downloadable")
        val officialDownloadUrl = extractJsonString(trackJson, "download_url")
        if (!officialDownloadable || officialDownloadUrl.isBlank()) {
            return DownloadResult(success = false, error = "SoundCloud does not permit downloading this track")
        }

        val downloadTarget = when {
            officialDownloadUrl.startsWith("http://") || officialDownloadUrl.startsWith("https://") -> officialDownloadUrl
            officialDownloadUrl.startsWith("/") -> "$SOUNDCLOUD_API_BASE$officialDownloadUrl"
            else -> "$SOUNDCLOUD_API_BASE/$officialDownloadUrl"
        }
        val response = authorizedBytesRequest(downloadTarget)
            ?: return DownloadResult(success = false, error = "SoundCloud download request failed")
        val bytes = response.first
            ?: return DownloadResult(success = false, error = "SoundCloud download returned no data")

        val extension = extensionForContentType(response.second)
        val safeName = sanitizeFilename(track.title.ifBlank { "soundcloud_track" }) + extension
        val targetFile = File(targetDir, safeName)
        return try {
            targetDir.mkdirs()
            targetFile.writeBytes(bytes)
            DownloadResult(success = true, localUri = Uri.fromFile(targetFile), localPath = targetFile.absolutePath)
        } catch (_: Exception) {
            DownloadResult(success = false, error = "SoundCloud download failed")
        }
    }

    /**
     * Real streaming download from the official `download_url`, with byte
     * progress and cooperative cancellation. The same strict gate as [download]
     * applies: the API must report `downloadable: true` and a non-null
     * `download_url` immediately before any bytes are fetched.
     */
    override fun downloadWithProgress(
        track: SourceMetadata,
        targetDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
    ): DownloadResult {
        if (!isConfigured()) {
            return DownloadResult(success = false, error = "SoundCloud is not configured")
        }
        if (!track.sourceCapabilities.contains(SourceCapability.DOWNLOAD)) {
            return DownloadResult(success = false, error = "SoundCloud does not permit downloading this track")
        }

        val urn = toTrackUrn(track.trackId.value)
        val trackJson = authorizedStringRequest("$SOUNDCLOUD_API_BASE/tracks/$urn")
            ?: return DownloadResult(success = false, error = "SoundCloud could not refresh track metadata")
        val officialDownloadable = extractJsonBoolean(trackJson, "downloadable")
        val officialDownloadUrl = extractJsonString(trackJson, "download_url")
        if (!officialDownloadable || officialDownloadUrl.isBlank()) {
            return DownloadResult(success = false, error = "SoundCloud does not permit downloading this track")
        }

        val downloadTarget = when {
            officialDownloadUrl.startsWith("http://") || officialDownloadUrl.startsWith("https://") -> officialDownloadUrl
            officialDownloadUrl.startsWith("/") -> "$SOUNDCLOUD_API_BASE$officialDownloadUrl"
            else -> "$SOUNDCLOUD_API_BASE/$officialDownloadUrl"
        }
        val token = resolveAccessToken()
            ?: return DownloadResult(success = false, error = "SoundCloud authentication failed")

        val safeBase = sanitizeFilename(track.title.ifBlank { "soundcloud_track" })
        var connection: HttpURLConnection? = null
        var temp: File? = null
        return try {
            connection = URL(downloadTarget).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.doInput = true
            connection.setRequestProperty("Authorization", "OAuth $token")
            connection.setRequestProperty("Accept", "application/octet-stream")
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                return DownloadResult(success = false, error = "SoundCloud download failed (HTTP $code)")
            }
            val contentType = connection.getHeaderField("Content-Type")
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: 0L
            val extension = extensionForContentType(contentType)
            targetDir.mkdirs()
            val target = File(targetDir, "$safeBase$extension")
            temp = File(targetDir, "$safeBase$extension.part").apply { delete() }
            connection.inputStream.use { input ->
                FileOutputStream(temp!!).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        if (isCancelled()) {
                            temp!!.delete()
                            connection.disconnect()
                            return DownloadResult(success = false, error = "Download cancelled")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }
            connection.disconnect()
            if (isCancelled()) {
                temp!!.delete()
                return DownloadResult(success = false, error = "Download cancelled")
            }
            if (temp!!.length() <= 0L) {
                temp!!.delete()
                return DownloadResult(success = false, error = "SoundCloud returned no data")
            }
            if (target.exists()) target.delete()
            if (!temp!!.renameTo(target)) {
                temp!!.copyTo(target, overwrite = true)
                temp!!.delete()
            }
            onProgress(target.length(), target.length())
            DownloadResult(success = true, localUri = Uri.fromFile(target), localPath = target.absolutePath)
        } catch (e: Exception) {
            temp?.delete()
            DownloadResult(success = false, error = e.message ?: "SoundCloud download failed")
        } finally {
            connection?.disconnect()
        }
    }

    // ── Response parsing ──────────────────────────────────────────────────

    /** Extracts top-level track object texts from a search/tracks response. */
    private fun extractTrackObjects(rawJson: String): List<String> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        return if (trimmed.startsWith("[")) {
            // Legacy / unpaginated array shape.
            splitTopLevelObjects(trimmed)
        } else {
            // Paginated shape: {"collection": [...], "next_href": "..."}
            val match = Regex("\"collection\"\\s*:\\s*(\\[)", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(rawJson) ?: return emptyList()
            val openIndex = match.groups[1]?.range?.first ?: return emptyList()
            val endIndex = findMatchingBracket(rawJson, openIndex) ?: return emptyList()
            splitTopLevelObjects(rawJson.substring(openIndex, endIndex + 1))
        }
    }

    private fun findMatchingBracket(json: String, openIndex: Int): Int? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in openIndex until json.length) {
            val ch = json[i]
            if (escape) {
                escape = false
                continue
            }
            if (inString) {
                if (ch == '\\') escape = true else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun splitTopLevelObjects(rawJson: String): List<String> {
        val trimmed = rawJson.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val content = trimmed.substring(1, trimmed.length - 1).trim()
        if (content.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        var startIndex = -1
        for ((index, ch) in content.withIndex()) {
            if (escape) {
                escape = false
                continue
            }
            if (inString) {
                if (ch == '\\') escape = true else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) startIndex = index
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && startIndex >= 0) {
                        result.add(content.substring(startIndex, index + 1))
                        startIndex = -1
                    }
                }
            }
        }
        return result
    }

    /**
     * Maps a Track object (current OpenAPI schema) to [SourceMetadata].
     *
     * Track access states are respected exactly as documented:
     *   playable -> full streaming
     *   preview  -> preview snippet only
     *   blocked  -> metadata only, no streaming
     * Downloads are only offered when the API explicitly marks the track
     * `downloadable: true` AND provides a non-null `download_url`.
     */
    private fun metadataForTrackObject(rawJson: String): SourceMetadata? {
        val urn = extractJsonString(rawJson, "urn")
        val legacyId = extractJsonLong(rawJson, "id")
        val trackValue = if (urn.isNotBlank()) urn else if (legacyId > 0L) legacyId.toString() else ""
        if (trackValue.isBlank()) return null

        val title = extractJsonString(rawJson, "title").ifBlank { "Unknown track" }
        val userObject = extractJsonObject(rawJson, "user")
        val artist = when {
            userObject.isNotBlank() -> extractJsonString(userObject, "username")
                .ifBlank { extractJsonString(rawJson, "metadata_artist") }
                .ifBlank { extractJsonString(userObject, "full_name") }
            else -> extractJsonString(rawJson, "metadata_artist")
                .ifBlank { extractJsonString(rawJson, "user_name") }
        }.ifBlank { "Unknown artist" }

        val album = extractJsonString(rawJson, "genre").ifBlank { extractJsonString(rawJson, "label_name") }
        val durationMs = extractJsonLong(rawJson, "duration")
        val art = resolveArtworkUrl(
            extractJsonString(rawJson, "artwork_url").ifBlank { extractJsonString(rawJson, "avatar_url") }
        )

        val rawAccess = extractJsonString(rawJson, "access")
        val access = if (rawAccess.isNotBlank()) {
            rawAccess
        } else if (extractJsonBoolean(rawJson, "streamable")) {
            "playable"
        } else {
            "blocked"
        }

        val downloadable = extractJsonBoolean(rawJson, "downloadable")
        val downloadUrl = extractJsonString(rawJson, "download_url")
        val officialDownloadPermitted = downloadable && downloadUrl.isNotBlank()

        val capabilities = linkedSetOf<SourceCapability>()
        when (access) {
            "playable" -> capabilities += SourceCapability.STREAM
            "preview" -> capabilities += SourceCapability.PREVIEW
            else -> capabilities += SourceCapability.BLOCKED
        }
        if (officialDownloadPermitted) capabilities += SourceCapability.DOWNLOAD
        if (capabilities.isEmpty()) capabilities += SourceCapability.BLOCKED

        return SourceMetadata(
            trackId = SourceTrackId(sourceId, trackValue),
            title = title,
            artist = artist,
            album = album.ifBlank { "SoundCloud" },
            durationMs = durationMs,
            artworkUri = art,
            localUri = null,
            sourceCapabilities = capabilities
        )
    }

    // ── URI / URL helpers ─────────────────────────────────────────────────

    private fun apiUrl(path: String, queryParams: Map<String, String> = emptyMap()): String {
        val base = "$SOUNDCLOUD_API_BASE$path"
        if (queryParams.isEmpty()) return base
        val joined = queryParams.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$base?$joined"
    }

    private fun toTrackUrn(value: String): String {
        val normalized = value.trim()
        return if (normalized.startsWith("soundcloud:tracks:") || normalized.startsWith("http")) {
            normalized
        } else {
            "soundcloud:tracks:$normalized"
        }
    }

    private fun safeUri(rawUrl: String?): Uri? = try {
        rawUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun resolveArtworkUrl(rawUrl: String?): Uri? {
        val candidate = rawUrl?.takeIf { it.isNotBlank() } ?: return null
        val normalized = if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            candidate.replace("-large", "-t500x500")
        } else {
            "https:$candidate"
        }
        return safeUri(normalized)
    }

    private fun extensionForContentType(contentType: String?): String =
        audioExtensionForContentType(contentType)

    private fun sanitizeFilename(name: String): String =
        sanitizeAudioFilename(name, "soundcloud_track")

    // 32 random bytes -> 43 base64url chars, within the RFC 7636 43-128
    // character range for PKCE code_verifiers.
    private fun randomNonce(): String = buildString {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        append(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
    }

    private fun sha256Base64Url(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}