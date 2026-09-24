package com.aurora.app

import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.animation.DecelerateInterpolator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.widget.Toast
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.aurora.app.audio.EqualizerBands
import com.aurora.app.audio.EqualizerConfig
import com.aurora.app.audio.EqualizerPresets
import com.aurora.app.audio.EqualizerStore
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.repositories.DownloadRepository
import com.aurora.app.database.repositories.FavoriteRepository
import com.aurora.app.database.repositories.LibraryRepository
import com.aurora.app.database.repositories.toTrack
import com.aurora.app.favorites.favoriteKey
import com.aurora.app.import.FilePickerIntentFactory
import com.aurora.app.import.ImportResult
import com.aurora.app.import.ImportStatus
import com.aurora.app.import.MusicImportManager
import com.aurora.app.source.DownloadAvailability
import com.aurora.app.source.AudiusSource
import com.aurora.app.source.LocalSource
import com.aurora.app.source.MusicSource
import com.aurora.app.source.SoundCloudSource
import com.aurora.app.source.SourceCapability
import com.aurora.app.source.SourceKind
import com.aurora.app.source.SourceMetadata
import com.aurora.app.source.SourceSearchResult
import com.aurora.app.source.SourceTrackId
import com.aurora.app.source.SpotifySource
import com.aurora.app.source.UnifiedSearchOutcome
import com.aurora.app.source.UnifiedSearchService
import com.aurora.app.source.ProviderSearchState
import com.aurora.app.source.spotifyWebUri
import com.aurora.app.transfer.DownloadManager
import com.aurora.app.transfer.DownloadProgress
import com.aurora.app.transfer.DownloadState
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val bg = Color.parseColor("#08070B")
    private val surface = Color.parseColor("#111017")
    private val elevated = Color.parseColor("#18151F")
    private val higher = Color.parseColor("#201D28")
    private val purple = Color.parseColor("#7C5CFC")
    private val lightPurple = Color.parseColor("#9B83FF")
    /** Subtle Spotify source accent, kept muted to match the Aurora palette. */
    private val spotifyAccent = Color.rgb(64, 190, 120)
    private val purpleSoft = Color.parseColor("#207C5CFC")
    private val text = Color.parseColor("#F5F3FA")
    private val textSecondary = Color.parseColor("#BAB5C4")
    private val textMuted = Color.parseColor("#918D9C")
    private val navBg = Color.parseColor("#08070B")
    private val divider = Color.parseColor("#1AFFFFFF")

    private var svc: PlaybackService? = null
    private var bound = false
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = (binder as PlaybackService.LocalBinder).service()
            bound = true
            svc?.listener = playbackListener
            svc?.setShuffleEnabled(isShuffle)
            svc?.setRepeatMode(repeatMode)
            svc?.streamResolver = { track -> resolveStreamUri(track) }
            val cur = svc?.currentTrack
            if (cur != null) {
                onTrackChanged(cur)
                onPlayStateChanged(svc?.isPlaying == true)
                onProgressUpdate(svc?.positionMs ?: 0, svc?.durationMs ?: 0)
            } else {
                syncMiniPlayerVisibility()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            svc = null
            bound = false
        }
    }

    private val playbackListener = object : PlaybackService.PlaybackListener {
        override fun onTrackChanged(track: Track) = this@MainActivity.onTrackChanged(track)
        override fun onPlayStateChanged(isPlaying: Boolean) = this@MainActivity.onPlayStateChanged(isPlaying)
        override fun onProgressUpdate(posMs: Int, durMs: Int) = this@MainActivity.onProgressUpdate(posMs, durMs)
        override fun onQueueChanged() = this@MainActivity.onQueueChanged()
        override fun onPlaybackError(message: String) = this@MainActivity.onPlaybackError(message)
    }

    private val database by lazy { AuroraDatabase.getInstance(applicationContext) }
    private val libraryRepository by lazy { LibraryRepository(database) }
    private val favoriteRepository by lazy { FavoriteRepository(database) }
    private val downloadRepository by lazy { DownloadRepository(database) }
    private val importManager by lazy { MusicImportManager(this, database) }
    private val downloadManager by lazy { DownloadManager(this, database) }
    private val soundCloudSource by lazy { SoundCloudSource(applicationContext) }
    private val audiusSource by lazy { AudiusSource() }
    private val spotifySource by lazy { SpotifySource(applicationContext) }
    private val localSource by lazy { LocalSource(libraryRepository, context = applicationContext) }
    /** Aggregates every provider into one source-aware, failure-isolated result set. */
    private val unifiedSearchService by lazy {
        UnifiedSearchService(listOf(localSource, audiusSource, soundCloudSource, spotifySource))
    }
    private var allTracks: List<Track> = emptyList()
    /** Track ids with a verified completed Aurora download (drives the Downloaded bucket). */
    private var downloadedTrackIds: Set<Long> = emptySet()
    /** Saved online library entries by track id (fresh stream resolved per play). */
    private var savedOnlineMeta: Map<Long, SourceMetadata> = emptyMap()
    private var activeDownloadView = false
    private var libraryNeedsRefresh = false

    private enum class LibrarySection { TRACKS, ALBUMS, ARTISTS }
    private enum class TrackFilter { ALL, LOCAL, DOWNLOADED, ONLINE }

    private val recents = mutableListOf<Track>()
    /**
     * In-memory mirror of the Room favorites, used for synchronous heart
     * rendering. The database is the single source of truth; this is always
     * reloaded from [favoriteRepository] on a background thread and is never a
     * second persisted store.
     */
    @Volatile private var favoriteKeys: Set<String> = emptySet()
    private val preferences by lazy { getSharedPreferences("aurora_preferences", Context.MODE_PRIVATE) }
    /** Equalizer settings live in the same existing preferences store. */
    private val equalizerStore by lazy { EqualizerStore(preferences) }
    private var equalizerConfig: EqualizerConfig = EqualizerConfig.default()
    private var selectedTab = 0
    private val navTabs = mutableListOf<LinearLayout>()
    private var contentContainer: FrameLayout? = null
    private var homeScroll: ScrollView? = null
    private var searchView: View? = null
    private var libraryView: View? = null
    private var settingsView: View? = null
    private var fullPlayerOverlay: FrameLayout? = null
    private var queueOverlay: FrameLayout? = null
    private var soundCloudSettingsGroup: LinearLayout? = null
    private var spotifySettingsGroup: LinearLayout? = null
    private var isFullPlayerOpen = false
    private var recentsRow: LinearLayout? = null
    private var quickGrid: LinearLayout? = null
    private var discoverCtaSubtitle: TextView? = null
    private var homeLibraryRow: LinearLayout? = null
    private var libraryContainer: LinearLayout? = null
    private var librarySection = LibrarySection.TRACKS
    private var trackFilter = TrackFilter.ALL
    private var librarySectionTabs = mutableListOf<FrameLayout>()
    private var trackFilterTabs = mutableListOf<FrameLayout>()
    private var trackFilterRow: View? = null
    private var searchResultsContainer: LinearLayout? = null
    private var searchStatusContainer: LinearLayout? = null
    private var searchEmptyState: LinearLayout? = null
    private var searchInput: EditText? = null

    private var searchDebounce: Runnable? = null
    private var searchRequestToken = 0
    private val searchDownloadButtons = mutableMapOf<String, TextView>()
    private var discoverView: View? = null
    private var discoverStatusContainer: LinearLayout? = null
    private var discoverResultsContainer: LinearLayout? = null
    private var discoveryRequestToken = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private val completedDownloadNotifications = mutableSetOf<Long>()
    private var queueContainer: LinearLayout? = null
    private var queueSubtitle: TextView? = null
    private var queueScroll: ScrollView? = null
    // Cached queue rows so a change of the active item only retints two rows
    // instead of rebuilding the whole list (and its artwork) again.
    private val queueRowViews = mutableListOf<LinearLayout>()
    private val queueRowTitleViews = mutableListOf<TextView>()
    private val queueRowStatusViews = mutableListOf<TextView>()
    private var queueRenderedSignature: List<Pair<Long, String>>? = null
    private var queueRenderedIndex = Int.MIN_VALUE
    private var queueRenderedPlaying = false
    private var miniPlayerBar: LinearLayout? = null
    private var miniArtView: ImageView? = null
    private var miniTitle: TextView? = null
    private var miniArtist: TextView? = null
    private var miniPlayBtn: ImageView? = null
    private var miniProgress: ProgressBar? = null
    private var fpBackdropView: ImageView? = null
    private var fpGlowView: ImageView? = null
    private var fpArtView: ImageView? = null
    private var fpTitle: TextView? = null
    private var fpArtist: TextView? = null
    private var fpAlbum: TextView? = null
    private var fpPlayBtn: ImageView? = null
    private var fpHeartBtn: ImageView? = null
    private var fpSeekBar: SeekBar? = null
    private var fpPosTxt: TextView? = null
    private var fpDurTxt: TextView? = null
    private var fpShuffleBtn: ImageView? = null
    private var fpRepeatBtn: ImageView? = null
    private var fpRepeatBadge: TextView? = null

    // ── Equalizer ─────────────────────────────────────────────────────────
    private var equalizerOverlay: FrameLayout? = null
    private var eqPanel: LinearLayout? = null
    private var eqEnabledSwitch: Switch? = null
    private var eqPresetRow: LinearLayout? = null
    private var eqPreampBar: SeekBar? = null
    private var eqPreampValue: TextView? = null
    private var eqStatusLabel: TextView? = null
    private var eqContextArt: ImageView? = null
    private var eqContextTitle: TextView? = null
    private var eqContextArtist: TextView? = null
    private var eqGraphView: EqGraphView? = null
    /** Drives the band open/preset/reset animation. Cancelled on user drag. */
    private var eqBandAnimator: ValueAnimator? = null
    private var eqPreampAnimator: ValueAnimator? = null
    private var eqRenderedPreset: String? = null
    private var fpQueueList: LinearLayout? = null
    private var fpHeaderCenter: LinearLayout? = null
    private var fpContentContainer: LinearLayout? = null
    private var heroContainer: LinearLayout? = null
    private var heroPlayingView: FrameLayout? = null
    private var heroEmptyView: LinearLayout? = null
    private var heroArtView: ImageView? = null
    private var heroTitle: TextView? = null
    private var heroArtist: TextView? = null
    private var heroPosTxt: TextView? = null
    private var heroDurTxt: TextView? = null
    private var heroProgress: ProgressBar? = null
    private var heroPlayBtn: ImageView? = null
    private var heroFavBtn: ImageView? = null
    private var isShuffle = false
    private var repeatMode = RepeatMode.OFF
    private var userSeeking = false
    /** Metadata for online tracks that are queued but not yet resolved to a stream. */
    private val playableMetadataById = mutableMapOf<Long, SourceMetadata>()
    /** The current search result set, used to build a consistent playback queue. */
    private var currentSearchResults: List<SearchResult> = emptyList()

    private companion object {
        const val RC_IMPORT_MUSIC = 43
        const val DISCOVERY_SEEN_KEY = "discovery_seen"

        // Equalizer panel metrics (presentation only).
        const val EQ_GRAPH_HEIGHT_DP = 188
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restorePreferences()
        equalizerConfig = try { equalizerStore.load() } catch (_: Exception) { EqualizerConfig.default() }

        @Suppress("DEPRECATION")
        window.statusBarColor = bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = navBg

        val root = FrameLayout(this).apply { setBackgroundColor(bg) }

        root.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, dp(260))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(80, 124, 92, 252), Color.TRANSPARENT)
            )
        })

        contentContainer = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        root.addView(contentContainer, FrameLayout.LayoutParams(MP, MP).also { it.bottomMargin = dp(128) })

        miniPlayerBar = buildMiniPlayer()
        root.addView(miniPlayerBar, FrameLayout.LayoutParams(MP, dp(74)).also {
            it.gravity = Gravity.BOTTOM
            it.bottomMargin = dp(78)
            it.leftMargin = dp(12)
            it.rightMargin = dp(12)
        })

        root.addView(buildBottomNav(), FrameLayout.LayoutParams(MP, dp(74)).also { it.gravity = Gravity.BOTTOM })

        fullPlayerOverlay = buildFullPlayerOverlay()
        root.addView(fullPlayerOverlay, FrameLayout.LayoutParams(MP, MP))

        queueOverlay = buildQueueOverlay()
        root.addView(queueOverlay, FrameLayout.LayoutParams(MP, MP))

        equalizerOverlay = buildEqualizerOverlay()
        root.addView(equalizerOverlay, FrameLayout.LayoutParams(MP, MP))

        setContentView(root)
        window.decorView.rootWindowInsets?.let { applyPlayerInsets(it) }
        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            applyPlayerInsets(insets)
            insets
        }
        downloadManager.subscribe { progress ->
            runOnUiThread {
                updateSearchDownloadButton(progress)
                if (activeDownloadView && libraryContainer != null) {
                    showDownloadsScreen()
                }
                if (progress.state == DownloadState.COMPLETED && completedDownloadNotifications.add(progress.jobId)) {
                    // A finished download added a TrackEntity; refresh the library
                    // so it appears under Downloaded without a manual reload.
                    libraryNeedsRefresh = true
                    if (selectedTab == 3) {
                        libraryNeedsRefresh = false
                        loadLibrary()
                    }
                }
            }
        }
        // Safe runtime diagnostics: booleans only, never secrets or URLs.
        android.util.Log.d("AuroraSC", "launch soundcloud configured=" + soundCloudSource.isConfigured() +
            " signedIn=" + soundCloudSource.isSignedIn())
        showTab(0)
        checkAndLoad()
        handleAuthCallback(intent)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PlaybackService::class.java)
        startService(intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            svc?.listener = null
            unbindService(conn)
            bound = false
            svc = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    /** Routes an `aurora://<provider>/callback` OAuth redirect to its provider. */
    private fun handleAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "aurora") return
        when (data.host) {
            "soundcloud" -> handleSoundCloudCallback(data)
            "spotify" -> handleSpotifyCallback(data)
        }
    }

    /**
     * Handles the aurora://soundcloud/callback deep link that the browser
     * redirects to after OAuth consent. Exchanges the authorization code for
     * a token session on a background thread.
     */
    private fun handleSoundCloudCallback(data: Uri) {
        // Safe diagnostics: presence flags only, never the code/state values.
        android.util.Log.d("AuroraSC", "callback received path=" + data.path +
            " hasCode=" + (data.getQueryParameter("code") != null) +
            " hasError=" + (data.getQueryParameter("error") != null) +
            " hasState=" + (data.getQueryParameter("state") != null))
        val error = data.getQueryParameter("error")
        if (error != null) {
            Toast.makeText(this, "SoundCloud sign-in failed: $error", Toast.LENGTH_LONG).show()
            return
        }
        val code = data.getQueryParameter("code")
        if (code == null) {
            Toast.makeText(this, "SoundCloud sign-in returned no authorization code", Toast.LENGTH_LONG).show()
            return
        }
        val state = data.getQueryParameter("state")
        Thread {
            val result = soundCloudSource.exchangeAuthorizationCode(code, state)
            if (result.success) {
                // Mirror the account's SoundCloud likes into the local
                // favorite cache so hearts render correctly (local set stays
                // the cache; SoundCloud stays the source of truth for likes).
                mirrorSoundCloudLikes()
            }
            runOnUiThread {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                refreshSoundCloudSettings()
            }
        }.start()
    }

    /**
     * Handles the aurora://spotify/callback deep link and completes the
     * Authorization Code + PKCE exchange (no client secret).
     */
    private fun handleSpotifyCallback(data: Uri) {
        val error = data.getQueryParameter("error")
        if (error != null) {
            Toast.makeText(this, "Spotify sign-in failed: $error", Toast.LENGTH_LONG).show()
            return
        }
        val code = data.getQueryParameter("code")
        if (code == null) {
            Toast.makeText(this, "Spotify sign-in returned no authorization code", Toast.LENGTH_LONG).show()
            return
        }
        val state = data.getQueryParameter("state")
        Thread {
            val result = spotifySource.exchangeAuthorizationCode(code, state)
            runOnUiThread {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                refreshSpotifySettings()
            }
        }.start()
    }

    /**
     * Best-effort mirror of the signed-in account's SoundCloud likes into the
     * local favorite set for UI/cache. Unknown (null) means offline or auth
     * failure and is left alone honestly — never treated as "no likes".
     * Runs network I/O; call off the main thread.
     */
    private fun mirrorSoundCloudLikes() {
        val liked = try {
            soundCloudSource.getLikedTrackIds()
        } catch (_: Exception) {
            null
        } ?: return
        if (liked.isEmpty()) return
        // Store remote likes as source favorites (no metadata is available for
        // a bare id). Runs on a background thread; refresh the cached hearts
        // afterwards.
        liked.forEach { value ->
            try {
                favoriteRepository.addSourceFavorite(soundCloudSource.sourceId, value)
            } catch (_: Exception) {
            }
        }
        favoriteKeys = favoriteRepository.favoriteKeys()
        runOnUiThread {
            refreshFavoriteHearts()
            refreshQuickAccess()
        }
    }

    /** Opens the official SoundCloud consent page (authorization code + PKCE). */
    private fun startSoundCloudConnect() {
        android.util.Log.d("AuroraSC", "connect pressed configured=" + soundCloudSource.isConfigured() +
            " signedIn=" + soundCloudSource.isSignedIn())
        if (!soundCloudSource.isConfigured()) {
            Toast.makeText(this, "SoundCloud is not configured in this build", Toast.LENGTH_SHORT).show()
            return
        }
        val authUrl = try {
            soundCloudSource.buildAuthorizationUrl()
        } catch (e: IllegalStateException) {
            Toast.makeText(this, e.message ?: "SoundCloud is not configured", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, authUrl))
        } catch (_: Exception) {
            Toast.makeText(this, "No app can open the SoundCloud sign-in page", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (equalizerOverlay?.visibility == View.VISIBLE) {
            hideEqualizer()
            return
        }
        if (queueOverlay?.visibility == View.VISIBLE) {
            hideQueue()
            return
        }
        if (fullPlayerOverlay?.visibility == View.VISIBLE) {
            hideFullPlayer()
            return
        }
        if (selectedTab != 0) {
            showTab(0)
            return
        }
        super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_IMPORT_MUSIC) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            if (resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this, "Import cancelled", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri
                if (uri != null) uris.add(uri)
            }
        } else {
            data.data?.let { uris.add(it) }
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "No audio files selected", Toast.LENGTH_SHORT).show()
            return
        }

        uris.forEach { uri ->
            FilePickerIntentFactory.persistReadPermission(this, uri)
        }

        importSelectedFiles(uris)
    }

    private fun restorePreferences() {
        isShuffle = preferences.getBoolean("shuffle_enabled", false)
        repeatMode = when (preferences.getString("repeat_mode", null)) {
            "ALL" -> RepeatMode.ALL
            "ONE" -> RepeatMode.ONE
            "OFF" -> RepeatMode.OFF
            // Migrate the legacy boolean: ON used to repeat the current track.
            else -> if (preferences.getBoolean("repeat_enabled", false)) RepeatMode.ALL else RepeatMode.OFF
        }
    }

    // Favorites are no longer persisted in SharedPreferences: Room is the one
    // source of truth. This still stores the playback-only prefs.
    private fun persistState() {
        preferences.edit()
            .putBoolean("shuffle_enabled", isShuffle)
            .putString("repeat_mode", repeatMode.name)
            .putString("recent_track_ids", recents.joinToString(",") { it.id.toString() })
            .apply()
    }

    /**
     * One-time migration of the pre-Room `favorite_track_ids` preference into
     * the unified favorites table. Runs off the main thread; the preference is
     * removed afterwards so it never becomes a second source of truth.
     */
    private fun migrateLegacyFavorites() {
        val legacy = preferences.getStringSet("favorite_track_ids", null) ?: return
        val ids = legacy.mapNotNull { it.toLongOrNull() }
        ids.forEach { id ->
            val entity = try { database.trackDao().getById(id) } catch (_: Exception) { null }
                ?: return@forEach
            favoriteRepository.add(entity.toTrack())
        }
        preferences.edit().remove("favorite_track_ids").apply()
    }

    private fun checkAndLoad() {
        // Aurora owns its library (explicit imports + explicit downloads), so
        // no device-wide audio permission is needed to load it.
        loadLibrary()
    }

    private fun openImportPicker() {
        val intent = FilePickerIntentFactory.openAudioPicker(multiple = true)
        startActivityForResult(intent, RC_IMPORT_MUSIC)
    }

    private fun importSelectedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val importMessage = if (uris.size == 1) "Importing 1 file..." else "Importing ${uris.size} files..."
        showImportLoadingState(importMessage)

        Thread {
            val results = importManager.importFromUris(uris)
            val imported = results.count { it.status == ImportStatus.SUCCESS }
            val duplicates = results.count { it.status == ImportStatus.DUPLICATE }
            val failed = results.count { it.status != ImportStatus.SUCCESS && it.status != ImportStatus.DUPLICATE }

            runOnUiThread {
                val summary = buildString {
                    if (imported > 0) append("Imported\n$imported tracks\n")
                    if (duplicates > 0) append("Skipped\n$duplicates duplicates\n")
                    if (failed > 0) append("Failed\n$failed tracks")
                    if (imported == 0 && duplicates == 0 && failed == 0) append("No files processed")
                }
                if (imported > 0 || duplicates > 0 || failed > 0) {
                    AlertDialog.Builder(this)
                        .setTitle(if (failed == 0 && duplicates == 0) "Import complete" else "Import finished")
                        .setMessage(summary.trim())
                        .setPositiveButton("OK", null)
                        .show()
                }

                loadLibrary()
            }
        }.start()
    }

    private fun showImportLoadingState(message: String) {
        libraryContainer?.removeAllViews()
        libraryContainer?.addView(buildStateCard("Importing music", message, true))
    }

    private fun loadLibrary() {
        showLoadingState()
        Thread {
            try {
                // Fold any pre-Room favorites into the unified table once.
                migrateLegacyFavorites()
                val favorites = favoriteRepository.favoriteKeys()
                // Aurora-owned library only: explicit user imports + explicit
                // Aurora downloads + explicitly saved online entries. Phone
                // music is never auto-adopted.
                val completedDownloads = downloadRepository.getAll()
                    .filter { it.status == "completed" }
                downloadedTrackIds = completedDownloads.mapNotNull { it.trackId }.toSet()
                // A saved remote entry is superseded (hidden) once a verified
                // local file carries the same (source, sourceTrackId) origin —
                // by a completed job or by the file itself, so removing the job
                // never resurfaces a duplicate Online row.
                val allSavedMeta = libraryRepository.getSavedOnlineMetadata()
                val supersededSavedIds = com.aurora.app.database.repositories.supersededSavedOnlineIds(
                    allSavedMeta,
                    completedDownloads,
                    libraryRepository.getDownloadedSourceKeys()
                )
                savedOnlineMeta = allSavedMeta - supersededSavedIds
                // getAuroraTracksExcluding already contains the saved entries,
                // so they are listed exactly once.
                val visibleTracks = libraryRepository.getAuroraTracksExcluding(supersededSavedIds)
                runOnUiThread {
                    favoriteKeys = favorites
                    onTracksLoaded(visibleTracks)
                }
            } catch (e: Exception) {
                runOnUiThread { showLibraryError(e.message ?: "Could not load your library.") }
            }
        }.start()
    }

    private fun onTracksLoaded(tracks: List<Track>) {
        allTracks = tracks
        if (recents.isEmpty() && tracks.isNotEmpty()) {
            recents.addAll(tracks.take(6))
        }
        if (tracks.isEmpty()) {
            showEmptyState()
            return
        }
        populateAllViews(tracks)
    }

    private fun showLoadingState() {
        libraryContainer?.removeAllViews()
        libraryContainer?.addView(buildStateCard("Loading your Aurora library...", "Reading imported and downloaded tracks", true))
    }

    private fun showEmptyState() {
        libraryContainer?.removeAllViews()
        libraryContainer?.addView(buildStateCard("Your Aurora library is empty", "Import music files or download tracks to start your collection.", false))
    }

    private fun showLibraryError(message: String) {
        libraryContainer?.removeAllViews()
        libraryContainer?.addView(
            buildActionCard("Could not load library", message, "Retry") { loadLibrary() }
        )
    }

    private fun buildActionCard(title: String, message: String, buttonText: String, onClick: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 18)
            setPadding(dp(20), dp(22), dp(20), dp(22))
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(16) }
        }
        card.addView(label(title, 16, text, true))
        card.addView(vGap(8))
        card.addView(label(message, 12, textSecondary, false))
        card.addView(vGap(16))
        val btn = FrameLayout(this).apply {
            background = rounded(purple, 12)
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { onClick() }
        }
        btn.addView(label(buttonText, 14, bg, true).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(WC, WC).also { it.gravity = Gravity.CENTER }
            setPadding(dp(18), dp(10), dp(18), dp(10))
        })
        card.addView(btn)
        return card
    }

    private fun showTab(tabIndex: Int) {
        selectedTab = tabIndex
        contentContainer?.removeAllViews()
        val view = when (tabIndex) {
            0 -> getOrCreateHomeView()
            1 -> getOrCreateSearchView()
            2 -> getOrCreateDiscoverView()
            3 -> getOrCreateLibraryView()
            4 -> getOrCreateSettingsView()
            else -> getOrCreateHomeView()
        }
        view.alpha = 0f
        contentContainer?.addView(view, FrameLayout.LayoutParams(MP, MP))
        view.animate().alpha(1f).setDuration(180).start()
        updateNavSelection()
        if (tabIndex == 0) {
            refreshHero()
            refreshHomeSections()
        }
        if (tabIndex == 3 && libraryNeedsRefresh) {
            libraryNeedsRefresh = false
            loadLibrary()
        }
    }

    private fun onTrackChanged(track: Track) {
        miniTitle?.text = track.title
        miniArtist?.text = track.artist
        miniArtView?.let { ArtworkLoader.loadArtwork(this, track, dp(48), it) }
        miniProgress?.max = track.duration.toInt().coerceAtLeast(1)
        miniProgress?.progress = 0

        fpTitle?.text = track.title
        fpArtist?.text = track.artist
        fpAlbum?.text = track.album
        fpDurTxt?.text = track.durationLabel
        fpBackdropView?.let { ArtworkLoader.loadArtwork(this, track, 512, it) }
        fpGlowView?.let { ArtworkLoader.loadArtwork(this, track, 512, it) }
        fpArtView?.let { ArtworkLoader.loadArtwork(this, track, 512, it) }
        fpSeekBar?.max = track.duration.toInt().coerceAtLeast(1)
        fpSeekBar?.progress = 0
        fpPosTxt?.text = "0:00"

        val isFav = isFavorite(track)
        fpHeartBtn?.imageTintList = ColorStateList.valueOf(if (isFav) purple else textMuted)
        heroFavBtn?.imageTintList = ColorStateList.valueOf(if (isFav) purple else textMuted)

        val wasFirst = recents.firstOrNull()?.id == track.id
        recents.removeAll { it.id == track.id }
        recents.add(0, track)
        while (recents.size > 8) recents.removeAt(recents.lastIndex)
        // Only write preferences when the recents list actually changed; doing
        // it on every track change was redundant main-thread work.
        if (!wasFirst) persistState()
        // Only touch visible surfaces; rebuilding the queue/recents on every
        // track change is what made playback actions feel sluggish. The recents
        // row only changes when this track was not already first.
        if (selectedTab == 0) {
            if (!wasFirst) updateRecentsUI()
            updateHeroTrack(track)
        }
        if (queueOverlay?.visibility == View.VISIBLE) updateQueueUI()
        if (isFullPlayerOpen) updateFullPlayerQueue()
        syncMiniPlayerVisibility()
    }

    /** Queue contents/order/cursor changed in the service (enqueue, remove, jump). */
    private fun onQueueChanged() {
        if (queueOverlay?.visibility == View.VISIBLE) updateQueueUI()
        if (isFullPlayerOpen) updateFullPlayerQueue()
    }

    /** A track could not be played: say why instead of failing silently. */
    private fun onPlaybackError(message: String) {
        if (message.isBlank()) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun onPlayStateChanged(isPlaying: Boolean) {
        val res = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        miniPlayBtn?.setImageResource(res)
        miniPlayBtn?.imageTintList = ColorStateList.valueOf(if (isPlaying) text else purple)
        fpPlayBtn?.setImageResource(res)
        fpPlayBtn?.background = circle(purple)
        heroPlayBtn?.setImageResource(res)
        // Refresh the "Now playing / Paused" label only while the queue is open;
        // the incremental queue update is cheap when the content is unchanged.
        if (queueOverlay?.visibility == View.VISIBLE) updateQueueUI()
    }

    /** Reflects the three-state repeat mode on the Full Player control. */
    private fun renderRepeatButton() {
        val active = repeatMode != RepeatMode.OFF
        fpRepeatBtn?.imageTintList = ColorStateList.valueOf(if (active) purple else textMuted)
        fpRepeatBtn?.contentDescription = when (repeatMode) {
            RepeatMode.OFF -> "Repeat off"
            RepeatMode.ALL -> "Repeat all"
            RepeatMode.ONE -> "Repeat one"
        }
        fpRepeatBadge?.visibility = if (repeatMode == RepeatMode.ONE) View.VISIBLE else View.GONE
    }

    private fun onProgressUpdate(posMs: Int, durMs: Int) {
        val pos = posMs.coerceAtLeast(0)
        // Streams can report no duration; fall back to the service, then to the
        // metadata we already have, and stay honest when none is known.
        val known = when {
            durMs > 0 -> durMs
            (svc?.durationMs ?: 0) > 0 -> svc?.durationMs ?: 0
            else -> (svc?.currentTrack?.duration ?: 0L).toInt()
        }

        // Only touch surfaces that are actually on screen. The mini player is
        // always visible; the Home hero and Full Player are not. This keeps the
        // 500 ms progress tick from invalidating off-screen views.
        val homeVisible = selectedTab == 0 && heroContainer != null
        val fullPlayerVisible = isFullPlayerOpen

        if (known <= 0) {
            if (miniProgress?.max != 1) miniProgress?.max = 1
            miniProgress?.progress = 0
            if (homeVisible) {
                if (heroProgress?.max != 1) heroProgress?.max = 1
                heroProgress?.progress = 0
                setTextIfChanged(heroPosTxt, msToLabel(pos))
                setTextIfChanged(heroDurTxt, "--:--")
            }
            if (fullPlayerVisible) {
                if (!userSeeking) {
                    if (fpSeekBar?.max != 1) fpSeekBar?.max = 1
                    fpSeekBar?.progress = 0
                    setTextIfChanged(fpPosTxt, msToLabel(pos))
                }
                setTextIfChanged(fpDurTxt, "--:--")
            }
            return
        }

        // Avoid redundant invalidations: only push max when it really changed.
        if (miniProgress?.max != known) miniProgress?.max = known
        miniProgress?.progress = pos

        if (homeVisible) {
            if (heroProgress?.max != known) heroProgress?.max = known
            heroProgress?.progress = pos
            setTextIfChanged(heroPosTxt, msToLabel(pos))
            setTextIfChanged(heroDurTxt, msToLabel(known))
        }

        if (fullPlayerVisible) {
            if (!userSeeking) {
                if (fpSeekBar?.max != known) fpSeekBar?.max = known
                fpSeekBar?.progress = pos
                setTextIfChanged(fpPosTxt, msToLabel(pos))
            }
            setTextIfChanged(fpDurTxt, msToLabel(known))
        }
    }

    /** Sets [view] text only when it actually changes, avoiding needless relayouts. */
    private fun setTextIfChanged(view: TextView?, value: String) {
        if (view != null && !TextUtils.equals(view.text, value)) view.text = value
    }

    private fun getOrCreateHomeView(): View {
        if (homeScroll != null) return homeScroll!!
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        page.addView(buildHomeHeader())
        page.addView(buildQuickAccess())
        page.addView(buildContinueSection())
        page.addView(buildDiscoverCta())
        page.addView(buildHomeLibrary())
        page.addView(vGap(8))

        scroll.addView(page)
        homeScroll = scroll
        if (allTracks.isNotEmpty()) populateAllViews(allTracks)
        return scroll
    }

    private fun buildHomeHeader(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(48), dp(20), dp(8))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        textCol.addView(label("AURORA", 11, purple, true).apply { letterSpacing = 0.22f })
        textCol.addView(vGap(6))
        textCol.addView(label(homeGreeting(), 22, text, true))
        textCol.addView(vGap(4))
        textCol.addView(label("Music always feels a little better at night.", 13, textSecondary, false))

        val searchBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_search)
            imageTintList = ColorStateList.valueOf(textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).also { it.rightMargin = dp(16) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { showTab(1) }
            contentDescription = "Search"
        }
        val settingsBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_settings)
            imageTintList = ColorStateList.valueOf(textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { showTab(4) }
            contentDescription = "Settings"
        }
        row.addView(textCol)
        row.addView(searchBtn)
        row.addView(settingsBtn)
        return row
    }

    private fun homeGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val part = when (hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$part, David."
    }

    private fun buildQuickAccess(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        section.addView(buildSectionHeader("Quick Access", false))
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        quickGrid = grid
        section.addView(grid)
        section.addView(vGap(20))
        refreshQuickAccess()
        return section
    }

    private fun refreshQuickAccess() {
        val grid = quickGrid ?: return
        grid.removeAllViews()
        val likedCount = favoriteKeys.size
        val recentCount = recents.size
        val downloadCount = allTracks.count { trackBucket(it) == TrackFilter.DOWNLOADED }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(quickCard(R.drawable.ic_heart, "Liked", songsLabel(likedCount), 1f, 0) {
            val firstLiked = allTracks.firstOrNull { isFavorite(it) }
            if (firstLiked != null) triggerPlay(firstLiked) else showTab(3)
        })
        top.addView(hGap(10))
        top.addView(quickCard(R.drawable.ic_waveform, "Recently Played", songsLabel(recentCount), 1f, 0) {
            val latest = recents.firstOrNull()
            if (latest != null) triggerPlay(latest) else showTab(3)
        })
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(10) }
        }
        bottom.addView(quickCard(R.drawable.ic_nav_library, "Downloads", songsLabel(downloadCount), 1f, 0) {
            showTab(3)
            showDownloadsScreen()
        })
        bottom.addView(hGap(10))
        bottom.addView(quickCard(R.drawable.ic_nav_discover, "Discover", if (soundCloudSource.isConfigured()) "Fresh picks" else "Not connected", 1f, 0) {
            showTab(2)
        })
        grid.addView(top)
        grid.addView(bottom)
    }

    private fun songsLabel(count: Int): String = if (count == 1) "1 song" else "$count songs"

    private fun quickCard(iconRes: Int, title: String, subtitle: String, weight: Float, leftMarginDp: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 18).apply {
                setStroke(dp(1), Color.argb(28, 124, 92, 252))
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(0, WC, weight).also { if (leftMarginDp > 0) it.leftMargin = dp(leftMarginDp) }
            clipToOutline = true
            outlineProvider = roundRectOutline(18)
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { onClick() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(purple)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).also { it.rightMargin = dp(12) }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                addView(label(title, 14, text, true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(vGap(2))
                addView(label(subtitle, 11, textSecondary, false).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            })
        }
    }

    private fun buildContinueSection(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(buildSectionHeader("Continue Listening", true) { showTab(3) })
        val wrap = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(24), 0, dp(24), 0)
            clipToPadding = false
        }
        recentsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wrap.addView(recentsRow)
        section.addView(wrap)
        section.addView(vGap(20))
        updateRecentsUI()
        return section
    }

    private fun buildDiscoverCta(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        section.addView(buildSectionHeader("Discover", true) { showTab(2) })
        val card = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(88, 58, 180), Color.rgb(22, 18, 36))
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.argb(60, 255, 255, 255))
            }
            clipToOutline = true
            outlineProvider = roundRectOutline(22)
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { showTab(2) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        col.addView(label("Random Discovery", 18, text, true))
        col.addView(vGap(6))
        discoverCtaSubtitle = label(discoverCtaText(), 12, textSecondary, false)
        col.addView(discoverCtaSubtitle)
        col.addView(vGap(14))
        val pill = FrameLayout(this).apply {
            background = rounded(Color.argb(235, 245, 243, 255), 12)
            isClickable = true
            isFocusable = true
            foreground = ripple()
            layoutParams = LinearLayout.LayoutParams(WC, dp(40))
            setOnClickListener { showTab(2) }
        }
        pill.addView(label("Open Discover", 13, bg, true).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(WC, WC).also { it.gravity = Gravity.CENTER }
            setPadding(dp(20), dp(8), dp(20), dp(8))
        })
        col.addView(pill)
        card.addView(col)
        section.addView(card)
        section.addView(vGap(20))
        return section
    }

    private fun discoverCtaText(): String = if (soundCloudSource.isConfigured()) {
        "A fresh shuffled batch of playable online tracks."
    } else {
        "Connect SoundCloud in Settings to unlock online picks."
    }

    private fun buildHomeLibrary(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        section.addView(buildSectionHeader("Your Library", true) { openLibrarySection(LibrarySection.TRACKS) })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        homeLibraryRow = row
        section.addView(row)
        section.addView(vGap(20))
        refreshHomeLibrary()
        return section
    }

    private fun refreshHomeLibrary() {
        val row = homeLibraryRow ?: return
        row.removeAllViews()
        val trackCount = allTracks.size
        val albumCount = allTracks.map { it.album.trim() }.filter { it.isNotEmpty() }.distinct().size
        val artistCount = allTracks.map { it.artist.trim() }.filter { it.isNotEmpty() }.distinct().size
        row.addView(libraryCard("Tracks", songsLabel(trackCount), 1f, 0) { openLibrarySection(LibrarySection.TRACKS) })
        row.addView(hGap(10))
        row.addView(libraryCard("Albums", if (albumCount == 1) "1 album" else "$albumCount albums", 1f, 0) { openLibrarySection(LibrarySection.ALBUMS) })
        row.addView(hGap(10))
        row.addView(libraryCard("Artists", if (artistCount == 1) "1 artist" else "$artistCount artists", 1f, 0) { openLibrarySection(LibrarySection.ARTISTS) })
    }

    private fun libraryCard(title: String, subtitle: String, weight: Float, leftMarginDp: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 18).apply {
                setStroke(dp(1), Color.argb(28, 124, 92, 252))
            }
            setPadding(dp(14), dp(16), dp(14), dp(16))
            layoutParams = LinearLayout.LayoutParams(0, WC, weight).also { if (leftMarginDp > 0) it.leftMargin = dp(leftMarginDp) }
            clipToOutline = true
            outlineProvider = roundRectOutline(18)
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { onClick() }
            addView(label(title, 14, text, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(vGap(4))
            addView(label(subtitle, 11, textSecondary, false).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
    }

    private fun openLibrarySection(section: LibrarySection) {
        librarySection = section
        if (libraryView != null) {
            updateSegmentedSelection(librarySectionTabs, section.ordinal)
            renderLibrarySection()
        }
        showTab(3)
    }

    private fun refreshHomeSections() {
        refreshQuickAccess()
        updateRecentsUI()
        discoverCtaSubtitle?.text = discoverCtaText()
        refreshHomeLibrary()
    }

    private fun buildHeroSection(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        heroContainer = container
        section.addView(container)
        section.addView(vGap(20))
        refreshHero()
        return section
    }

    private fun refreshHero() {
        val container = heroContainer ?: return
        val track = svc?.currentTrack
        if (track == null) {
            if (heroEmptyView == null) heroEmptyView = buildHeroEmpty()
            if (container.childCount == 0 || container.getChildAt(0) != heroEmptyView) {
                container.removeAllViews()
                container.addView(heroEmptyView)
            }
        } else {
            updateHeroTrack(track)
            onPlayStateChanged(svc?.isPlaying == true)
            onProgressUpdate(svc?.positionMs ?: 0, svc?.durationMs ?: 0)
        }
    }

    private fun buildHeroEmpty(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 22).apply {
                setStroke(dp(1), Color.argb(32, 124, 92, 252))
            }
            setPadding(dp(18), dp(18), dp(18), dp(18))
            clipToOutline = true
            outlineProvider = roundRectOutline(22)
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener {
                val first = allTracks.firstOrNull()
                if (first != null) triggerPlay(first)
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_aurora_logo)
                imageTintList = ColorStateList.valueOf(purple)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).also { it.rightMargin = dp(14) }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                addView(label("Nothing playing", 16, text, true))
                addView(vGap(4))
                addView(label("Choose a song and it will appear here.", 12, textSecondary, false))
            })
        }
    }

    private fun buildHeroPlaying(): FrameLayout {
        val card = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(44, 30, 78), Color.rgb(18, 19, 26))
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.argb(45, 124, 92, 252))
            }
            clipToOutline = true
            outlineProvider = roundRectOutline(22)
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { showFullPlayer() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val artWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(104)).also { it.rightMargin = dp(14) }
            background = rounded(elevated, 16)
            clipToOutline = true
            outlineProvider = roundRectOutline(16)
        }
        val art = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        heroArtView = art
        artWrap.addView(art)
        row.addView(artWrap)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        heroTitle = label("Unknown track", 15, text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        heroArtist = label("Unknown artist", 12, textSecondary, false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(heroTitle)
        info.addView(vGap(2))
        info.addView(heroArtist)
        info.addView(vGap(10))
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(purple)
            progressBackgroundTintList = ColorStateList.valueOf(higher)
            layoutParams = LinearLayout.LayoutParams(MP, dp(3))
            max = 100
        }
        heroProgress = progress
        info.addView(progress)
        info.addView(vGap(6))
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroPosTxt = label("0:00", 11, textMuted, false)
        heroDurTxt = label("0:00", 11, textMuted, false)
        times.addView(heroPosTxt)
        times.addView(spacerH())
        times.addView(heroDurTxt)
        info.addView(times)
        info.addView(vGap(10))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prev = ImageView(this).apply {
            setImageResource(R.drawable.ic_skip_previous)
            imageTintList = ColorStateList.valueOf(textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).also { it.rightMargin = dp(12) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { svc?.previous() }
            contentDescription = "Previous track"
        }
        val play = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            background = rounded(purple, 16)
            imageTintList = ColorStateList.valueOf(text)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).also { it.rightMargin = dp(12) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { svc?.togglePlayPause() }
            contentDescription = "Play or pause"
        }
        heroPlayBtn = play
        val next = ImageView(this).apply {
            setImageResource(R.drawable.ic_skip_next)
            imageTintList = ColorStateList.valueOf(textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).also { it.rightMargin = dp(12) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { svc?.next() }
            contentDescription = "Next track"
        }
        val fav = ImageView(this).apply {
            setImageResource(R.drawable.ic_heart)
            imageTintList = ColorStateList.valueOf(textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener {
                val cur = svc?.currentTrack ?: return@setOnClickListener
                toggleFavorite(cur)
            }
            contentDescription = "Favorite"
        }
        heroFavBtn = fav
        controls.addView(prev)
        controls.addView(play)
        controls.addView(next)
        controls.addView(fav)
        info.addView(controls)
        row.addView(info)
        card.addView(row)
        return card
    }

    private fun updateHeroTrack(track: Track) {
        val container = heroContainer ?: return
        if (heroPlayingView == null) heroPlayingView = buildHeroPlaying()
        if (container.childCount == 0 || container.getChildAt(0) != heroPlayingView) {
            container.removeAllViews()
            container.addView(heroPlayingView)
        }
        heroTitle?.text = track.title.ifBlank { "Unknown track" }
        heroArtist?.text = track.artist.ifBlank { "Unknown artist" }
        heroArtView?.let { ArtworkLoader.loadArtwork(this, track, dp(104), it) }
        heroProgress?.max = track.duration.toInt().coerceAtLeast(1)
        heroDurTxt?.text = track.durationLabel
        heroFavBtn?.imageTintList = ColorStateList.valueOf(if (isFavorite(track)) purple else textMuted)
    }

    private fun buildSectionHeader(title: String, showArrow: Boolean, onAction: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        row.addView(label(title, 12, textSecondary, true).apply {
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        })
        if (showArrow) {
            val action = label("›", 16, purple, true)
            if (onAction != null) {
                action.isClickable = true
                action.foreground = ripple()
                action.setOnClickListener { onAction() }
            }
            row.addView(action)
        }
        return row
    }

    private fun getOrCreateSearchView(): View {
        if (searchView != null) return searchView!!

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        page.addView(label("Search", 28, text, true))
        page.addView(vGap(8))
        page.addView(label("Search your library and every connected source.", 13, textSecondary, false))
        page.addView(vGap(12))

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 18).apply {
                setStroke(dp(1), Color.argb(32, 124, 92, 252))
            }
            setPadding(dp(14), dp(10), dp(12), dp(10))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_search)
            imageTintList = ColorStateList.valueOf(purple)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        val input = EditText(this).apply {
            hint = "Search Aurora..."
            setHintTextColor(textMuted)
            setTextColor(this@MainActivity.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterSearch(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val clearBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).also { it.leftMargin = dp(6) }
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { input.setText("") }
            contentDescription = "Clear search"
            visibility = View.GONE
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        searchBox.addView(icon)
        searchBox.addView(hGap(10))
        searchBox.addView(input)
        searchBox.addView(clearBtn)
        page.addView(searchBox)
        searchInput = input
        page.addView(vGap(16))

        searchStatusContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchResultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(searchStatusContainer)
        page.addView(searchResultsContainer)
        runSearch("")
        scroll.addView(page)
        searchView = scroll
        return scroll
    }

    // ── Discover: random online discovery through the configured source ──────

    /**
     * Builds the Discover screen: a [Start Discovery] action that asks the
     * configured online source for a fresh, shuffled batch of playable tracks
     * that the user does not already have.
     */
    private fun getOrCreateDiscoverView(): View {
        if (discoverView != null) return discoverView!!

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(24), dp(48), dp(24), 0)
        }
        page.addView(label("Discover", 28, text, true))
        page.addView(vGap(8))
        page.addView(label("Find something new.", 13, textSecondary, false))
        page.addView(vGap(16))
        page.addView(buildPrimaryButton("Start Discovery") { runDiscovery() })
        page.addView(vGap(16))

        discoverStatusContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        discoverResultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(discoverStatusContainer)
        page.addView(discoverResultsContainer)
        scroll.addView(page)
        discoverView = scroll
        renderDiscoverIdle()
        return scroll
    }

    private fun renderDiscoverIdle() {
        val status = discoverStatusContainer ?: return
        status.removeAllViews()
        status.visibility = View.VISIBLE
        discoverResultsContainer?.visibility = View.GONE
        status.addView(
            buildStateCard(
                "Random Discovery",
                "Pulls a fresh, shuffled batch of online tracks that are playable from the source.",
                false
            )
        )
    }

    /** A discovery batch outcome; keeps search states honest (no silent empties). */
    private sealed class DiscoverOutcome {
        data class Success(val results: List<SearchResult>) : DiscoverOutcome()
        data class Empty(val message: String) : DiscoverOutcome()
        data class Error(val message: String) : DiscoverOutcome()
        object NotConfigured : DiscoverOutcome()
    }

    private fun runDiscovery() {
        val status = discoverStatusContainer ?: return
        val results = discoverResultsContainer ?: return
        results.removeAllViews()
        status.removeAllViews()
        results.visibility = View.GONE
        status.visibility = View.VISIBLE

        if (!soundCloudSource.isConfigured()) {
            status.addView(
                buildActionCard(
                    "SoundCloud is not available",
                    "Connect SoundCloud in Settings to discover new online tracks.",
                    "Open Settings"
                ) { showTab(4) }
            )
            return
        }

        status.addView(buildStateCard("Discovering…", "Looking for tracks you have not heard yet", true))
        val requestId = ++discoveryRequestToken
        Thread {
            val outcome = discoverOnline()
            runOnUiThread {
                if (requestId != discoveryRequestToken) return@runOnUiThread
                renderDiscoverOutcome(outcome)
            }
        }.start()
    }

    /**
     * Queries several discovery "buckets" on the configured source, merges and
     * dedupes the hits, drops anything already known or previously discovered,
     * keeps only playable tracks, then shuffles and trims the batch.
     */
    private fun discoverOnline(): DiscoverOutcome {
        if (!soundCloudSource.isConfigured()) return DiscoverOutcome.NotConfigured
        val buckets = listOf(
            "electronic", "lofi", "hip hop", "jazz", "ambient", "house", "chill",
            "indie", "rock", "classical", "pop", "soul", "techno", "piano",
            "acoustic", "r&b", "downtempo", "soundtrack"
        ).shuffled().take(5)

        val seen = discoverySeen()
        val collected = LinkedHashMap<String, SourceMetadata>()
        var successes = 0
        var lastError: String? = null
        buckets.forEach { query ->
            when (val outcome = soundCloudSource.searchDetailed(query)) {
                is SourceSearchResult.Success -> {
                    successes++
                    outcome.results.forEach { metadata ->
                        collected.putIfAbsent(metadata.trackId.value, metadata)
                    }
                }
                is SourceSearchResult.NotConfigured -> return DiscoverOutcome.NotConfigured
                is SourceSearchResult.Error -> lastError = outcome.message
                is SourceSearchResult.Empty -> Unit
            }
        }
        if (successes == 0) {
            return DiscoverOutcome.Error(lastError ?: "Could not reach SoundCloud right now")
        }

        val playableMetadata = collected.values.filter { metadata ->
            metadata.trackId.value !in seen &&
                (metadata.sourceCapabilities.contains(SourceCapability.STREAM) ||
                    metadata.sourceCapabilities.contains(SourceCapability.PREVIEW))
        }
        val knownTitles = allTracks
        val fresh = playableMetadata.filterNot { metadata ->
            knownTitles.any {
                it.title.equals(metadata.title, ignoreCase = true) &&
                    (metadata.artist.isBlank() || it.artist.equals(metadata.artist, ignoreCase = true))
            }
        }
        val sourcePool = if (fresh.size >= 10) fresh else playableMetadata
        if (sourcePool.isEmpty()) {
            return DiscoverOutcome.Empty("No new tracks right now — try again in a moment.")
        }
        val batch = sourcePool.shuffled().take(15)
        val mapped = mapSearchResults(batch).filter { it.playable }
        if (mapped.isEmpty()) {
            return DiscoverOutcome.Empty("No playable tracks were found this round — try again.")
        }
        rememberDiscovered(mapped.map { it.metadata.trackId.value })
        return DiscoverOutcome.Success(mapped)
    }

    private fun renderDiscoverOutcome(outcome: DiscoverOutcome) {
        val status = discoverStatusContainer ?: return
        val results = discoverResultsContainer ?: return
        status.removeAllViews()
        results.removeAllViews()
        when (outcome) {
            is DiscoverOutcome.Success -> {
                status.visibility = View.GONE
                results.visibility = View.VISIBLE
                results.addView(label("${outcome.results.size} new tracks", 13, text, true))
                results.addView(vGap(10))
                outcome.results.forEachIndexed { index, entry ->
                    results.addView(buildSearchResultRow(entry))
                    if (index < outcome.results.lastIndex) results.addView(dividerRow())
                }
                results.addView(vGap(14))
                results.addView(buildPrimaryButton("Shuffle Again") { runDiscovery() })
            }
            is DiscoverOutcome.Empty -> {
                results.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.addView(buildStateCard("Nothing new yet", outcome.message, false))
            }
            is DiscoverOutcome.Error -> {
                results.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.addView(buildActionCard("Discovery failed", outcome.message, "Retry") { runDiscovery() })
            }
            DiscoverOutcome.NotConfigured -> {
                results.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.addView(
                    buildActionCard("SoundCloud is not available", "Connect SoundCloud in Settings first.", "Open Settings") { showTab(4) }
                )
            }
        }
    }

    private fun discoverySeen(): MutableSet<String> =
        preferences.getStringSet(DISCOVERY_SEEN_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun rememberDiscovered(sourceTrackIds: List<String>) {
        val merged = discoverySeen().apply { addAll(sourceTrackIds) }
        val stored = if (merged.size > 800) sourceTrackIds.toMutableSet() else merged
        preferences.edit().putStringSet(DISCOVERY_SEEN_KEY, stored).apply()
    }

    /** One rendered search hit with the capabilities the source actually grants. */
    private data class SearchResult(
        val metadata: SourceMetadata,
        val source: MusicSource,
        val sourceLabel: String,
        val track: Track,
        val playable: Boolean,
        val preview: Boolean,
        val downloadable: Boolean,
        val downloaded: Boolean,
        val unavailable: Boolean,
        /** Catalog item that opens in an external app (Spotify) rather than playing here. */
        val external: Boolean = false,
        val kind: SourceKind = SourceKind.TRACK
    )

    /** Debounces typing so a search only runs after the user pauses (no per-keystroke request). */
    private fun filterSearch(query: String) {
        val trimmed = query.trim()
        searchDebounce?.let { uiHandler.removeCallbacks(it) }
        if (trimmed.isEmpty()) {
            runSearch("")
            return
        }
        val runnable = Runnable { runSearch(trimmed) }
        searchDebounce = runnable
        uiHandler.postDelayed(runnable, 350L)
    }

    /** Resolves a MusicSource from a stored source id (queue retry, remote playback). */
    private fun sourceForId(sourceId: String): MusicSource = when (sourceId) {
        audiusSource.sourceId -> audiusSource
        soundCloudSource.sourceId -> soundCloudSource
        spotifySource.sourceId -> spotifySource
        else -> localSource
    }

    /**
     * Runs the single unified search. Local results are reported first and
     * online providers populate progressively; one provider failing never
     * removes another provider's results.
     */
    private fun runSearch(query: String) {
        val results = searchResultsContainer ?: return
        val status = searchStatusContainer ?: return
        searchDownloadButtons.clear()
        results.removeAllViews()
        status.removeAllViews()
        results.visibility = View.GONE
        status.visibility = View.VISIBLE
        if (query.isBlank()) {
            status.addView(buildStateCard("Search Aurora", "Search your library and every connected source.", false))
            return
        }

        val requestId = ++searchRequestToken
        status.addView(buildStateCard("Searching…", "Looking across your library and online sources", true))
        Thread {
            // Load the local Aurora index once for the whole search rather than
            // re-querying the database for every provider update.
            val auroraTracks = try {
                libraryRepository.getAllTracks().filter { it.isAuroraImported || it.sourceType == "aurora_imported" }
            } catch (_: Exception) {
                emptyList()
            }
            unifiedSearchService.search(query) { outcome, complete ->
                val mapped = mapSearchResults(outcome.results, auroraTracks)
                runOnUiThread {
                    if (requestId != searchRequestToken) return@runOnUiThread
                    renderUnifiedSearchOutcome(outcome, mapped, query, complete)
                }
            }
        }.start()
    }

    /**
     * Background-thread mapping: resolves "already downloaded" and capability
     * flags per hit. [auroraTracks] may be supplied so a progressive unified
     * search queries the local index once instead of once per provider update.
     */
    private fun mapSearchResults(
        results: List<SourceMetadata>,
        auroraTracks: List<com.aurora.app.database.entities.TrackEntity>? = null
    ): List<SearchResult> {
        val localIndex = auroraTracks ?: try {
            libraryRepository.getAllTracks().filter { it.isAuroraImported || it.sourceType == "aurora_imported" }
        } catch (_: Exception) {
            emptyList()
        }
        return results.map { metadata ->
            val isLocal = metadata.trackId.source == localSource.sourceId
            val localCopy: File? = if (isLocal) {
                metadata.localPath?.let { File(it) }?.takeIf { it.exists() }
            } else {
                // Match by stable source identity, never by title/artist, so a
                // different song that happens to share a title is not treated
                // as already downloaded and a real local copy is always found.
                localIndex.firstOrNull {
                    it.source == metadata.trackId.source &&
                        it.sourceTrackId == metadata.trackId.value
                }?.localPath?.let { File(it) }?.takeIf { it.exists() }
            }
            val downloaded = localCopy != null
            // Downloaded bytes are authoritative: a track the remote source now
            // reports as blocked/unavailable still plays from the local copy.
            val availability = com.aurora.app.source.resolveSearchAvailability(
                capabilities = metadata.sourceCapabilities,
                downloaded = downloaded,
                isLocal = isLocal,
                hasLocalUri = metadata.localUri != null
            )
            val remoteName = com.aurora.app.source.sourceDisplayName(metadata.trackId.source)
            val kindLabel = when (metadata.kind) {
                SourceKind.ALBUM -> "album"
                SourceKind.ARTIST -> "artist"
                SourceKind.TRACK -> null
            }
            val label = when {
                downloaded && !isLocal -> "$remoteName • downloaded"
                downloaded -> "Local • downloaded"
                isLocal -> "On device"
                kindLabel != null -> "$remoteName • $kindLabel"
                else -> remoteName
            }
            val resolvedUri = localCopy?.let { Uri.fromFile(it) } ?: metadata.localUri ?: Uri.EMPTY
            // Reuse the library Track (with its real DB id) only when a real
            // local URI exists; Uri.EMPTY is shared by every saved-online entry
            // and must never be used to match an unrelated track.
            val matched = if (resolvedUri != Uri.EMPTY) {
                allTracks.firstOrNull { it.uri == resolvedUri }
            } else {
                null
            }
            SearchResult(
                metadata = metadata,
                source = if (downloaded || isLocal) localSource else sourceForId(metadata.trackId.source),
                sourceLabel = label,
                track = matched?.copy(uri = resolvedUri) ?: Track(
                    id = stableTrackId(metadata.trackId.source, metadata.trackId.value),
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album.ifBlank {
                        if (isLocal || metadata.kind != SourceKind.TRACK) "" else remoteName
                    },
                    duration = metadata.durationMs,
                    uri = resolvedUri,
                    albumId = 0L,
                    artworkUri = metadata.artworkUri,
                    source = metadata.trackId.source,
                    sourceTrackId = metadata.trackId.value
                ),
                playable = availability.playable,
                preview = availability.preview,
                downloadable = availability.downloadable,
                downloaded = downloaded,
                unavailable = availability.unavailable,
                external = availability.external,
                kind = metadata.kind
            )
        }
    }

    /**
     * Renders the unified search: one deduplicated result list with subtle
     * source badges, plus honest per-provider notes so a Spotify failure stays
     * visible without hiding results the others returned. While online
     * providers are still resolving, a single in-progress state is shown.
     */
    private fun renderUnifiedSearchOutcome(
        outcome: UnifiedSearchOutcome,
        mapped: List<SearchResult>,
        query: String,
        complete: Boolean
    ) {
        val results = searchResultsContainer ?: return
        val status = searchStatusContainer ?: return
        results.removeAllViews()
        status.removeAllViews()
        // Keep the exact result set for queue building and remember each remote
        // track's metadata so it can be streamed later, when it becomes current.
        currentSearchResults = mapped
        mapped.forEach { entry ->
            if (entry.playable) playableMetadataById[entry.track.id] = entry.metadata
        }

        if (mapped.isNotEmpty()) {
            status.visibility = View.GONE
            results.visibility = View.VISIBLE
            results.addView(label("${mapped.size} result${if (mapped.size == 1) "" else "s"} for \"$query\"", 12, textMuted, false).apply {
                setPadding(0, 0, 0, dp(10))
            })
            mapped.forEachIndexed { index, entry ->
                results.addView(buildSearchResultRow(entry))
                if (index < mapped.lastIndex) results.addView(dividerRow())
            }
            if (!complete) {
                results.addView(vGap(8))
                results.addView(label("Searching more sources…", 11, textMuted, false))
            }
            addProviderNotes(results, outcome)
            return
        }

        // Nothing yet: keep the single loading state until every provider has
        // reported, then show one unified empty state.
        if (!complete) {
            results.visibility = View.GONE
            status.visibility = View.VISIBLE
            status.addView(buildStateCard("Searching…", "Looking across your library and online sources", true))
            return
        }

        results.visibility = View.GONE
        status.visibility = View.VISIBLE
        val failures = outcome.failedProviders
        if (failures.isNotEmpty()) {
            status.addView(buildActionCard("No results", "No results for \"$query\"", "Retry") { runSearch(query) })
            failures.forEach { report ->
                status.addView(label(
                    "${report.displayName} unavailable: ${(report.state as ProviderSearchState.Failed).message}",
                    11,
                    textMuted,
                    false
                ).apply { setPadding(0, dp(2), 0, dp(2)) })
            }
        } else {
            status.addView(buildStateCard("No results", "No results for \"$query\"", false))
        }
    }

    /** Appends a muted, honest per-provider failure line under unified results. */
    private fun addProviderNotes(container: LinearLayout, outcome: UnifiedSearchOutcome) {
        val notes = buildList {
            outcome.failedProviders.forEach {
                add("${it.displayName} unavailable: ${(it.state as ProviderSearchState.Failed).message}")
            }
            outcome.unconfiguredProviders.forEach {
                add("${it.displayName} is not configured")
            }
        }
        if (notes.isEmpty()) return
        container.addView(vGap(8))
        notes.forEach { note ->
            container.addView(label(note, 11, textMuted, false).apply {
                setPadding(0, dp(4), 0, dp(4))
            })
        }
    }

    /** A search hit with its source, metadata, capabilities and only the allowed actions. */
    private fun buildSearchResultRow(result: SearchResult): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener {
                when {
                    result.playable -> playSearchResult(result)
                    result.external -> openExternal(result.metadata)
                    else -> Toast.makeText(this@MainActivity, "This track is not playable from its source.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val art = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).also { it.rightMargin = dp(12) }
            background = rounded(surface, 12)
            clipToOutline = true
            outlineProvider = roundRectOutline(12)
        }
        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = roundRectOutline(12)
        }
        ArtworkLoader.loadArtwork(this, result.track, dp(52), iv, result.metadata.artworkUri)
        art.addView(iv)
        top.addView(art)

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        meta.addView(label(result.track.title.ifBlank { "Unknown track" }, 14, text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        meta.addView(vGap(2))
        val subtitle = buildString {
            append(result.track.artist.ifBlank { "Unknown artist" })
            if (result.track.album.isNotBlank()) append(" • ").append(result.track.album)
        }
        meta.addView(label(subtitle, 12, textSecondary, false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        meta.addView(vGap(2))
        val sourceColor = when {
            result.unavailable -> Color.rgb(255, 120, 120)
            result.external -> spotifyAccent
            result.preview -> Color.rgb(255, 190, 92)
            result.downloaded -> Color.rgb(120, 220, 160)
            else -> lightPurple
        }
        meta.addView(sourceBadge(result.sourceLabel, sourceColor))
        top.addView(meta)
        row.addView(top)

        val capabilityText = when {
            result.external -> "Opens in Spotify"
            result.unavailable -> "Unavailable"
            result.preview -> "Preview only"
            result.downloaded -> "Downloaded"
            result.downloadable -> "Downloadable"
            else -> "Stream only"
        }
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(64), dp(6), 0, 0)
        }
        if (result.track.duration > 0L) infoRow.addView(label(result.track.durationLabel, 11, textMuted, false))
        infoRow.addView(spacerH())
        infoRow.addView(label(capabilityText, 11, sourceColor, true))
        row.addView(infoRow)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(64), dp(8), 0, 0)
        }
        if (result.playable) {
            actions.addView(searchActionChip(if (result.preview) "Preview" else "Play", purple) { playSearchResult(result) })
            actions.addView(hGap(8))
        }
        if (result.external) {
            // Spotify audio is DRM-protected: Aurora offers an external open action
            // instead of pretending the track can stream here.
            actions.addView(searchActionChip("Open in Spotify", spotifyAccent) { openExternal(result.metadata) })
            actions.addView(hGap(8))
        }
        if (result.downloaded) {
            actions.addView(searchActionChip("Downloaded", Color.rgb(120, 220, 160), clickable = false) { })
            actions.addView(hGap(8))
        } else if (result.downloadable) {
            val downloadBtn = searchActionChip("Download", purple) { startSearchDownload(result) }
            searchDownloadButtons[result.metadata.trackId.value] = downloadBtn
            actions.addView(downloadBtn)
            actions.addView(hGap(8))
        }
        if (result.playable) {
            actions.addView(searchActionChip("Queue", textSecondary) { addSearchResultToQueue(result) })
            actions.addView(hGap(8))
        }
        val heart = ImageView(this).apply {
            setImageResource(R.drawable.ic_heart)
            imageTintList = ColorStateList.valueOf(if (isFavorite(result.track)) purple else textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            isClickable = true
            isFocusable = true
            foreground = ripple()
            contentDescription = "Favorite"
            setOnClickListener {
                toggleFavorite(result.track, result.metadata)
                imageTintList = ColorStateList.valueOf(if (isFavorite(result.track)) purple else textMuted)
            }
        }
        actions.addView(heart)
        actions.addView(hGap(10))
        actions.addView(searchActionChip("More", textMuted) { showSearchResultDetails(result) })
        row.addView(actions)
        return row
    }

    private fun searchActionChip(text: String, color: Int, clickable: Boolean = true, onClick: () -> Unit): TextView {
        return label(text, 11, color, true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(surface, 10)
            if (clickable) {
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener { onClick() }
            }
        }
    }

    private fun stableTrackId(source: String, value: String): Long =
        com.aurora.app.source.stableSourceTrackId(source, value)

    /**
     * Resolves a fresh playable stream URL for a queued online track. Called by
     * [PlaybackService] on a worker thread at the moment the track starts, so
     * expiring Audius/SoundCloud URLs are never persisted and always fresh.
     */
    private fun resolveStreamUri(track: Track): Uri? {
        val metadata = playableMetadataById[track.id] ?: savedOnlineMeta[track.id] ?: return null
        return try {
            sourceForId(metadata.trackId.source).stream(metadata).uri
        } catch (_: Exception) {
            null
        }
    }

    /** The playable current search results as a playback queue, anchored on [anchor]. */
    private fun searchQueue(anchor: Track): List<Track> {
        val base = currentSearchResults.filter { it.playable }.map { it.track }
        if (base.isEmpty()) return listOf(anchor)
        return base.map { if (it.id == anchor.id) anchor else it }
    }

    private fun playRemoteTrack(track: Track, metadata: SourceMetadata?) {
        if (metadata == null) {
            triggerPlay(track)
            return
        }
        val source: MusicSource = sourceForId(metadata.trackId.source)
        Thread {
            val streamResult = try {
                source.stream(metadata)
            } catch (e: Exception) {
                com.aurora.app.source.StreamResult(uri = null, metadata = metadata, error = e.message ?: "Track is unavailable")
            }
            runOnUiThread {
                val uri = streamResult.uri
                if (uri == null) {
                    Toast.makeText(this, streamResult.error ?: "This track is currently unavailable.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val playable = track.copy(uri = uri)
                if (streamResult.isPreview) {
                    Toast.makeText(this, "Playing a preview — the full track is not downloadable.", Toast.LENGTH_LONG).show()
                }
                // Play within the surrounding search results so Next/Previous
                // move through real matches instead of repeating one track.
                val queue = if (currentSearchResults.any { it.track.id == track.id }) {
                    searchQueue(playable)
                } else {
                    listOf(playable)
                }
                svc?.playTrack(playable, queue)
            }
        }.start()
    }

    private fun playSearchResult(result: SearchResult) {
        if (result.external) {
            openExternal(result.metadata)
            return
        }
        if (!result.playable) {
            Toast.makeText(this, "This track is not playable from its source.", Toast.LENGTH_SHORT).show()
            return
        }
        if (result.source.sourceId == localSource.sourceId && result.track.uri != Uri.EMPTY) {
            triggerPlay(result.track)
        } else {
            playRemoteTrack(result.track, result.metadata)
        }
    }

    /**
     * Opens an external catalog item (Spotify) in its own app, falling back to
     * the universal web link when no app is installed. Aurora never attempts to
     * stream or download this audio itself.
     */
    private fun openExternal(metadata: SourceMetadata) {
        val canonical = metadata.externalUri
        if (canonical == null) {
            Toast.makeText(this, "No external link is available for this item.", Toast.LENGTH_SHORT).show()
            return
        }
        val candidates = listOfNotNull(canonical, spotifyWebUri(canonical))
        for (uri in candidates) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return
            } catch (_: Exception) {
                // Try the next candidate (app deep link, then web fallback).
            }
        }
        Toast.makeText(this, "No app can open this Spotify item.", Toast.LENGTH_LONG).show()
    }

    private fun addSearchResultToQueue(result: SearchResult) {
        if (!result.playable) {
            Toast.makeText(this, "This track cannot be queued.", Toast.LENGTH_SHORT).show()
            return
        }
        // Queue the track as-is; remote stream URLs are resolved fresh when the
        // track actually starts, so the queue never holds an expired URL.
        svc?.enqueue(result.track)
        Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
    }

    private fun startSearchDownload(result: SearchResult) {
        if (result.downloaded) {
            Toast.makeText(this, "Already in Aurora storage", Toast.LENGTH_SHORT).show()
            return
        }
        if (!result.downloadable) {
            Toast.makeText(this, "This source does not allow downloading this track.", Toast.LENGTH_SHORT).show()
            return
        }
        val button = searchDownloadButtons[result.metadata.trackId.value]
        button?.text = "Checking…"
        Thread {
            val capability = try {
                result.source.checkDownloadAvailability(result.metadata)
            } catch (e: Exception) {
                com.aurora.app.source.DownloadCapability(DownloadAvailability.BLOCKED, e.message ?: "Unavailable")
            }
            if (capability.availability != DownloadAvailability.AVAILABLE) {
                runOnUiThread {
                    button?.text = "Download"
                    val reason = capability.reason.ifBlank { "This track is not downloadable." }
                    Toast.makeText(this, "Download unavailable — $reason", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            downloadManager.queue(result.source, result.metadata)
            runOnUiThread {
                button?.text = "Queued…"
                Toast.makeText(this, "Download queued: ${result.metadata.title}", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun showSearchResultDetails(result: SearchResult) {
        val message = buildString {
            append("Title: ").append(result.track.title).append('\n')
            append("Artist: ").append(result.track.artist.ifBlank { "Unknown" }).append('\n')
            if (result.track.album.isNotBlank()) append("Album: ").append(result.track.album).append('\n')
            if (result.track.duration > 0L) append("Duration: ").append(result.track.durationLabel).append('\n')
            append("Source: ").append(result.sourceLabel).append('\n')
            when {
                result.external -> append("Playback: opens in the Spotify app\n")
                else -> append("Playable: ").append(if (result.playable) "yes" else "no").append('\n')
            }
            append("Downloadable: ")
                .append(if (result.downloaded) "already downloaded" else if (result.downloadable) "yes" else "no")
                .append('\n')
            append("Source track id: ").append(result.metadata.trackId.value)
            result.metadata.externalUri?.let { append('\n').append("External: ").append(it) }
        }
        AlertDialog.Builder(this)
            .setTitle("Track details")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    /**
     * Downloads a saved online library entry through its own source. The live
     * track is re-fetched so availability reflects the source's current access
     * flags rather than a persisted snapshot; an unavailable source is reported
     * honestly instead of queueing an impossible transfer.
     */
    private fun downloadRemoteTrack(metadata: SourceMetadata) {
        val source: MusicSource = sourceForId(metadata.trackId.source)
        Thread {
            // Require live metadata: without it Aurora cannot honestly confirm
            // the source still permits a download (e.g. while offline).
            val live = try {
                source.getTrack(metadata.trackId)
            } catch (_: Exception) {
                null
            }
            if (live == null) {
                runOnUiThread {
                    Toast.makeText(this, "Track details are unavailable right now — check your connection.", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            val capability = try {
                source.checkDownloadAvailability(live)
            } catch (e: Exception) {
                com.aurora.app.source.DownloadCapability(
                    DownloadAvailability.BLOCKED,
                    e.message ?: "Unavailable"
                )
            }
            if (capability.availability != DownloadAvailability.AVAILABLE) {
                runOnUiThread {
                    val reason = capability.reason.ifBlank { "This track is not officially downloadable." }
                    Toast.makeText(this, "Download unavailable — $reason", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            downloadManager.queue(source, live)
            runOnUiThread {
                Toast.makeText(this, "Download queued: ${live.title}", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Reflects a live download job on the matching search result's Download chip. */
    private fun updateSearchDownloadButton(progress: DownloadProgress) {
        val button = searchDownloadButtons[progress.sourceTrackId] ?: return
        button.text = when (progress.state) {
            DownloadState.QUEUED -> "Queued…"
            DownloadState.RESOLVING -> "Resolving…"
            DownloadState.DOWNLOADING -> if (progress.totalBytes > 0L) {
                "${(progress.progress * 100).toInt()}%"
            } else {
                "Downloading…"
            }
            DownloadState.PROCESSING -> "Processing…"
            DownloadState.VERIFYING -> "Verifying…"
            DownloadState.COMPLETED -> {
                button.isClickable = false
                button.isFocusable = false
                button.setOnClickListener(null)
                button.setTextColor(Color.rgb(120, 220, 160))
                "Downloaded"
            }
            DownloadState.FAILED -> "Retry"
            DownloadState.CANCELLED -> "Cancelled"
            DownloadState.PAUSED -> "Paused"
        }
    }

    private fun getOrCreateLibraryView(): View {
        if (libraryView != null) return libraryView!!
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(24), dp(48), dp(24), 0)
        }
        page.addView(label("Library", 28, text, true))
        page.addView(vGap(8))
        page.addView(label("Your Aurora collection", 13, textSecondary, false))
        page.addView(vGap(12))
        page.addView(buildLocalSummaryCard())
        page.addView(vGap(12))
        page.addView(buildSettingRow("Import Music", "Add local audio files to Aurora", "＋") { openImportPicker() })
        page.addView(vGap(8))
        page.addView(buildSettingRow("Downloads", "Transfer queue and progress", "↗") { showDownloadsScreen() })
        page.addView(vGap(12))

        page.addView(buildSegmentedRow(listOf("Tracks", "Albums", "Artists"), librarySection.ordinal, librarySectionTabs) { switchLibrarySection(it) })
        page.addView(vGap(8))
        trackFilterRow = buildSegmentedRow(listOf("All", "Local", "Downloaded", "Online"), 0, trackFilterTabs) { switchTrackFilter(it) }
        page.addView(trackFilterRow!!)
        page.addView(vGap(16))

        libraryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(libraryContainer)
        scroll.addView(page)
        libraryView = scroll
        if (allTracks.isNotEmpty()) renderLibrarySection()
        return scroll
    }

    /**
     * Equal-width segmented control. When [scrollable] is true the tabs size to
     * their labels and scroll horizontally, so a long row (e.g. the five search
     * sources) never clips on a narrow screen. Existing non-scrollable callers
     * are unchanged.
     */
    private fun buildSegmentedRow(
        labels: List<String>,
        initial: Int,
        tabStore: MutableList<FrameLayout>,
        scrollable: Boolean = false,
        onChange: (Int) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 14)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        tabStore.clear()
        labels.forEachIndexed { idx, labelText ->
            val tab = FrameLayout(this).apply {
                background = if (idx == initial) rounded(purple, 10) else null
                layoutParams = if (scrollable) {
                    LinearLayout.LayoutParams(WC, dp(34)).also { if (idx > 0) it.leftMargin = dp(6) }
                } else {
                    LinearLayout.LayoutParams(0, dp(34), 1f).also { if (idx > 0) it.leftMargin = dp(6) }
                }
                setPadding(dp(if (scrollable) 14 else 6), dp(6), dp(if (scrollable) 14 else 6), dp(6))
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener { onChange(idx) }
            }
            val tv = label(labelText, 11, if (idx == initial) text else textSecondary, idx == initial).apply {
                gravity = Gravity.CENTER
                layoutParams = if (scrollable) {
                    FrameLayout.LayoutParams(WC, WC).also { it.gravity = Gravity.CENTER }
                } else {
                    FrameLayout.LayoutParams(MP, MP)
                }
            }
            tab.addView(tv)
            row.addView(tab)
            tabStore.add(tab)
        }
        if (!scrollable) return row
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            addView(row)
        }
    }

    /** Re-styles a segmented row so only [active] is highlighted. */
    private fun updateSegmentedSelection(tabs: List<FrameLayout>, active: Int) {
        tabs.forEachIndexed { index, tab ->
            tab.background = if (index == active) rounded(purple, 10) else null
            val tv = tab.getChildAt(0) as? TextView
            tv?.setTextColor(if (index == active) text else textSecondary)
            tv?.typeface = Typeface.create("sans-serif", if (index == active) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun switchLibrarySection(index: Int) {
        librarySection = LibrarySection.values()[index.coerceIn(0, LibrarySection.values().lastIndex)]
        updateSegmentedSelection(librarySectionTabs, librarySection.ordinal)
        renderLibrarySection()
    }

    private fun switchTrackFilter(index: Int) {
        trackFilter = TrackFilter.values()[index.coerceIn(0, TrackFilter.values().lastIndex)]
        updateSegmentedSelection(trackFilterTabs, trackFilter.ordinal)
        renderLibrarySection()
    }

    /** Classifies an Aurora-owned track into the All / Local / Downloaded / Online buckets. */
    private fun trackBucket(track: Track): TrackFilter {
        // Explicit Aurora downloads (verified local copy) come first so they
        // stay in Downloaded even though they are also local files.
        if (downloadedTrackIds.contains(track.id)) return TrackFilter.DOWNLOADED
        // Saved online entries have no local file until explicitly downloaded.
        if (savedOnlineMeta.containsKey(track.id)) return TrackFilter.ONLINE
        return when (track.uri.scheme?.lowercase()) {
            "http", "https" -> TrackFilter.ONLINE     // network source tracks when present
            else -> TrackFilter.LOCAL                 // explicitly imported Aurora files
        }
    }

    /** Renders the active Library section (Tracks, Albums or Artists) into the content container. */
    private fun renderLibrarySection() {
        val container = libraryContainer ?: return
        activeDownloadView = false
        trackFilterRow?.visibility = if (librarySection == LibrarySection.TRACKS) View.VISIBLE else View.GONE
        when (librarySection) {
            LibrarySection.TRACKS -> renderLibraryTracks(container)
            LibrarySection.ALBUMS -> renderLibraryAlbums(container)
            LibrarySection.ARTISTS -> renderLibraryArtists(container)
        }
    }

    private fun renderLibraryTracks(container: LinearLayout) {
        container.removeAllViews()
        val visible = when (trackFilter) {
            TrackFilter.ALL -> allTracks
            TrackFilter.LOCAL -> allTracks.filter { trackBucket(it) == TrackFilter.LOCAL }
            TrackFilter.DOWNLOADED -> allTracks.filter { trackBucket(it) == TrackFilter.DOWNLOADED }
            TrackFilter.ONLINE -> allTracks.filter { trackBucket(it) == TrackFilter.ONLINE }
        }
        if (visible.isEmpty()) {
            if (trackFilter == TrackFilter.DOWNLOADED) {
                container.addView(
                    buildActionCard(
                        "No downloaded tracks",
                        "Tracks you import or download into Aurora appear here.",
                        "Open Downloads"
                    ) { showDownloadsScreen() }
                )
                return
            }
            if (trackFilter == TrackFilter.ALL) {
                container.addView(
                    buildActionCard(
                        "No tracks",
                        "Import music files or download tracks to start your Aurora collection.",
                        "Import Music"
                    ) { openImportPicker() }
                )
                return
            }
            val (title, message) = when (trackFilter) {
                TrackFilter.ALL -> "No tracks" to "Import music files or download tracks to start your Aurora collection."
                TrackFilter.LOCAL -> "No imported tracks" to "Music files you explicitly import into Aurora appear here."
                TrackFilter.DOWNLOADED -> "No downloaded tracks" to "Tracks you import or download into Aurora appear here."
                TrackFilter.ONLINE -> "No online tracks" to "Online tracks you save to your library appear here."
            }
            container.addView(buildStateCard(title, message, false))
            return
        }
        container.addView(label("${visible.size} tracks", 12, textMuted, false).apply { setPadding(0, 0, 0, dp(12)) })
        visible.forEachIndexed { i, track ->
            val onlineMeta = savedOnlineMeta[track.id]
            if (onlineMeta != null) {
                container.addView(buildTrackRow(track, i + 1, true, source = com.aurora.app.source.sourceDisplayName(onlineMeta.trackId.source), sourceMetadata = onlineMeta))
            } else {
                container.addView(buildTrackRow(track, i + 1, true))
            }
            if (i < visible.lastIndex) container.addView(dividerRow())
        }
    }

    private fun renderLibraryAlbums(container: LinearLayout) {
        container.removeAllViews()
        val grouped = allTracks.groupBy { it.album.takeIf { a -> a.isNotBlank() } ?: "Unknown Album" }
        if (grouped.isEmpty()) {
            container.addView(buildStateCard("No albums", "Albums from your music collection appear here.", false))
            return
        }
        container.addView(label("${grouped.size} albums", 12, textMuted, false).apply { setPadding(0, 0, 0, dp(12)) })
        grouped.toList().sortedBy { it.first.lowercase() }.forEach { (album, tracks) ->
            val sample = tracks.first()
            val trackWord = if (tracks.size == 1) "track" else "tracks"
            container.addView(buildLibraryEntityRow(sample, album, "${tracks.size} $trackWord • ${sample.artist}", "") {
                showLibraryDrillDown(album, tracks)
            })
            container.addView(vGap(10))
        }
    }

    private fun renderLibraryArtists(container: LinearLayout) {
        container.removeAllViews()
        val grouped = allTracks.groupBy { it.artist.takeIf { a -> a.isNotBlank() } ?: "Unknown Artist" }
        if (grouped.isEmpty()) {
            container.addView(buildStateCard("No artists", "Artists from your music collection appear here.", false))
            return
        }
        container.addView(label("${grouped.size} artists", 12, textMuted, false).apply { setPadding(0, 0, 0, dp(12)) })
        grouped.toList().sortedBy { it.first.lowercase() }.forEach { (artist, tracks) ->
            val sample = tracks.first()
            val trackWord = if (tracks.size == 1) "track" else "tracks"
            val albumCount = tracks.map { it.album }.distinct().size
            val albumWord = if (albumCount == 1) "album" else "albums"
            container.addView(buildLibraryEntityRow(sample, artist, "${tracks.size} $trackWord • $albumCount $albumWord", "") {
                showLibraryDrillDown(artist, tracks)
            })
            container.addView(vGap(10))
        }
    }

    /** Compact album/artist row: artwork, name, subtitle, tap to open its tracks. */
    private fun buildLibraryEntityRow(artTrack: Track, title: String, subtitle: String, meta: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { onClick() }
        }
        val art = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).also { it.rightMargin = dp(12) }
            background = rounded(elevated, 12)
            clipToOutline = true
            outlineProvider = roundRectOutline(12)
        }
        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = roundRectOutline(12)
        }
        ArtworkLoader.loadArtwork(this, artTrack, dp(52), iv)
        art.addView(iv)
        row.addView(art)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        textCol.addView(label(title, 15, text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        textCol.addView(vGap(2))
        textCol.addView(label(subtitle, 12, textSecondary, false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(textCol)
        if (meta.isNotBlank()) row.addView(label(meta, 11, purple, true))
        return row
    }

    /** Shows the tracks of one album/artist inside the Library with a back row. */
    private fun showLibraryDrillDown(
        title: String,
        tracks: List<Track>,
        metaById: Map<Long, SourceMetadata> = emptyMap()
    ) {
        val container = libraryContainer ?: return
        activeDownloadView = false
        trackFilterRow?.visibility = View.GONE
        container.removeAllViews()
        val word = if (tracks.size == 1) "track" else "tracks"
        val trackWordCount = "${tracks.size} $word • tap a track to play"
        container.addView(buildSettingRow("‹  $title", trackWordCount, "Back") { renderLibrarySection() })
        container.addView(vGap(4))
        tracks.forEachIndexed { i, track ->
            val onlineMeta = metaById[track.id] ?: savedOnlineMeta[track.id]
            if (onlineMeta != null) {
                container.addView(buildTrackRow(track, i + 1, true, source = com.aurora.app.source.sourceDisplayName(onlineMeta.trackId.source), sourceMetadata = onlineMeta))
            } else {
                container.addView(buildTrackRow(track, i + 1, true))
            }
            if (i < tracks.lastIndex) container.addView(dividerRow())
        }
    }

    private fun getOrCreateSettingsView(): View {
        if (settingsView != null) return settingsView!!
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(24), dp(48), dp(24), 0)
        }
        page.addView(label("Settings", 28, text, true))


        page.addView(vGap(20))

        page.addView(vGap(20))
        page.addView(settingGroupLabel("Playback"))
        // Primary, always-visible entry point for the Equalizer. Opens the
        // existing floating overlay; there is no separate Equalizer screen.
        page.addView(buildSettingRow("Equalizer", "Customize Aurora playback", "Open") { showEqualizer() })

        page.addView(vGap(20))
        page.addView(settingGroupLabel("Library"))
        page.addView(buildSettingRow("Reload library", "Refresh your Aurora collection", "↻") { loadLibrary() })
        page.addView(buildSettingRow("Duplicate imports", "Already-imported audio is skipped automatically", "Auto"))

        page.addView(vGap(20))
        page.addView(settingGroupLabel("SoundCloud"))
        soundCloudSettingsGroup = buildSoundCloudSettingsGroup()
        page.addView(soundCloudSettingsGroup)

        page.addView(vGap(20))
        page.addView(settingGroupLabel("Spotify"))
        spotifySettingsGroup = buildSpotifySettingsGroup()
        page.addView(spotifySettingsGroup)

        page.addView(vGap(20))
        page.addView(settingGroupLabel("Online sources"))
        page.addView(buildSettingRow("Audius", "Online search and streaming — no sign-in required", "Available"))

        scroll.addView(page)
        settingsView = scroll
        return scroll
    }

    /**
     * Builds the SoundCloud connection group. Client credentials are an internal
     * implementation detail and are never exposed in the normal UI — the user
     * simply presses Connect and completes the official SoundCloud sign-in.
     */
    private fun buildSoundCloudSettingsGroup(): LinearLayout {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        group.addView(buildSoundCloudStatusCard())
        group.addView(vGap(16))

        group.addView(label("Connect your SoundCloud account", 15, text, true))
        group.addView(vGap(4))
        group.addView(label("Use SoundCloud as an online music source in Aurora.", 12, textSecondary, false))
        group.addView(vGap(14))

        val configured = soundCloudSource.isConfigured()
        val signedIn = soundCloudSource.isSignedIn()
        if (signedIn) {
            val accountName = soundCloudSource.signedInAccountName()
            group.addView(buildSettingRow(
                "Connected to SoundCloud",
                accountName?.let { "Signed in as $it" } ?: "A user session is active",
                "Connected"
            ))
            group.addView(vGap(10))
            group.addView(buildPrimaryButton("Disconnect") { disconnectSoundCloud() })
        } else {
            group.addView(buildPrimaryButton("Connect SoundCloud") { startSoundCloudConnect() })
            if (!configured) {
                group.addView(vGap(10))
                group.addView(label(
                    "This build has no SoundCloud app credentials, so the official sign-in page cannot be opened.",
                    11,
                    textMuted,
                    false
                ))
            }
        }
        return group
    }

    private fun buildSoundCloudStatusCard(): LinearLayout {
        val configured = soundCloudSource.isConfigured()
        val signedIn = soundCloudSource.isSignedIn()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 18)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.bottomMargin = dp(10) }
        }
        card.addView(label("SOUNDCLOUD", 11, purple, true).apply { letterSpacing = 0.14f })
        card.addView(vGap(8))
        card.addView(label(
            when {
                signedIn -> "Connected to SoundCloud"
                configured -> "Ready to connect"
                else -> "SoundCloud is unavailable in this build"
            },
            16,
            text,
            true
        ))
        card.addView(vGap(4))
        val accountName = if (signedIn) soundCloudSource.signedInAccountName() else null
        card.addView(label(
            when {
                accountName != null -> "Signed in as $accountName — qualified tracks stream with your account."
                signedIn -> "A user session is active — qualified tracks stream with your account."
                configured -> "Press Connect to sign in on the official SoundCloud page."
                else -> "No SoundCloud app credentials are bundled, so online search and discovery are offline."
            },
            12,
            textSecondary,
            false
        ))
        return card
    }

    private fun refreshSoundCloudSettings() {
        val group = soundCloudSettingsGroup ?: return
        val parent = group.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(group)
        parent.removeView(group)
        soundCloudSettingsGroup = buildSoundCloudSettingsGroup()
        parent.addView(soundCloudSettingsGroup, index)
    }

    /**
     * Disconnects the SoundCloud account: revokes app access through the
     * official POST /disconnect endpoint when the token supports it, then
     * always clears local credentials. Local Aurora music, downloads, and
     * the library are never affected.
     */
    private fun disconnectSoundCloud() {
        Thread {
            val revoked = try {
                soundCloudSource.disconnectRemote()
            } catch (_: Exception) {
                false
            }
            soundCloudSource.clearAuthentication()
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (revoked) "Disconnected from SoundCloud"
                    else "Disconnected locally — remote revoke was not supported for this session",
                    Toast.LENGTH_LONG
                ).show()
                refreshSoundCloudSettings()
            }
        }.start()
    }

    /**
     * Builds the Spotify connection group. Spotify uses Authorization Code +
     * PKCE, so only the public Client ID is configured — never a secret.
     */
    private fun buildSpotifySettingsGroup(): LinearLayout {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        group.addView(buildSpotifyStatusCard())
        group.addView(vGap(16))

        group.addView(label("Connect your Spotify account", 15, text, true))
        group.addView(vGap(4))
        group.addView(label("Search the Spotify catalog and open tracks in the Spotify app.", 12, textSecondary, false))
        group.addView(vGap(14))

        val configured = spotifySource.isConfigured()
        val signedIn = spotifySource.isSignedIn()
        if (signedIn) {
            group.addView(buildSettingRow("Connected to Spotify", "A user session is active", "Connected"))
            group.addView(vGap(10))
            group.addView(buildPrimaryButton("Disconnect") { disconnectSpotify() })
        } else {
            group.addView(buildPrimaryButton("Connect Spotify") { startSpotifyConnect() })
            if (!configured) {
                group.addView(vGap(10))
                group.addView(label(
                    "This build has no Spotify Client ID, so the official sign-in page cannot be opened.",
                    11,
                    textMuted,
                    false
                ))
            }
        }
        return group
    }

    private fun buildSpotifyStatusCard(): LinearLayout {
        val configured = spotifySource.isConfigured()
        val signedIn = spotifySource.isSignedIn()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 18)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.bottomMargin = dp(10) }
        }
        card.addView(label("SPOTIFY", 11, spotifyAccent, true).apply { letterSpacing = 0.14f })
        card.addView(vGap(8))
        card.addView(label(
            when {
                signedIn -> "Connected to Spotify"
                configured -> "Ready to connect"
                else -> "Spotify is unavailable in this build"
            },
            16,
            text,
            true
        ))
        card.addView(vGap(4))
        card.addView(label(
            when {
                signedIn -> "Catalog search is on. Spotify tracks open in the Spotify app."
                configured -> "Press Connect to sign in on the official Spotify page."
                else -> "No Spotify Client ID is bundled, so Spotify search is offline."
            },
            12,
            textSecondary,
            false
        ))
        return card
    }

    private fun refreshSpotifySettings() {
        val group = spotifySettingsGroup ?: return
        val parent = group.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(group)
        parent.removeView(group)
        spotifySettingsGroup = buildSpotifySettingsGroup()
        parent.addView(spotifySettingsGroup, index)
    }

    private fun startSpotifyConnect() {
        if (!spotifySource.isConfigured()) {
            Toast.makeText(this, "Spotify is not configured in this build", Toast.LENGTH_SHORT).show()
            return
        }
        val authUrl = try {
            spotifySource.buildAuthorizationUrl()
        } catch (e: IllegalStateException) {
            Toast.makeText(this, e.message ?: "Spotify is not configured", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, authUrl))
        } catch (_: Exception) {
            Toast.makeText(this, "No app can open the Spotify sign-in page", Toast.LENGTH_SHORT).show()
        }
    }

    /** Clears the local Spotify session. Local music, downloads and the library are untouched. */
    private fun disconnectSpotify() {
        spotifySource.clearAuthentication()
        Toast.makeText(this, "Disconnected from Spotify", Toast.LENGTH_LONG).show()
        refreshSpotifySettings()
    }

    private fun buildLocalSummaryCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 18)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.bottomMargin = dp(10) }
        }

        val countLabel = label("Loading…", 16, text, true)
        val statLabel = label("Reading your local collection", 12, textSecondary, false)

        card.addView(label("Local", 12, purple, true).apply { letterSpacing = 0.12f })
        card.addView(vGap(8))
        card.addView(countLabel)
        card.addView(vGap(4))
        card.addView(statLabel)

        // Room forbids database access on the main thread, so the summary
        // counts are loaded on a background thread and applied to the labels
        // on the UI thread once ready.
        Thread {
            val auroraTracks = libraryRepository.getAuroraTracks()
            val artists = libraryRepository.getAuroraArtists()
            val albums = libraryRepository.getAuroraAlbums()
            runOnUiThread {
                countLabel.text = when {
                    auroraTracks.isEmpty() -> "No Aurora local tracks yet"
                    auroraTracks.size == 1 -> "1 track in Aurora local storage"
                    else -> "${auroraTracks.size} tracks in Aurora local storage"
                }
                statLabel.text = "${artists.size} artists • ${albums.size} albums"
            }
        }.start()

        return card
    }

    /** Terminal download states are finished and never receive further progress. */
    private fun isDownloadTerminal(state: DownloadState): Boolean =
        state == DownloadState.COMPLETED || state == DownloadState.FAILED || state == DownloadState.CANCELLED

    private fun showDownloadsScreen() {
        activeDownloadView = true
        trackFilterRow?.visibility = View.GONE
        libraryContainer?.removeAllViews()
        val list = downloadManager.getAll().sortedByDescending { it.jobId }
        val activeCount = list.count { !isDownloadTerminal(it.state) }
        val completedCount = list.count { it.state == DownloadState.COMPLETED }
        val subtitle = when {
            list.isEmpty() -> "Transfer queue and progress"
            else -> "$activeCount active • $completedCount completed"
        }
        libraryContainer?.addView(buildSettingRow("‹  Downloads", subtitle, "Back") { renderLibrarySection() })
        libraryContainer?.addView(vGap(4))
        val sections = listOf(
            DownloadState.DOWNLOADING,
            DownloadState.RESOLVING,
            DownloadState.PROCESSING,
            DownloadState.VERIFYING,
            DownloadState.QUEUED,
            DownloadState.COMPLETED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
            DownloadState.PAUSED
        )

        if (list.isEmpty()) {
            libraryContainer?.addView(buildEmptyDownloadsState())
            return
        }

        sections.forEach { state ->
            val items = list.filter { it.state == state }
            if (items.isEmpty()) return@forEach
            libraryContainer?.addView(label(state.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }, 12, purple, true).apply {
                setPadding(0, dp(18), 0, dp(10))
            })
            items.forEach { progress ->
                libraryContainer?.addView(buildDownloadCard(progress))
                libraryContainer?.addView(vGap(10))
            }
        }
    }

    private fun buildEmptyDownloadsState(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(surface, 18)
            setPadding(dp(24), dp(28), dp(24), dp(28))
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(16) }
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_aurora_logo)
            imageTintList = ColorStateList.valueOf(purple)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }
        card.addView(icon)
        card.addView(vGap(12))
        card.addView(label("Nothing downloading yet", 16, text, true).apply { gravity = Gravity.CENTER })
        card.addView(vGap(4))
        card.addView(label("Downloaded tracks will appear here.", 12, textSecondary, false).apply { gravity = Gravity.CENTER })
        return card
    }

    private fun buildDownloadCard(progress: DownloadProgress): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 18)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val artWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).also { it.rightMargin = dp(12) }
            background = rounded(elevated, 12)
            clipToOutline = true
            outlineProvider = roundRectOutline(12)
        }
        val art = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = roundRectOutline(12)
        }
        // Prefer the indexed library track for artwork once the finished file
        // is in Aurora storage; otherwise use honest generative art (no fakes).
        val artTrack = findLibraryTrackForDownload(progress)
            ?: Track(0L, progress.title.ifBlank { "Aurora" }, progress.artist.ifBlank { "Local" }, "", progress.totalBytes.coerceAtLeast(0L), android.net.Uri.EMPTY, 0L)
        ArtworkLoader.loadArtwork(this, artTrack, dp(52), art)
        artWrap.addView(art)
        header.addView(artWrap)

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        meta.addView(label(progress.title.ifBlank { "Audio transfer" }, 14, text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        meta.addView(vGap(2))
        meta.addView(label(progress.artist.ifBlank { progress.source }, 12, textSecondary, false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        header.addView(meta)

        val badgeColor = when (progress.state) {
            DownloadState.COMPLETED -> Color.rgb(120, 220, 160)
            DownloadState.FAILED -> Color.rgb(255, 100, 100)
            else -> purple
        }
        val badge = TextView(this).apply {
            text = when (progress.state) {
                DownloadState.QUEUED -> "Queued"
                DownloadState.RESOLVING -> "Resolving"
                DownloadState.DOWNLOADING -> "Downloading"
                DownloadState.PROCESSING -> "Processing"
                DownloadState.VERIFYING -> "Verifying"
                DownloadState.COMPLETED -> "Completed"
                DownloadState.FAILED -> "Failed"
                DownloadState.CANCELLED -> "Cancelled"
                DownloadState.PAUSED -> "Paused"
            }
            setTextColor(badgeColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        header.addView(badge)
        // A finished download is a real library track: tapping it plays it
        // through the existing PlaybackService (no second player).
        if (progress.state == DownloadState.COMPLETED) {
            card.isClickable = true
            card.isFocusable = true
            card.foreground = ripple()
            card.setOnClickListener { playCompletedDownload(progress) }
        }
        card.addView(header)

        if (progress.state in setOf(DownloadState.DOWNLOADING, DownloadState.RESOLVING, DownloadState.PROCESSING, DownloadState.VERIFYING, DownloadState.QUEUED)) {
            card.addView(vGap(10))
            // Real byte-backed progress only; unknown totals show the honest state name.
            val percent = (progress.progress.coerceIn(0f, 1f) * 100f).toInt()
            val percentLabel = if (progress.totalBytes > 0L) "$percent%" else progress.state.name.lowercase().replaceFirstChar { it.uppercase() }
            card.addView(label(percentLabel, 11, purple, true))
            card.addView(vGap(6))
            val track = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.argb(40, 124, 92, 252))
                    cornerRadius = dp(99).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(MP, dp(8))
            }
            val fillWeight = (progress.progress.coerceIn(0f, 1f) * 100f).coerceIn(0f, 100f)
            val fill = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(purple)
                    cornerRadius = dp(99).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(8), fillWeight.coerceAtLeast(0.5f))
            }
            val remainder = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(8), (100f - fillWeight).coerceAtLeast(0.5f))
            }
            track.addView(fill)
            track.addView(remainder)
            card.addView(track)
            card.addView(vGap(8))
            val bytesText = if (progress.totalBytes > 0L) {
                "${progress.downloadedBytes / 1024L} KB / ${progress.totalBytes / 1024L} KB"
            } else if (progress.downloadedBytes > 0L) {
                "${progress.downloadedBytes / 1024L} KB downloaded"
            } else {
                progress.state.name.lowercase().replaceFirstChar { it.uppercase() }
            }
            card.addView(label(bytesText, 11, textSecondary, false))
            card.addView(vGap(6))
            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val statusText = when {
                progress.speedBytesPerSecond > 0f -> {
                    val speed = "${String.format("%.1f", progress.speedBytesPerSecond / 1024f)} KB/s"
                    if (progress.etaSeconds > 0L) "$speed • ETA ${progress.etaSeconds}s" else speed
                }
                progress.state == DownloadState.QUEUED -> "Queued"
                progress.state == DownloadState.RESOLVING -> "Resolving source"
                progress.state == DownloadState.PROCESSING -> "Processing audio"
                progress.state == DownloadState.VERIFYING -> "Verifying file"
                else -> "Downloading"
            }
            footer.addView(label(statusText, 11, textMuted, false))
            footer.addView(spacerH())
            // DownloadManager supports cancellation for in-flight jobs; it has
            // no pause API, so Cancel is the only honest in-flight action.
            footer.addView(label("Cancel", 11, purple, true).apply {
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener { downloadManager.cancel(progress.jobId) }
            })
            card.addView(footer)
        } else {
            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val sub = label(
                when (progress.state) {
                    DownloadState.COMPLETED -> "Completed • tap to play"
                    DownloadState.FAILED -> progress.error ?: "Transfer failed"
                    DownloadState.CANCELLED -> "Cancelled"
                    DownloadState.PAUSED -> progress.error ?: "Paused"
                    else -> "Ready"
                },
                11,
                if (progress.state == DownloadState.FAILED) Color.rgb(255, 120, 120) else textSecondary,
                false
            )
            footer.addView(sub)
            footer.addView(spacerH())
            if (progress.state == DownloadState.COMPLETED) {
                footer.addView(label("Play", 11, purple, true).apply {
                    isClickable = true
                    isFocusable = true
                    foreground = ripple()
                    setOnClickListener { playCompletedDownload(progress) }
                })
                footer.addView(hGap(14))
            } else {
                // Failed / cancelled / interrupted-paused jobs resume by
                // re-queueing through the same DownloadManager pipeline.
                val retryLabel = if (progress.state == DownloadState.PAUSED) "Resume" else "Retry"
                footer.addView(label(retryLabel, 11, purple, true).apply {
                    isClickable = true
                    isFocusable = true
                    foreground = ripple()
                    setOnClickListener { retryDownload(progress) }
                })
                footer.addView(hGap(14))
            }
            footer.addView(label("Remove", 11, textMuted, true).apply {
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener {
                    downloadManager.remove(progress.jobId)
                    showDownloadsScreen()
                }
            })
            card.addView(vGap(8))
            card.addView(footer)
        }

        return card
    }

    /** Matches a finished job to its indexed library track (in-memory only, no DB on main). */
    private fun findLibraryTrackForDownload(progress: DownloadProgress): Track? {
        val file = progress.currentFile
        if (!file.isNullOrBlank()) {
            allTracks.firstOrNull { it.uri.toString() == android.net.Uri.fromFile(java.io.File(file)).toString() }
                ?.let { return it }
        }
        return allTracks.firstOrNull {
            it.title.equals(progress.title, ignoreCase = true) &&
                (progress.artist.isBlank() || it.artist.equals(progress.artist, ignoreCase = true))
        }
    }

    /** Plays a completed download through the existing PlaybackService queue. */
    private fun playCompletedDownload(progress: DownloadProgress) {
        val track = findLibraryTrackForDownload(progress)
        if (track == null) {
            Toast.makeText(this, "Track is being added to your library — try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }
        triggerPlay(track)
    }

    private fun retryDownload(progress: DownloadProgress) {
        val source: MusicSource = sourceForId(progress.source)
        Thread {
            val metadata = try {
                source.getTrack(SourceTrackId(progress.source, progress.sourceTrackId))
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (metadata == null) {
                    Toast.makeText(this, "Track details are no longer available for this download.", Toast.LENGTH_SHORT).show()
                } else {
                    downloadManager.remove(progress.jobId)
                    downloadManager.queue(source, metadata)
                }
            }
        }.start()
    }

    private fun settingGroupLabel(title: String): TextView = label(title.uppercase(), 11, purple, true).apply {
        letterSpacing = 0.12f
        setPadding(0, 0, 0, dp(10))
    }

    private fun buildSettingRow(title: String, subtitle: String, meta: String, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.bottomMargin = dp(8) }
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener { onClick() }
            }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        textCol.addView(label(title, 15, text, true))
        textCol.addView(vGap(2))
        textCol.addView(label(subtitle, 12, textSecondary, false))
        row.addView(textCol)
        row.addView(label(meta, 11, purple, true))
        return row
    }

    private fun buildToggleRow(title: String, subtitle: String, checked: Boolean, onChanged: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.bottomMargin = dp(8) }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        textCol.addView(label(title, 15, text, true))
        textCol.addView(vGap(2))
        textCol.addView(label(subtitle, 12, textSecondary, false))
        row.addView(textCol)
        val toggle = Switch(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList.valueOf(purple)
            trackTintList = ColorStateList.valueOf(purpleSoft)
            setOnCheckedChangeListener { _, enabled -> onChanged(enabled) }
        }
        row.addView(toggle)
        return row
    }

    private fun populateAllViews(tracks: List<Track>) {
        refreshHomeSections()
        if (libraryContainer != null && tracks.isNotEmpty()) {
            renderLibrarySection()
        }
    }

    private fun updateRecentsUI() {
        val row = recentsRow ?: return
        row.removeAllViews()
        if (recents.isEmpty()) {
            row.addView(buildStateCard("No recent tracks yet", "Play something and it will show up here.", false))
            return
        }
        recents.take(8).forEachIndexed { idx, track ->
            row.addView(buildAlbumCard(track, idx == 0))
            if (idx < recents.take(8).lastIndex) row.addView(hGap(12))
        }
    }

    private fun buildAlbumCard(track: Track, highlight: Boolean): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(150), WC)
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { triggerPlay(track) }
        }
        val art = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(150))
            background = rounded(surface, 18)
            clipToOutline = true
            outlineProvider = roundRectOutline(18)
        }
        val image = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = roundRectOutline(18)
        }
        ArtworkLoader.loadArtwork(this, track, dp(150), image)
        art.addView(image)
        item.addView(art)
        item.addView(vGap(8))

        val title = label(track.title.takeIf { it.isNotBlank() } ?: "Unknown", 13, text, true)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        item.addView(title)
        item.addView(vGap(2))

        val subtitle = label(track.artist.takeIf { it.isNotBlank() } ?: "Unknown Artist", 11, textSecondary, false)
        subtitle.maxLines = 1
        subtitle.ellipsize = TextUtils.TruncateAt.END
        item.addView(subtitle)
        return item
    }

    private fun buildTrackRow(
        track: Track,
        index: Int,
        compact: Boolean,
        source: String = "Local",
        sourceMetadata: SourceMetadata? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = if (compact) dp(64) else dp(68)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener {
                when {
                    sourceMetadata != null &&
                        sourceMetadata.sourceCapabilities.contains(SourceCapability.EXTERNAL_PLAYBACK) ->
                        openExternal(sourceMetadata)
                    sourceMetadata != null -> playRemoteTrack(track, sourceMetadata)
                    track.uri.scheme == "http" || track.uri.scheme == "https" -> svc?.playTrack(track, allTracks)
                    else -> triggerPlay(track)
                }
            }
        }
        val art = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).also { it.rightMargin = dp(12) }
            background = rounded(surface, 12)
            clipToOutline = true
            outlineProvider = roundRectOutline(12)
        }
        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = roundRectOutline(12)
        }
        ArtworkLoader.loadArtwork(
            this, track, dp(52), iv,
            sourceMetadata?.artworkUri ?: savedOnlineMeta[track.id]?.artworkUri
        )
        art.addView(iv)
        row.addView(art)

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        meta.addView(label(track.title.ifBlank { "Unknown track" }, 14, text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        meta.addView(vGap(2))
        val artistAlbum = buildString {
            append(track.artist.ifBlank { "Unknown artist" })
            if (track.album.isNotBlank()) append(" • ").append(track.album)
        }
        meta.addView(label(artistAlbum, 12, textSecondary, false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (source == "SoundCloud") {
            meta.addView(vGap(2))
            meta.addView(sourceBadge("Remote • $source", lightPurple))
        } else {
            // Library rows: honest duration + source bucket (Local / Downloaded / Online).
            val bucket = trackBucket(track)
            val bucketLabel = when (bucket) {
                TrackFilter.LOCAL -> "Local"
                TrackFilter.DOWNLOADED -> "Downloaded"
                TrackFilter.ONLINE -> "Online"
                TrackFilter.ALL -> "Local"
            }
            val bucketColor = when (bucket) {
                TrackFilter.DOWNLOADED -> Color.rgb(120, 220, 160)
                TrackFilter.ONLINE -> lightPurple
                else -> textMuted
            }
            meta.addView(vGap(2))
            meta.addView(sourceBadge(bucketLabel, bucketColor))
        }
        row.addView(meta)
        row.addView(label(track.durationLabel, 12, textMuted, false).apply {
            setPadding(dp(8), 0, dp(10), 0)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val onlineSourceId = sourceMetadata?.trackId?.source
        if (sourceMetadata != null && !onlineSourceId.isNullOrBlank() && onlineSourceId != localSource.sourceId) {
            val sourceSupportsDownload = sourceForId(onlineSourceId).capabilities.contains(SourceCapability.DOWNLOAD)
            if (sourceSupportsDownload) {
                // The per-track permission comes from the saved entry's last
                // known capability; the download itself re-checks the live
                // source before transferring anything.
                val canDownload = sourceMetadata.sourceCapabilities.contains(SourceCapability.DOWNLOAD)
                val actionBtn = TextView(this).apply {
                    text = if (canDownload) "Download" else "Unavailable"
                    setTextColor(if (canDownload) purple else textMuted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = rounded(surface, 10)
                    isClickable = canDownload
                    isFocusable = canDownload
                    if (canDownload) {
                        foreground = ripple()
                        setOnClickListener { downloadRemoteTrack(sourceMetadata) }
                    }
                    contentDescription = if (canDownload) "Download ${track.title}" else "Download unavailable"
                }
                actions.addView(actionBtn)
            }
        }

        val heart = ImageView(this).apply {
            setImageResource(R.drawable.ic_heart)
            imageTintList = ColorStateList.valueOf(if (isFavorite(track)) purple else textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.leftMargin = if (source == "SoundCloud") dp(8) else dp(10) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener {
                toggleFavorite(track, sourceMetadata ?: savedOnlineMeta[track.id])
                imageTintList = ColorStateList.valueOf(if (isFavorite(track)) purple else textMuted)
            }
            contentDescription = "Favorite track"
        }
        actions.addView(heart)
        row.addView(actions)
        return row
    }

    /** True when [track] is favorited, according to the Room-backed cache. */
    private fun isFavorite(track: Track): Boolean = track.favoriteKey in favoriteKeys

    /** Re-tints the currently shown player hearts from the favorite cache. */
    private fun refreshFavoriteHearts() {
        val cur = svc?.currentTrack ?: return
        val fav = isFavorite(cur)
        fpHeartBtn?.imageTintList = ColorStateList.valueOf(if (fav) purple else textMuted)
        heroFavBtn?.imageTintList = ColorStateList.valueOf(if (fav) purple else textMuted)
    }

    /**
     * Favorites/unfavorites [track] through the single Room-backed favorites
     * store. Updates the in-memory cache optimistically, persists off the main
     * thread, mirrors the change to SoundCloud when applicable, then refreshes
     * the visible surfaces. Audio files are never touched.
     */
    private fun toggleFavorite(track: Track, metadata: SourceMetadata? = null) {
        val nowFavorite = !isFavorite(track)
        favoriteKeys = if (nowFavorite) favoriteKeys + track.favoriteKey
                      else favoriteKeys - track.favoriteKey
        refreshFavoriteHearts()
        val effectiveMeta = metadata ?: savedOnlineMeta[track.id] ?: playableMetadataById[track.id]
        Thread {
            try {
                if (nowFavorite) favoriteRepository.add(track, effectiveMeta)
                else favoriteRepository.remove(track)
            } catch (_: Exception) {
                // Roll the optimistic update back on a genuine failure.
                runOnUiThread {
                    favoriteKeys = if (nowFavorite) favoriteKeys - track.favoriteKey
                                  else favoriteKeys + track.favoriteKey
                    refreshFavoriteHearts()
                }
                return@Thread
            }
            // Mirror the like/unlike with SoundCloud when signed in (best effort).
            if (effectiveMeta != null &&
                effectiveMeta.trackId.source == soundCloudSource.sourceId &&
                soundCloudSource.isSignedIn()
            ) {
                val outcome = try {
                    soundCloudSource.setTrackLiked(effectiveMeta, nowFavorite)
                } catch (e: Exception) {
                    com.aurora.app.source.LikeResult(
                        success = false,
                        liked = nowFavorite,
                        message = e.message ?: "SoundCloud like failed"
                    )
                }
                if (!outcome.success) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            outcome.message.ifBlank { "SoundCloud sync failed — kept locally" },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            onFavoriteChanged()
        }.start()
    }

    /** Refreshes favorite-dependent surfaces after a change (posted to UI). */
    private fun onFavoriteChanged() {
        runOnUiThread {
            refreshFavoriteHearts()
            refreshQuickAccess()
            libraryNeedsRefresh = true
            if (selectedTab == 3) {
                libraryNeedsRefresh = false
                loadLibrary()
            }
        }
    }

    private fun buildMiniPlayer(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 18).apply {
                setStroke(dp(1), Color.argb(30, 124, 92, 252))
            }
            setPadding(dp(8), dp(8), dp(10), dp(8))
            elevation = dp(14).toFloat()
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { showFullPlayer() }
        }

        val artWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).also { it.rightMargin = dp(10) }
            background = rounded(elevated, 12)
            clipToOutline = true
            outlineProvider = roundRectOutline(12)
        }
        val art = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        miniArtView = art
        artWrap.addView(art)
        bar.addView(artWrap)

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        miniTitle = label("Aurora Player", 13, text, true)
        miniTitle?.maxLines = 1
        miniTitle?.ellipsize = TextUtils.TruncateAt.END
        miniArtist = label("Tap a song to play", 11, textSecondary, false)
        miniArtist?.maxLines = 1
        miniArtist?.ellipsize = TextUtils.TruncateAt.END
        meta.addView(miniTitle)
        meta.addView(miniArtist)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(purple)
            progressBackgroundTintList = ColorStateList.valueOf(higher)
            layoutParams = LinearLayout.LayoutParams(MP, dp(3)).also { it.topMargin = dp(6) }
            max = 100
        }
        miniProgress = progress
        meta.addView(progress)
        bar.addView(meta)

        val prev = ImageView(this).apply {
            setImageResource(R.drawable.ic_skip_previous)
            imageTintList = ColorStateList.valueOf(textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.leftMargin = dp(8) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { svc?.previous() }
            contentDescription = "Previous track"
        }
        val play = ImageView(this).apply {
            val size = dp(32)
            setImageResource(R.drawable.ic_play)
            background = rounded(purple, 16)
            imageTintList = ColorStateList.valueOf(text)
            layoutParams = LinearLayout.LayoutParams(size, size).also { it.leftMargin = dp(8) }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { svc?.togglePlayPause() }
            contentDescription = "Play or pause"
        }
        miniPlayBtn = play
        val next = ImageView(this).apply {
            setImageResource(R.drawable.ic_skip_next)
            imageTintList = ColorStateList.valueOf(textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.leftMargin = dp(8) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { svc?.next() }
            contentDescription = "Next track"
        }
        bar.addView(prev)
        bar.addView(play)
        bar.addView(next)
        return bar
    }

    private fun applyPlayerInsets(insets: WindowInsets): Unit {
        val statusInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.statusBars()).top
        } else 0
        val navInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else 0
        val cutoutInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            insets.getInsets(WindowInsets.Type.displayCutout()).top
        } else 0
        val safeTopInset = maxOf(statusInset, cutoutInset)
        val centerHeaderLift = if (cutoutInset > 0) maxOf(cutoutInset / 2, dp(12)) else dp(8)

        fpHeaderCenter?.setPadding(0, centerHeaderLift, 0, 0)
        fpContentContainer?.setPadding(dp(20), safeTopInset + dp(18), dp(20), navInset + dp(18))
    }

    private fun buildFullPlayerOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(bg)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            elevation = dp(32).toFloat()
        }

        val rootBackground = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            setBackgroundColor(Color.argb(255, 7, 7, 10))
        }

        fpBackdropView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.22f
        }
        val bgGlow = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(80, 124, 92, 252), Color.argb(30, 8, 7, 11), Color.argb(200, 7, 7, 10))
            )
        }
        val vignette = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(140, 7, 7, 10), Color.argb(220, 7, 7, 10))
            )
        }
        rootBackground.addView(fpBackdropView)
        rootBackground.addView(bgGlow)
        rootBackground.addView(vignette)
        overlay.addView(rootBackground)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            fitsSystemWindows = false
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(MP, MP)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(MP, WC)
        }
        fpContentContainer = content

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, dp(54)).also { it.bottomMargin = dp(8) }
        }
        val down = buildIconButton(R.drawable.ic_chevron_down, dp(24), text) { hideFullPlayer() }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            setPadding(0, dp(8), 0, 0)
        }
        fpHeaderCenter = center
        center.addView(label("PLAYING FROM", 10, purple, true).apply {
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
        })
        center.addView(label("Your Library", 12, textSecondary, false).apply { gravity = Gravity.CENTER })
        val moreAction = buildIconButton(R.drawable.ic_more, dp(22), textSecondary) {
            val cur = svc?.currentTrack ?: allTracks.firstOrNull() ?: return@buildIconButton
            val totalLabel = if (isFavorite(cur)) "Remove from favorites" else "Add to favorites"
            AlertDialog.Builder(this@MainActivity)
                .setTitle(cur.title)
                .setMessage("Artist: ${cur.artist}\nAlbum: ${cur.album}\nDuration: ${cur.durationLabel}")
                .setPositiveButton(totalLabel) { _, _ ->
                    toggleFavorite(cur)
                }
                .setNeutralButton("Queue") { _, _ -> showQueue() }
                .setNegativeButton("Close", null)
                .show()
        }
        header.addView(down)
        header.addView(center)
        header.addView(moreAction)
        content.addView(header)

        val currentTopInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
        } else 0
        val currentCutoutInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.displayCutout())?.top ?: 0
        } else 0
        val currentNavInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())?.bottom ?: 0
        } else 0
        val availableHeight = resources.displayMetrics.heightPixels - (maxOf(currentTopInset, currentCutoutInset) + currentNavInset + dp(160))
        val artworkSize = minOf(resources.displayMetrics.widthPixels - dp(120), (availableHeight * 0.46f).toInt()).coerceAtLeast(dp(220))
        val artStage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MP, artworkSize + dp(16)).also { it.bottomMargin = dp(18) }
            setBackgroundColor(Color.TRANSPARENT)
        }
        fpGlowView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.30f
            colorFilter = android.graphics.PorterDuffColorFilter(Color.argb(160, 124, 92, 252), android.graphics.PorterDuff.Mode.SRC_OVER)
        }
        val artCard = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(artworkSize, artworkSize).also { it.gravity = Gravity.CENTER }
            background = rounded(Color.argb(10, 124, 92, 252), 22)
            clipToOutline = true
            outlineProvider = roundRectOutline(22)
            elevation = dp(18).toFloat()
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        fpArtView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = roundRectOutline(18)
        }
        artCard.addView(fpArtView)
        artStage.addView(fpGlowView)
        artStage.addView(artCard)
        content.addView(artStage)

        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(4) }
        }
        val metaText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        fpTitle = label("Nothing playing", 30, text, true)
        fpTitle?.maxLines = 2
        fpTitle?.ellipsize = TextUtils.TruncateAt.END
        fpArtist = label("Choose a song to start listening", 14, textSecondary, false)
        fpArtist?.maxLines = 1
        fpArtist?.ellipsize = TextUtils.TruncateAt.END
        fpAlbum = label("", 11, textMuted, false)
        fpAlbum?.maxLines = 1
        fpAlbum?.ellipsize = TextUtils.TruncateAt.END
        metaText.addView(fpTitle)
        metaText.addView(vGap(2))
        metaText.addView(fpArtist)
        titleWrap.addView(metaText)
        val heart = ImageView(this).apply {
            setImageResource(R.drawable.ic_heart)
            imageTintList = ColorStateList.valueOf(textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).also {
                it.leftMargin = dp(12)
                it.topMargin = dp(8)
            }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener {
                val cur = svc?.currentTrack ?: allTracks.firstOrNull() ?: return@setOnClickListener
                toggleFavorite(cur)
            }
            contentDescription = "Toggle favorite"
        }
        fpHeartBtn = heart
        titleWrap.addView(heart)
        content.addView(titleWrap)

        val seeker = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(purple)
            thumbTintList = ColorStateList.valueOf(purple)
            progressBackgroundTintList = ColorStateList.valueOf(higher)
            splitTrack = false
            layoutParams = LinearLayout.LayoutParams(MP, dp(22)).also { it.topMargin = dp(18) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) fpPosTxt?.text = msToLabel(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) { userSeeking = false; svc?.seekTo(sb?.progress ?: 0) }
            })
        }
        fpSeekBar = seeker
        content.addView(seeker)

        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(6) }
        }
        fpPosTxt = label("0:00", 11, textMuted, false)
        fpDurTxt = label("0:00", 11, textMuted, false)
        times.addView(fpPosTxt)
        times.addView(spacerH())
        times.addView(fpDurTxt)
        content.addView(times)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(14) }
        }
        val shuffle = ImageView(this).apply {
            setImageResource(R.drawable.ic_shuffle)
            imageTintList = ColorStateList.valueOf(if (isShuffle) purple else textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).also { it.rightMargin = dp(18) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener {
                isShuffle = !isShuffle
                svc?.setShuffleEnabled(isShuffle)
                persistState()
                imageTintList = ColorStateList.valueOf(if (isShuffle) purple else textMuted)
            }
        }
        fpShuffleBtn = shuffle
        val prev = ImageView(this).apply {
            setImageResource(R.drawable.ic_skip_previous)
            imageTintList = ColorStateList.valueOf(textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also { it.rightMargin = dp(18) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { svc?.previous() }
        }
        val play = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            background = circle(purple)
            imageTintList = ColorStateList.valueOf(text)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(74)).also { it.rightMargin = dp(18) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            elevation = dp(18).toFloat()
            setOnClickListener { svc?.togglePlayPause() }
        }
        fpPlayBtn = play
        val next = ImageView(this).apply {
            setImageResource(R.drawable.ic_skip_next)
            imageTintList = ColorStateList.valueOf(textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also { it.rightMargin = dp(18) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { svc?.next() }
        }
        val repeatIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_repeat)
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26))
        }
        val repeatBadge = label("1", 9, bg, true).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(13), dp(13)).also {
                it.gravity = Gravity.TOP or Gravity.END
            }
            background = circle(purple)
            visibility = View.GONE
        }
        val repeat = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            isClickable = true
            isFocusable = true
            foreground = ripple()
            addView(repeatIcon)
            addView(repeatBadge)
            setOnClickListener {
                repeatMode = when (repeatMode) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                }
                svc?.setRepeatMode(repeatMode)
                persistState()
                renderRepeatButton()
            }
        }
        fpRepeatBtn = repeatIcon
        fpRepeatBadge = repeatBadge
        renderRepeatButton()
        controls.addView(shuffle)
        controls.addView(prev)
        controls.addView(play)
        controls.addView(next)
        controls.addView(repeat)
        content.addView(controls)

        val pills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(18) }
        }
        pills.addView(buildActionPill(R.drawable.ic_waveform, "Lyrics", true) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Lyrics")
                .setMessage("No lyrics available for this local track in the current build.")
                .setPositiveButton("Close", null)
                .show()
        })
        pills.addView(buildActionPill(R.drawable.ic_queue, "Queue", true) { showQueue() })
        // Replaces the old (unavailable) "Device" pill: opens the existing
        // floating Equalizer overlay. No second Equalizer screen is created.
        pills.addView(buildActionPill(R.drawable.ic_equalizer, "Equalizer", true) { showEqualizer() })
        pills.addView(buildActionPill(R.drawable.ic_more, "More", true) {
            val cur = svc?.currentTrack ?: allTracks.firstOrNull() ?: return@buildActionPill
            val actions = arrayOf("Favorite", "Track info", "Close")
            AlertDialog.Builder(this@MainActivity)
                .setTitle(cur.title)
                .setItems(actions) { _, idx ->
                    when (idx) {
                        0 -> toggleFavorite(cur)
                        1 -> AlertDialog.Builder(this@MainActivity)
                            .setTitle("Track info")
                            .setMessage("${cur.title}\n${cur.artist}\n${cur.album}\n${cur.durationLabel}")
                            .setPositiveButton("Close", null)
                            .show()
                    }
                }
                .show()
        })
        content.addView(pills)

        val queueTitleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(24) }
        }
        queueTitleWrap.addView(label("UP NEXT", 12, textSecondary, true).apply {
            letterSpacing = 0.18f
        })
        queueTitleWrap.addView(spacerH())
        queueTitleWrap.addView(label("See all", 12, purple, true).apply {
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { showQueue() }
        })
        content.addView(queueTitleWrap)

        val queueBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 18)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }
        fpQueueList = queueBox
        content.addView(queueBox)
        updateFullPlayerQueue()

        scroll.addView(content)
        overlay.addView(scroll)
        return overlay
    }

    private fun buildActionPill(iconRes: Int, textLabel: String, enabled: Boolean, onClick: () -> Unit): LinearLayout {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(surface, 16)
            alpha = if (enabled) 1f else 0.6f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).also { it.leftMargin = if (this@MainActivity == this@MainActivity) dp(6) else 0 }
            isClickable = enabled
            isFocusable = enabled
            foreground = ripple()
            setOnClickListener { if (enabled) onClick() }
        }
        if (enabled) {
            pill.background = rounded(Color.argb(24, 124, 92, 252), 16).apply {
                setStroke(dp(1), Color.argb(45, 124, 92, 252))
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(if (enabled) textSecondary else textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        }
        val label = label(textLabel, 12, if (enabled) textSecondary else textMuted, true)
        pill.addView(icon)
        pill.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        pill.addView(label)
        return pill
    }

    private fun updateFullPlayerQueue() {
        val container = fpQueueList ?: return
        container.removeAllViews()

        // Ask the service for the real play order: it is shuffle- and
        // repeat-aware, unlike the raw queue list, so "Up Next" never promises
        // a track that will not actually play next.
        val upcoming = svc?.upcoming(5) ?: emptyList()

        if (upcoming.isEmpty()) {
            val hasQueue = (svc?.queue?.isNotEmpty() == true)
            container.addView(
                label(
                    if (hasQueue) "End of queue" else "Queue is empty",
                    12,
                    textSecondary,
                    false
                ).apply {
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
            )
            return
        }

        upcoming.forEachIndexed { idx, track ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = null
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.bottomMargin = if (idx < upcoming.lastIndex) dp(8) else 0 }
            }
            val art = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).also { it.rightMargin = dp(10) }
                background = rounded(surface, 10)
                clipToOutline = true
                outlineProvider = roundRectOutline(10)
            }
            val iv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(MP, MP)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = roundRectOutline(10)
            }
            ArtworkLoader.loadArtwork(this, track, dp(38), iv)
            art.addView(iv)
            row.addView(art)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            }
            textCol.addView(label(track.title, 13, text, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            textCol.addView(vGap(2))
            textCol.addView(label(track.artist, 11, textSecondary, false).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(textCol)
            row.addView(label("≡", 18, textSecondary, false).apply {
                layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.leftMargin = dp(12) }
            })
            container.addView(row)
        }
    }

    private fun applyFullPlayerEdgeToEdge(enabled: Boolean) {
        if (enabled) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else {
            @Suppress("DEPRECATION")
            window.statusBarColor = bg
            @Suppress("DEPRECATION")
            window.navigationBarColor = navBg
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
        }
    }

    private fun showFullPlayer() {
        isFullPlayerOpen = true
        applyFullPlayerEdgeToEdge(true)
        val cur = svc?.currentTrack ?: allTracks.firstOrNull()
        if (cur != null) {
            onTrackChanged(cur)
            onPlayStateChanged(svc?.isPlaying == true)
            onProgressUpdate(svc?.positionMs ?: 0, svc?.durationMs ?: 0)
        } else {
            // Nothing has ever played: show an honest empty state, never fake track data.
            fpTitle?.text = "Nothing playing"
            fpArtist?.text = "Choose a song to start listening"
            fpAlbum?.text = ""
            fpPosTxt?.text = "0:00"
            fpDurTxt?.text = "0:00"
            fpSeekBar?.max = 1
            fpSeekBar?.progress = 0
            onPlayStateChanged(false)
            updateFullPlayerQueue()
        }
        fullPlayerOverlay?.let { view ->
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(220).start()
        }
        syncMiniPlayerVisibility()
    }

    private fun hideFullPlayer() {
        isFullPlayerOpen = false
        applyFullPlayerEdgeToEdge(false)
        fullPlayerOverlay?.let { view ->
            view.animate().alpha(0f).setDuration(180).withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                syncMiniPlayerVisibility()
            }.start()
        }
    }

    private fun buildQueueOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(bg)
            visibility = View.GONE
            elevation = dp(40).toFloat()
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, dp(48))
        }
        head.addView(label("Queue", 22, text, true))
        head.addView(spacerH())
        head.addView(buildIconButton(R.drawable.ic_close, dp(22), textSecondary) { hideQueue() })
        col.addView(head)
        col.addView(vGap(4))
        val subtitle = label("", 12, textMuted, false)
        queueSubtitle = subtitle
        col.addView(subtitle)
        col.addView(vGap(10))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        queueScroll = scroll
        queueContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(queueContainer)

        col.addView(scroll, LinearLayout.LayoutParams(MP, 0, 1f))
        overlay.addView(col)
        return overlay
    }

    private fun updateQueueUI() {
        val container = queueContainer ?: return
        val queue = svc?.queue ?: emptyList()
        val currentIndex = svc?.queueIndex ?: -1
        val playing = svc?.isPlaying == true

        if (queue.isEmpty()) {
            queueRenderedSignature = emptyList()
            queueRenderedIndex = -1
            queueRenderedPlaying = false
            queueRowViews.clear()
            queueRowTitleViews.clear()
            queueRowStatusViews.clear()
            container.removeAllViews()
            queueSubtitle?.text = ""
            container.addView(buildStateCard("Queue is empty", "Play a track to build your current listening queue.", false))
            return
        }

        val signature = queue.map { it.id to it.uri.toString() }
        val contentUnchanged = signature == queueRenderedSignature && queueRowViews.size == queue.size
        if (contentUnchanged) {
            // Only the active item and/or play state changed: retint in place
            // instead of rebuilding every row and reloading artwork.
            if (currentIndex != queueRenderedIndex || playing != queueRenderedPlaying) {
                applyQueueSelection(currentIndex, playing)
                queueRenderedIndex = currentIndex
                queueRenderedPlaying = playing
            }
            updateQueueSubtitle(queue)
            return
        }

        rebuildQueueRows(container, queue, currentIndex, playing)
        queueRenderedSignature = signature
        queueRenderedIndex = currentIndex
        queueRenderedPlaying = playing
        updateQueueSubtitle(queue)
    }

    private fun rebuildQueueRows(
        container: LinearLayout,
        queue: List<Track>,
        currentIndex: Int,
        playing: Boolean
    ) {
        container.removeAllViews()
        queueRowViews.clear()
        queueRowTitleViews.clear()
        queueRowStatusViews.clear()
        queue.forEachIndexed { idx, track ->
            val isCurrent = idx == currentIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isCurrent) rounded(purpleSoft, 14) else null
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.bottomMargin = dp(8) }
                isClickable = true
                isFocusable = true
                foreground = ripple()
                // Jump within the existing queue: never rebuild or replace it.
                setOnClickListener { svc?.playAt(idx) }
            }
            val art = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).also { it.rightMargin = dp(12) }
                background = rounded(surface, 12)
                clipToOutline = true
                outlineProvider = roundRectOutline(12)
            }
            val iv = ImageView(this).apply { layoutParams = FrameLayout.LayoutParams(MP, MP); scaleType = ImageView.ScaleType.CENTER_CROP }
            ArtworkLoader.loadArtwork(this, track, dp(52), iv)
            art.addView(iv)
            row.addView(art)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            }
            val title = label(track.title, 14, if (isCurrent) purple else text, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(title)
            textCol.addView(vGap(2))
            textCol.addView(label(track.artist, 12, textSecondary, false).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            // Always present so the active state can be toggled without rebuilding.
            val status = label(if (playing) "Now playing" else "Paused", 10, purple, true).apply {
                visibility = if (isCurrent) View.VISIBLE else View.GONE
                layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.topMargin = dp(2) }
            }
            textCol.addView(status)
            row.addView(textCol)

            val remove = ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(textMuted)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).also { it.leftMargin = dp(12) }
                isClickable = true
                isFocusable = true
                foreground = ripple()
                contentDescription = "Remove ${track.title} from queue"
                setOnClickListener { svc?.removeFromQueue(idx) }
            }
            row.addView(remove)
            container.addView(row)
            queueRowViews.add(row)
            queueRowTitleViews.add(title)
            queueRowStatusViews.add(status)
        }
        scrollQueueToCurrent(currentIndex)
    }

    /** Retints the rows when only the active item / play state changed. */
    private fun applyQueueSelection(currentIndex: Int, playing: Boolean) {
        queueRowViews.forEachIndexed { idx, row ->
            val isCurrent = idx == currentIndex
            row.background = if (isCurrent) rounded(purpleSoft, 14) else null
            queueRowTitleViews.getOrNull(idx)?.setTextColor(if (isCurrent) purple else text)
            val status = queueRowStatusViews.getOrNull(idx) ?: return@forEachIndexed
            status.visibility = if (isCurrent) View.VISIBLE else View.GONE
            if (isCurrent) status.text = if (playing) "Now playing" else "Paused"
        }
        scrollQueueToCurrent(currentIndex)
    }

    private fun scrollQueueToCurrent(currentIndex: Int) {
        val target = queueRowViews.getOrNull(currentIndex) ?: return
        queueScroll?.post { queueScroll?.smoothScrollTo(0, target.top) }
    }

    private fun updateQueueSubtitle(queue: List<Track>) {
        queueSubtitle?.text = buildString {
            append("${queue.size} track").append(if (queue.size == 1) "" else "s")
            if (svc?.shuffleEnabled == true) append(" • Shuffle on")
            when (svc?.repeatMode) {
                RepeatMode.ALL -> append(" • Repeat all")
                RepeatMode.ONE -> append(" • Repeat one")
                else -> Unit
            }
        }
    }

    private fun showQueue() {
        updateQueueUI()
        queueOverlay?.let { view ->
            view.bringToFront()
            view.visibility = View.VISIBLE
            view.translationY = view.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
            view.animate().translationY(0f).setDuration(220).start()
        }
    }

    private fun hideQueue() {
        queueOverlay?.let { view ->
            val h = view.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
            view.animate().translationY(h).setDuration(180).withEndAction { view.visibility = View.GONE }.start()
        }
    }

    // ── Equalizer screen ──────────────────────────────────────────────────

    /** Full-screen Equalizer overlay in Aurora's visual language. */
    private fun buildEqualizerOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(208, 8, 7, 11))
            visibility = View.GONE
            elevation = dp(44).toFloat()
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(dp(14), dp(28), dp(14), dp(28))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 28).apply { setStroke(dp(1), softTint(purple, 120)) }
            elevation = dp(22).toFloat()
            clipToOutline = true
            outlineProvider = roundRectOutline(28)
            setPadding(dp(18), dp(16), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }
        eqPanel = panel

        // ── Player context ────────────────────────────────────────────────
        val contextRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val contextArtWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).also { it.rightMargin = dp(10) }
            background = rounded(elevated, 10)
            clipToOutline = true
            outlineProvider = roundRectOutline(10)
        }
        eqContextArt = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        contextArtWrap.addView(eqContextArt)
        contextRow.addView(contextArtWrap)
        val contextText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        eqContextTitle = label("Nothing playing", 12, text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        eqContextArtist = label("Aurora playback", 10, textMuted, false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        contextText.addView(eqContextTitle)
        contextText.addView(eqContextArtist)
        contextRow.addView(contextText)
        panel.addView(contextRow)

        panel.addView(eqDivider())

        // ── Compact header ────────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(2))
        }
        header.addView(label("Equalizer", 20, text, true))
        header.addView(hGap(8))
        eqStatusLabel = label("Saved", 9, lightPurple, true).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = rounded(softTint(lightPurple, 70), 8)
        }
        header.addView(eqStatusLabel)
        header.addView(spacerH())
        header.addView(label("Reset", 11, purple, true).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { resetEqualizer() }
        })
        header.addView(hGap(2))
        header.addView(buildIconButton(R.drawable.ic_close, dp(20), textSecondary) { hideEqualizer() })
        panel.addView(header)

        // ── Enabled ───────────────────────────────────────────────────────
        val enabledRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(10))
        }
        enabledRow.addView(label("Enabled", 13, text, true))
        enabledRow.addView(spacerH())
        eqEnabledSwitch = Switch(this).apply {
            isChecked = equalizerConfig.enabled
            thumbTintList = ColorStateList.valueOf(lightPurple)
            trackTintList = ColorStateList.valueOf(if (equalizerConfig.enabled) purple else higher)
            setOnCheckedChangeListener { _, checked -> onEqualizerEnabledChanged(checked) }
        }
        enabledRow.addView(eqEnabledSwitch)
        panel.addView(enabledRow)

        // ── Draggable EQ graph (the main visual) ──────────────────────────
        val graphCard = FrameLayout(this).apply {
            background = rounded(elevated, 20)
            setPadding(dp(2), dp(8), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }
        eqGraphView = EqGraphView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, dp(EQ_GRAPH_HEIGHT_DP))
            onGainChanged = { index, gainDb -> onEqualizerBandDragged(index, gainDb.roundToInt()) }
            onGainCommitted = { index, gainDb -> onEqualizerBandCommitted(index, gainDb.roundToInt()) }
        }
        graphCard.addView(eqGraphView)
        panel.addView(graphCard)

        // ── Presets ───────────────────────────────────────────────────────
        panel.addView(vGap(14))
        panel.addView(label("PRESET", 10, textSecondary, true).apply { letterSpacing = 0.16f })
        panel.addView(vGap(8))
        val presetScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        eqPresetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presetScroll.addView(eqPresetRow)
        panel.addView(presetScroll)

        // ── Preamp ────────────────────────────────────────────────────────
        panel.addView(vGap(16))
        val preampHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        preampHeader.addView(label("Preamp", 12, text, true))
        preampHeader.addView(spacerH())
        eqPreampValue = label("0 dB", 11, lightPurple, true)
        preampHeader.addView(eqPreampValue)
        panel.addView(preampHeader)
        panel.addView(vGap(2))
        eqPreampBar = SeekBar(this).apply {
            max = EqualizerBands.DEFAULT_MAX_PREAMP_DB - EqualizerBands.DEFAULT_MIN_PREAMP_DB
            progress = equalizerConfig.preampDb - EqualizerBands.DEFAULT_MIN_PREAMP_DB
            splitTrack = false
            progressTintList = ColorStateList.valueOf(purple)
            progressBackgroundTintList = ColorStateList.valueOf(higher)
            thumb = eqThumbDrawable()
            thumbOffset = 0
            layoutParams = LinearLayout.LayoutParams(MP, dp(34))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onEqualizerPreampDragged(progress + EqualizerBands.DEFAULT_MIN_PREAMP_DB)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    eqPreampAnimator?.cancel()
                    eqPreampAnimator = null
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    sb?.let { onEqualizerPreampCommitted(it.progress + EqualizerBands.DEFAULT_MIN_PREAMP_DB) }
                }
            })
        }
        panel.addView(eqPreampBar)

        scroll.addView(panel)
        overlay.addView(scroll)
        return overlay
    }

    /** Subtle hairline used to separate the panel's context area from its header. */
    private fun eqDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MP, dp(1)).also { it.topMargin = dp(12) }
        setBackgroundColor(Color.argb(28, 245, 243, 250))
    }

    /** Rounded light knob with a soft purple halo for the EQ sliders. */
    private fun eqThumbDrawable(): Drawable {
        val halo = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(softTint(lightPurple, 68))
        }
        val knob = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(text)
            setStroke(dp(1), softTint(purple, 180))
        }
        return LayerDrawable(arrayOf(halo, knob)).apply {
            setLayerSize(0, dp(22), dp(22))
            setLayerSize(1, dp(12), dp(12))
            setLayerGravity(1, Gravity.CENTER)
        }
    }

    private fun onEqualizerEnabledChanged(enabled: Boolean) {
        equalizerConfig = equalizerConfig.withEnabled(enabled)
        persistEqualizer()
        renderEqualizer()
    }

    private fun onEqualizerPresetSelected(name: String) {
        equalizerConfig = equalizerConfig.withPreset(name)
        persistEqualizer()
        renderEqualizer()
    }

    /**
     * Live, cheap preview while dragging a band: updates the in-memory config,
     * the dragged gain label, the visual curve and the live effect only — never
     * SharedPreferences and never a full re-render, so dragging stays at frame rate.
     */
    private fun onEqualizerBandDragged(index: Int, gainDb: Int) {
        equalizerConfig = equalizerConfig.withBandGain(index, gainDb)
        // Keep the drawn handle exactly on the clamped value.
        eqGraphView?.setBandGain(index, equalizerConfig.gainsDb.getOrElse(index) { 0 }.toFloat())
        svc?.previewEqualizerConfig(equalizerConfig)
    }

    /** Commit on release: persist once and refresh the detected preset highlight. */
    private fun onEqualizerBandCommitted(index: Int, gainDb: Int) {
        equalizerConfig = equalizerConfig.withBandGain(index, gainDb)
        eqGraphView?.setBandGain(index, equalizerConfig.gainsDb.getOrElse(index) { 0 }.toFloat())
        persistEqualizer()
        renderEqualizerPresets()
    }

    private fun onEqualizerPreampDragged(db: Int) {
        equalizerConfig = equalizerConfig.withPreamp(db)
        eqPreampValue?.text = "${formatGain(equalizerConfig.preampDb)} dB"
        svc?.previewEqualizerConfig(equalizerConfig)
    }

    private fun onEqualizerPreampCommitted(db: Int) {
        equalizerConfig = equalizerConfig.withPreamp(db)
        eqPreampValue?.text = "${formatGain(equalizerConfig.preampDb)} dB"
        persistEqualizer()
    }

    private fun resetEqualizer() {
        equalizerConfig = equalizerConfig.resetToFlat()
        persistEqualizer()
        renderEqualizer()
    }

    /** Saves to the existing preferences and pushes the change to the service. */
    private fun persistEqualizer() {
        equalizerStore.save(equalizerConfig)
        svc?.applyEqualizerConfig(equalizerConfig)
    }

    // ── Equalizer presentation animation ─────────────────────────────────

    private fun neutralGain(i: Int): Float = 0f

    private fun configGain(index: Int): Float =
        equalizerConfig.gainsDb.getOrElse(index) { 0 }.toFloat()

    private fun setBandGain(index: Int, gainDb: Float) {
        eqGraphView?.setBandGain(index, gainDb)
    }

    /**
     * Cancels any in-flight band animation. When [exceptIndex] is the band the
     * user just grabbed, that band is left to the finger while every other band
     * snaps to the committed configuration so nothing is left mid-flight.
     */
    private fun cancelEqualizerBandAnimation(exceptIndex: Int = -1) {
        val animator = eqBandAnimator ?: return
        animator.cancel()
        eqBandAnimator = null
        for (i in 0 until EqualizerBands.BAND_COUNT) {
            if (i != exceptIndex) setBandGain(i, configGain(i))
        }
    }

    /**
     * Animates all ten bands to [targetGains] with a short per-band stagger and
     * an ease-out curve. Presentation only — the live effect is applied
     * separately, so nothing here touches audio or persistence.
     */
    private fun animateBandsTo(
        targetGains: List<Int>,
        durationMs: Long = 210L,
        staggerMs: Long = 13L
    ) {
        cancelEqualizerBandAnimation()
        val graph = eqGraphView ?: return
        val count = EqualizerBands.BAND_COUNT
        val starts = FloatArray(count) { i -> graph.gainsDb.getOrElse(i) { 0f } }
        val ends = FloatArray(count) { i -> targetGains.getOrElse(i) { 0 }.toFloat() }
        if (starts.contentEquals(ends)) {
            graph.setGains(ends.toList())
            return
        }
        val ease = DecelerateInterpolator(1.6f)
        val total = durationMs + staggerMs * (count - 1)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply { this.duration = total }
        animator.addUpdateListener { anim ->
            val elapsed = anim.currentPlayTime.toFloat()
            for (i in 0 until count) {
                val localT = ((elapsed - staggerMs * i) / durationMs).coerceIn(0f, 1f)
                val value = starts[i] + (ends[i] - starts[i]) * ease.getInterpolation(localT)
                graph.setBandGain(i, value)
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (eqBandAnimator === animator) eqBandAnimator = null
            }
        })
        eqBandAnimator = animator
        animator.start()
    }

    private fun animatePreampTo(db: Int) {
        val bar = eqPreampBar ?: return
        val target = (db - EqualizerBands.DEFAULT_MIN_PREAMP_DB).coerceIn(0, bar.max)
        eqPreampAnimator?.cancel()
        eqPreampAnimator = null
        if (bar.progress == target) {
            eqPreampValue?.text = "${formatGain(db)} dB"
            return
        }
        val animator = ValueAnimator.ofInt(bar.progress, target).apply {
            duration = 210L
            interpolator = DecelerateInterpolator(1.6f)
        }
        animator.addUpdateListener { anim ->
            val value = anim.animatedValue as Int
            bar.progress = value
            eqPreampValue?.text = "${formatGain(value + EqualizerBands.DEFAULT_MIN_PREAMP_DB)} dB"
        }
        eqPreampAnimator = animator
        animator.start()
    }

    /** Renders all equalizer controls, animating bands/preamp for a premium feel. */
    private fun renderEqualizer(animate: Boolean = true) {
        eqEnabledSwitch?.apply {
            isChecked = equalizerConfig.enabled
            trackTintList = ColorStateList.valueOf(if (equalizerConfig.enabled) purple else higher)
        }
        if (animate) {
            animateBandsTo(equalizerConfig.gainsDb)
            animatePreampTo(equalizerConfig.preampDb)
        } else {
            eqGraphView?.setGains(equalizerConfig.gainsDb.map { it.toFloat() })
            eqPreampBar?.progress = equalizerConfig.preampDb - EqualizerBands.DEFAULT_MIN_PREAMP_DB
            eqPreampValue?.text = "${formatGain(equalizerConfig.preampDb)} dB"
        }
        renderEqualizerPresets()
        renderEqualizerStatus()
        renderEqualizerContext()
    }

    private fun renderEqualizerPresets() {
        val row = eqPresetRow ?: return
        row.removeAllViews()
        val presets = EqualizerPresets.SELECTABLE + EqualizerPresets.CUSTOM
        val selected = equalizerConfig.preset
        val animateSelection = selected != eqRenderedPreset
        presets.forEachIndexed { index, name ->
            val active = name == selected
            val chip = label(name, 11, if (active) bg else textSecondary, active).apply {
                setPadding(dp(13), dp(7), dp(13), dp(7))
                background = if (active) rounded(purple, 13) else rounded(elevated, 13)
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener { onEqualizerPresetSelected(name) }
            }
            if (active && animateSelection) animateChipActivation(chip)
            row.addView(chip)
            if (index < presets.lastIndex) row.addView(hGap(7))
        }
        eqRenderedPreset = selected
    }

    /** Smoothly transitions a chip from the muted state into the active tint. */
    private fun animateChipActivation(chip: TextView) {
        chip.scaleX = 0.92f
        chip.scaleY = 0.92f
        chip.animate().scaleX(1f).scaleY(1f).setDuration(190L)
            .setInterpolator(DecelerateInterpolator()).start()
        val evaluator = ArgbEvaluator()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply { duration = 200L }
        animator.addUpdateListener { anim ->
            val t = anim.animatedFraction
            chip.background = rounded(evaluator.evaluate(t, elevated, purple) as Int, 13)
            chip.setTextColor(evaluator.evaluate(t, textSecondary, bg) as Int)
        }
        animator.start()
    }

    private fun renderEqualizerStatus() {
        val view = eqStatusLabel ?: return
        val (short, detail, color) = when {
            svc?.equalizerSupported == true ->
                Triple("Active", "Active on Aurora playback", lightPurple)
            svc?.equalizerInitialized == true ->
                Triple("Unsupported", "This device does not expose an equalizer effect", Color.rgb(255, 190, 92))
            else ->
                Triple("Saved", "Saved — activates when Aurora starts playing", textMuted)
        }
        view.text = short
        view.setTextColor(color)
        view.background = rounded(softTint(color, 70), 8)
        view.contentDescription = detail
    }

    private fun renderEqualizerContext() {
        val track = svc?.currentTrack
        if (track == null) {
            eqContextTitle?.text = "Nothing playing"
            eqContextArtist?.text = "Aurora playback"
            return
        }
        eqContextTitle?.text = track.title.ifBlank { "Unknown track" }
        eqContextArtist?.text = track.artist.ifBlank { "Unknown artist" }
        eqContextArt?.let { ArtworkLoader.loadArtwork(this, track, dp(34), it) }
    }

    private fun formatGain(db: Int): String = if (db > 0) "+$db" else db.toString()

    private fun showEqualizer() {
        equalizerConfig = try { equalizerStore.load() } catch (_: Exception) { equalizerConfig }
        eqRenderedPreset = null
        // Chrome first, then the bands "power on" from neutral to the saved curve.
        eqEnabledSwitch?.apply {
            isChecked = equalizerConfig.enabled
            trackTintList = ColorStateList.valueOf(if (equalizerConfig.enabled) purple else higher)
        }
        eqPreampBar?.progress = equalizerConfig.preampDb - EqualizerBands.DEFAULT_MIN_PREAMP_DB
        eqPreampValue?.text = "${formatGain(equalizerConfig.preampDb)} dB"
        // Start flat, then "power on" to the saved curve.
        eqGraphView?.setGains(List(EqualizerBands.BAND_COUNT) { neutralGain(it) })
        renderEqualizerPresets()
        renderEqualizerStatus()
        renderEqualizerContext()
        animateBandsTo(equalizerConfig.gainsDb)

        val overlay = equalizerOverlay ?: return
        overlay.bringToFront()
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        val panel = eqPanel
        panel?.apply {
            scaleX = 0.97f
            scaleY = 0.97f
            translationY = dp(14).toFloat()
        }
        overlay.animate().alpha(1f).setDuration(190L).start()
        panel?.animate()?.scaleX(1f)?.scaleY(1f)?.translationY(0f)
            ?.setDuration(220L)?.setInterpolator(DecelerateInterpolator())?.start()
    }

    private fun hideEqualizer() {
        val overlay = equalizerOverlay ?: return
        eqBandAnimator?.cancel(); eqBandAnimator = null
        eqPreampAnimator?.cancel(); eqPreampAnimator = null
        overlay.animate().alpha(0f).setDuration(150L).withEndAction {
            overlay.visibility = View.GONE
            overlay.alpha = 1f
        }.start()
        eqPanel?.animate()?.scaleX(0.97f)?.scaleY(0.97f)?.translationY(dp(14).toFloat())
            ?.setDuration(150L)?.start()
    }

    /**
     * Interactive EQ graph: dB scale, frequency labels, the Aurora curve with a
     * soft glow, and draggable band handles. Visual only — it reports gain
     * changes to the Activity, which applies them to the live effect.
     */
    private inner class EqGraphView(context: Context) : View(context) {
        val gainsDb = FloatArray(EqualizerBands.BAND_COUNT) { 0f }
        var onGainChanged: ((index: Int, gainDb: Float) -> Unit)? = null
        var onGainCommitted: ((index: Int, gainDb: Float) -> Unit)? = null

        private val minDb = EqualizerBands.DEFAULT_MIN_DB
        private val maxDb = EqualizerBands.DEFAULT_MAX_DB
        private val scaleValues = listOf(10, 5, 0, -5, -10)
        private var activeBand = -1

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(20, 245, 243, 250)
            strokeWidth = dp(1).toFloat()
        }
        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = softTint(purple, 90)
            strokeWidth = dp(1).toFloat()
        }
        private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2.5f).toFloat()
            color = purple
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(10).toFloat()
            color = softTint(lightPurple, 55)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = text
        }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = purple
        }
        private val handleHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = softTint(lightPurple, 80)
        }
        private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textMuted
            textSize = sp(9f)
            textAlign = Paint.Align.RIGHT
        }
        private val freqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textMuted
            textSize = sp(9f)
            textAlign = Paint.Align.CENTER
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lightPurple
            textSize = sp(9f)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val path = Path()

        fun setGains(values: List<Float>) {
            for (i in values.indices) {
                if (i < gainsDb.size) gainsDb[i] = values[i].coerceIn(minDb.toFloat(), maxDb.toFloat())
            }
            activeBand = -1
            invalidate()
        }

        fun setBandGain(index: Int, gainDb: Float) {
            if (index !in gainsDb.indices) return
            gainsDb[index] = gainDb.coerceIn(minDb.toFloat(), maxDb.toFloat())
            invalidate()
        }

        private fun plotLeft() = dp(28).toFloat()
        private fun plotRight() = width - dp(10).toFloat()
        private fun plotTop() = dp(14).toFloat()
        private fun plotBottom() = height - dp(20).toFloat()

        private fun bandX(index: Int): Float {
            val left = plotLeft()
            val right = plotRight()
            if (EqualizerBands.BAND_COUNT <= 1) return (left + right) / 2f
            return left + (right - left) * index / (EqualizerBands.BAND_COUNT - 1)
        }

        private fun gainY(gainDb: Float): Float {
            val top = plotTop()
            val bottom = plotBottom()
            return top + (maxDb - gainDb) / (maxDb - minDb).toFloat() * (bottom - top)
        }

        private fun gainFromY(y: Float): Float {
            val top = plotTop()
            val bottom = plotBottom()
            val t = ((y - top) / (bottom - top)).coerceIn(0f, 1f)
            return maxDb - t * (maxDb - minDb)
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val left = plotLeft()
            val right = plotRight()
            val top = plotTop()
            val bottom = plotBottom()

            // Horizontal dB grid + scale labels.
            scaleValues.forEach { db ->
                val y = gainY(db.toFloat())
                canvas.drawLine(left, y, right, y, if (db == 0) centerPaint else gridPaint)
                canvas.drawText(if (db > 0) "+$db" else "$db", left - dp(6), y + dp(3), scalePaint)
            }
            // Vertical band grid + frequency labels.
            for (i in 0 until EqualizerBands.BAND_COUNT) {
                val x = bandX(i)
                canvas.drawLine(x, top, x, bottom, gridPaint)
                canvas.drawText(EqualizerBands.CENTER_LABELS[i], x, height - dp(5).toFloat(), freqPaint)
            }

            // Smooth curve through the handles.
            path.reset()
            for (i in gainsDb.indices) {
                val x = bandX(i)
                val y = gainY(gainsDb[i])
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    val px = bandX(i - 1)
                    val py = gainY(gainsDb[i - 1])
                    val midX = (px + x) / 2f
                    path.cubicTo(midX, py, midX, y, x, y)
                }
            }
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, curvePaint)

            // Handles.
            for (i in gainsDb.indices) {
                val x = bandX(i)
                val y = gainY(gainsDb[i])
                val active = i == activeBand
                val radius = dp(if (active) 8 else 6).toFloat()
                if (active) canvas.drawCircle(x, y, radius + dp(6), handleHalo)
                canvas.drawCircle(x, y, radius, handleFill)
                canvas.drawCircle(x, y, radius, handleRing)
                if (active) {
                    canvas.drawText(formatGain(gainsDb[i].roundToInt()), x, y - radius - dp(8), valuePaint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val nearest = nearestBand(x)
                    if (nearest < 0) return false
                    activeBand = nearest
                    parent?.requestDisallowInterceptTouchEvent(true)
                    applyTouch(nearest, y, commit = false)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeBand < 0) return false
                    applyTouch(activeBand, y, commit = false)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeBand < 0) return false
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        applyTouch(activeBand, y, commit = true)
                    }
                    activeBand = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun nearestBand(x: Float): Int {
            var best = -1
            var bestDistance = dp(34).toFloat()
            for (i in gainsDb.indices) {
                val distance = abs(x - bandX(i))
                if (distance <= bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
            return best
        }

        private fun applyTouch(index: Int, y: Float, commit: Boolean) {
            gainsDb[index] = gainFromY(y).coerceIn(minDb.toFloat(), maxDb.toFloat())
            invalidate()
            if (commit) onGainCommitted?.invoke(index, gainsDb[index])
            else onGainChanged?.invoke(index, gainsDb[index])
        }
    }

    private fun buildBottomNav(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(navBg)
            elevation = dp(18).toFloat()
        }
        root.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, dp(1)).also { it.gravity = Gravity.TOP }
            setBackgroundColor(Color.argb(36, 255, 255, 255))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(MP, MP)
        }
        val items = listOf(
            0 to Pair(R.drawable.ic_nav_home, "Home"),
            1 to Pair(R.drawable.ic_nav_search, "Search"),
            3 to Pair(R.drawable.ic_nav_library, "Library")
        )
        items.forEach { (targetIdx, pair) ->
            val (iconRes, labelText) = pair
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, MP, 1f)
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener { showTab(targetIdx) }
                tag = targetIdx // Store the target tab index
            }
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(if (targetIdx == selectedTab) purple else textMuted)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            val lbl = label(labelText, 10, if (targetIdx == selectedTab) purple else textMuted, targetIdx == selectedTab)
            lbl.gravity = Gravity.CENTER
            tab.addView(icon)
            tab.addView(vGap(4))
            tab.addView(lbl)
            navTabs.add(tab)
            row.addView(tab)
        }
        root.addView(row)
        return root
    }

    private fun updateNavSelection() {
        navTabs.forEach { tab ->
            val targetIdx = tab.tag as Int
            tab.removeAllViews()
            val iconRes = when (targetIdx) {
                0 -> R.drawable.ic_nav_home
                1 -> R.drawable.ic_nav_search
                3 -> R.drawable.ic_nav_library
                else -> R.drawable.ic_nav_home
            }
            val titleText = when (targetIdx) {
                0 -> "Home"
                1 -> "Search"
                3 -> "Library"
                else -> "Home"
            }
            val color = if (targetIdx == selectedTab) purple else textMuted
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(color)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            val title = label(titleText, 10, color, targetIdx == selectedTab)
            title.gravity = Gravity.CENTER
            tab.addView(icon)
            tab.addView(vGap(4))
            tab.addView(title)
        }
    }

    private fun syncMiniPlayerVisibility() {
        miniPlayerBar?.visibility = if (!isFullPlayerOpen && svc?.currentTrack != null) View.VISIBLE else View.GONE
        miniPlayerBar?.alpha = if (miniPlayerBar?.visibility == View.VISIBLE) 1f else 0f
    }

    private fun buildStateCard(title: String, subtitle: String, loading: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(surface, 18)
            setPadding(dp(24), dp(28), dp(24), dp(28))
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(16) }
        }
        if (loading) {
            val pb = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(purple) }
            card.addView(pb)
            card.addView(vGap(12))
        } else {
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_aurora_logo)
                imageTintList = ColorStateList.valueOf(purple)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            }
            card.addView(icon)
            card.addView(vGap(12))
        }
        card.addView(label(title, 16, text, true).apply { gravity = Gravity.CENTER })
        card.addView(vGap(4))
        card.addView(label(subtitle, 12, textSecondary, false).apply { gravity = Gravity.CENTER })
        return card
    }

    private fun dividerRow(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MP, dp(1))
        setBackgroundColor(Color.argb(25, 255, 255, 255))
    }

    /** Full-width primary action button (Aurora purple pill). */
    private fun buildPrimaryButton(text: String, onClick: () -> Unit): FrameLayout {
        return FrameLayout(this).apply {
            background = rounded(purple, 12)
            isClickable = true
            isFocusable = true
            foreground = ripple()
            layoutParams = LinearLayout.LayoutParams(MP, dp(46))
            setOnClickListener { onClick() }
        }.also { button ->
            button.addView(label(text, 14, bg, true).apply {
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(WC, WC).also { it.gravity = Gravity.CENTER }
            })
        }
    }

    private fun buildIconButton(iconRes: Int, sizeDp: Int, color: Int, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(sizeDp, sizeDp)
        isClickable = true
        isFocusable = true
        foreground = ripple()
        setOnClickListener { onClick() }
    }

    private fun label(text: String, sizeSp: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** A low-alpha tint of [color], used for soft badge/chip backgrounds. */
    private fun softTint(color: Int, alpha: Int = 34): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Small soft-tinted pill used for source / bucket badges across lists. */
    private fun sourceBadge(text: String, color: Int): TextView =
        label(text, 10, color, true).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = rounded(softTint(color), 9)
        }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun ripple(): android.graphics.drawable.Drawable? {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        val d = ta.getDrawable(0)
        ta.recycle()
        return d
    }

    private fun vGap(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MP, dp(h))
    }

    private fun hGap(w: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(w), 1)
    }

    private fun spacerH() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private fun roundRectOutline(radiusDp: Int) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
        }
    }

    private fun triggerPlay(track: Track) {
        svc?.playTrack(track, allTracks)
    }

    private fun msToLabel(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
