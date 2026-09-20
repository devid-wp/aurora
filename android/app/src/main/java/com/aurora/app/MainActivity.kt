package com.aurora.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.repositories.DownloadRepository
import com.aurora.app.database.repositories.LibraryRepository
import com.aurora.app.import.FilePickerIntentFactory
import com.aurora.app.import.ImportResult
import com.aurora.app.import.ImportStatus
import com.aurora.app.import.MusicImportManager
import com.aurora.app.source.DownloadAvailability
import com.aurora.app.source.LocalSource
import com.aurora.app.source.MusicSource
import com.aurora.app.source.SoundCloudSource
import com.aurora.app.source.SourceCapability
import com.aurora.app.source.SourceMetadata
import com.aurora.app.source.SourceSearchResult
import com.aurora.app.source.SourceTrackId
import com.aurora.app.transfer.DownloadManager
import com.aurora.app.transfer.DownloadProgress
import com.aurora.app.transfer.DownloadState
import java.io.File

class MainActivity : Activity() {
    private val bg = Color.rgb(7, 7, 10)
    private val surface = Color.rgb(18, 19, 26)
    private val elevated = Color.rgb(26, 27, 35)
    private val higher = Color.rgb(35, 36, 47)
    private val purple = Color.rgb(124, 92, 252)
    private val lightPurple = Color.rgb(157, 124, 255)
    private val purpleSoft = Color.argb(35, 124, 92, 252)
    private val text = Color.rgb(245, 243, 255)
    private val textSecondary = Color.rgb(184, 177, 208)
    private val textMuted = Color.rgb(120, 114, 148)
    private val navBg = Color.rgb(14, 14, 20)
    private val divider = Color.argb(30, 255, 255, 255)

    private var svc: PlaybackService? = null
    private var bound = false
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = (binder as PlaybackService.LocalBinder).service()
            bound = true
            svc?.listener = playbackListener
            svc?.setShuffleEnabled(isShuffle)
            svc?.setRepeatEnabled(isRepeat)
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
    }

    private val database by lazy { AuroraDatabase.getInstance(applicationContext) }
    private val libraryRepository by lazy { LibraryRepository(database) }
    private val downloadRepository by lazy { DownloadRepository(database) }
    private val importManager by lazy { MusicImportManager(this, database) }
    private val downloadManager by lazy { DownloadManager(this, database) }
    private val soundCloudSource by lazy { SoundCloudSource(applicationContext) }
    private val localSource by lazy { LocalSource(libraryRepository, context = applicationContext) }
    private var allTracks: List<Track> = emptyList()
    private var activeDownloadView = false
    private var libraryNeedsRefresh = false
    private var currentSearchSource = "local"

    private enum class LibrarySection { TRACKS, ALBUMS, ARTISTS }
    private enum class TrackFilter { ALL, LOCAL, DOWNLOADED, ONLINE }

    private val recents = mutableListOf<Track>()
    private val favoriteTrackIds = mutableSetOf<Long>()
    private val preferences by lazy { getSharedPreferences("aurora_preferences", Context.MODE_PRIVATE) }
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
    private var trackFilterRow: LinearLayout? = null
    private var searchResultsContainer: LinearLayout? = null
    private var searchStatusContainer: LinearLayout? = null
    private var searchEmptyState: LinearLayout? = null
    private var searchInput: EditText? = null
    private var searchSourceTabs = mutableListOf<FrameLayout>()
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
    private var isRepeat = false
    private var userSeeking = false

    private companion object {
        const val RC_PERM = 42
        const val RC_IMPORT_MUSIC = 43
        const val DISCOVERY_SEEN_KEY = "discovery_seen"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restorePreferences()

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
        showTab(0)
        checkAndLoad()
        handleSoundCloudCallback(intent)
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
        handleSoundCloudCallback(intent)
    }

    /**
     * Handles the aurora://soundcloud/callback deep link that the browser
     * redirects to after OAuth consent. Exchanges the authorization code for
     * a token session on a background thread.
     */
    private fun handleSoundCloudCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "aurora" || data.host != "soundcloud") return

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
            runOnUiThread {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                refreshSoundCloudSettings()
            }
        }.start()
    }

    /** Opens the official SoundCloud consent page (authorization code + PKCE). */
    private fun startSoundCloudConnect() {
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
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_PERM && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            loadLibrary()
        } else {
            showPermissionDenied()
        }
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
        val set = preferences.getStringSet("favorite_track_ids", emptySet()) ?: emptySet()
        favoriteTrackIds.clear(); favoriteTrackIds.addAll(set.mapNotNull { it.toLongOrNull() })
        isShuffle = preferences.getBoolean("shuffle_enabled", false)
        isRepeat = preferences.getBoolean("repeat_enabled", false)
    }

    private fun persistState() {
        preferences.edit()
            .putStringSet("favorite_track_ids", favoriteTrackIds.map(Long::toString).toSet())
            .putBoolean("shuffle_enabled", isShuffle)
            .putBoolean("repeat_enabled", isRepeat)
            .putString("recent_track_ids", recents.joinToString(",") { it.id.toString() })
            .apply()
    }

    private fun checkAndLoad() {
        if (hasAudioPermission()) {
            loadLibrary()
        } else {
            showPermissionRequest()
        }
    }

    private fun hasAudioPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        requestPermissions(arrayOf(perm), RC_PERM)
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
            val mediaTracks = MusicScanner.scan(this)
            // Keep the library table in sync so source-backed search (LocalSource)
            // can see device tracks, not just Aurora-imported ones.
            runCatching { libraryRepository.syncMediaStoreTracks(mediaTracks) }
            val importedTracks = libraryRepository.getAuroraTracks()
            val merged = (mediaTracks + importedTracks).distinctBy { it.uri.toString() + it.id }
            runOnUiThread { onTracksLoaded(merged) }
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
        libraryContainer?.addView(buildStateCard("Scanning music library...", "Locating local tracks and artwork", true))
    }

    private fun showEmptyState() {
        libraryContainer?.removeAllViews()
        libraryContainer?.addView(buildStateCard("No tracks found", "No local audio was detected.", false))
    }

    private fun showPermissionRequest() {
        libraryContainer?.removeAllViews()
        libraryContainer?.addView(buildActionCard("Access your local music", "Aurora needs storage access to scan and play your tracks.", "Grant access") { requestAudioPermission() })
    }

    private fun showPermissionDenied() {
        libraryContainer?.removeAllViews()
        libraryContainer?.addView(buildActionCard("Permission required", "Aurora cannot index your library without audio access.", "Try again") { requestAudioPermission() })
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

        val isFav = favoriteTrackIds.contains(track.id)
        fpHeartBtn?.imageTintList = ColorStateList.valueOf(if (isFav) purple else textMuted)
        heroFavBtn?.imageTintList = ColorStateList.valueOf(if (isFav) purple else textMuted)
        recents.removeAll { it.id == track.id }
        recents.add(0, track)
        while (recents.size > 8) recents.removeAt(recents.lastIndex)
        persistState()
        updateRecentsUI()
        updateQueueUI()
        updateFullPlayerQueue()
        updateHeroTrack(track)
        syncMiniPlayerVisibility()
    }

    private fun onPlayStateChanged(isPlaying: Boolean) {
        val res = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        miniPlayBtn?.setImageResource(res)
        miniPlayBtn?.imageTintList = ColorStateList.valueOf(if (isPlaying) text else purple)
        fpPlayBtn?.setImageResource(res)
        fpPlayBtn?.background = circle(if (isPlaying) purple else purple)
        heroPlayBtn?.setImageResource(res)
    }

    private fun onProgressUpdate(posMs: Int, durMs: Int) {
        if (durMs > 0) {
            miniProgress?.max = durMs
            miniProgress?.progress = posMs
            if (!userSeeking) {
                fpSeekBar?.max = durMs
                fpSeekBar?.progress = posMs
                fpPosTxt?.text = msToLabel(posMs)
            }
            fpDurTxt?.text = msToLabel(durMs)
            heroProgress?.max = durMs
            heroProgress?.progress = posMs
            heroPosTxt?.text = msToLabel(posMs)
            heroDurTxt?.text = msToLabel(durMs)
        }
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
        page.addView(buildHeroSection())
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
        val likedCount = favoriteTrackIds.size
        val recentCount = recents.size
        val downloadCount = allTracks.count { trackBucket(it) == TrackFilter.DOWNLOADED }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(quickCard(R.drawable.ic_heart, "Liked", songsLabel(likedCount), 1f, 0) {
            val firstLiked = allTracks.firstOrNull { favoriteTrackIds.contains(it.id) }
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
        heroFavBtn?.imageTintList = ColorStateList.valueOf(if (favoriteTrackIds.contains(track.id)) purple else textMuted)
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
        page.addView(label("Find tracks on this device and online.", 13, textSecondary, false))
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
            hint = "Artists, albums, tracks..."
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
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
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

        searchSourceTabs.clear()
        val sourceRow = buildSegmentedRow(
            listOf("Local", "SoundCloud"),
            if (currentSearchSource == "local") 0 else 1,
            searchSourceTabs
        ) { index ->
            currentSearchSource = if (index == 0) "local" else "soundcloud"
            updateSegmentedSelection(searchSourceTabs, index)
            filterSearch(searchInput?.text?.toString() ?: "")
        }
        page.addView(sourceRow)
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
        val mapped = mapSearchResults(batch, soundCloudSource).filter { it.playable }
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
        val unavailable: Boolean
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

    private fun runSearch(query: String) {
        val results = searchResultsContainer ?: return
        val status = searchStatusContainer ?: return
        searchDownloadButtons.clear()
        results.removeAllViews()
        status.removeAllViews()
        results.visibility = View.GONE
        status.visibility = View.VISIBLE
        if (query.isBlank()) {
            status.addView(
                buildStateCard(
                    "Search your music",
                    if (currentSearchSource == "soundcloud") {
                        "Find tracks on SoundCloud. Results stream, and can be downloaded when the artist allows it."
                    } else {
                        "Find tracks on this device and in Aurora storage."
                    },
                    false
                )
            )
            return
        }

        val requestId = ++searchRequestToken
        val source: MusicSource = if (currentSearchSource == "soundcloud") soundCloudSource else localSource
        status.addView(
            buildStateCard(
                "Searching…",
                if (currentSearchSource == "soundcloud") "Querying SoundCloud for \"$query\"" else "Searching your library for \"$query\"",
                true
            )
        )
        Thread {
            val outcome = try {
                source.searchDetailed(query)
            } catch (e: Exception) {
                SourceSearchResult.Error(e.message ?: "Search failed")
            }
            val mapped = if (outcome is SourceSearchResult.Success) mapSearchResults(outcome.results, source) else emptyList()
            runOnUiThread {
                if (requestId != searchRequestToken) return@runOnUiThread
                renderSearchOutcome(outcome, mapped, query)
            }
        }.start()
    }

    /** Background-thread mapping: resolves "already downloaded" and capability flags per hit. */
    private fun mapSearchResults(results: List<SourceMetadata>, querySource: MusicSource): List<SearchResult> {
        val auroraTracks = try {
            libraryRepository.getAllTracks().filter { it.isAuroraImported || it.sourceType == "aurora_imported" }
        } catch (_: Exception) {
            emptyList()
        }
        return results.map { metadata ->
            val isLocal = metadata.trackId.source == localSource.sourceId
            val localCopy: File? = if (isLocal) {
                metadata.localPath?.let { File(it) }?.takeIf { it.exists() }
            } else {
                auroraTracks.firstOrNull {
                    it.title.equals(metadata.title, ignoreCase = true) &&
                        (metadata.artist.isBlank() || it.artist.equals(metadata.artist, ignoreCase = true))
                }?.localPath?.let { File(it) }?.takeIf { it.exists() }
            }
            val caps = metadata.sourceCapabilities
            val streamable = caps.contains(SourceCapability.STREAM)
            val previewOnly = caps.contains(SourceCapability.PREVIEW) && !streamable
            val playable = streamable || previewOnly
            val unavailable = !playable
            val downloaded = localCopy != null
            val downloadable = !downloaded && (caps.contains(SourceCapability.DOWNLOAD) || (isLocal && metadata.localUri != null))
            val label = when {
                downloaded && !isLocal -> "SoundCloud • downloaded"
                downloaded -> "Local • downloaded"
                isLocal -> "On device"
                else -> "SoundCloud"
            }
            val resolvedUri = localCopy?.let { Uri.fromFile(it) } ?: metadata.localUri ?: Uri.EMPTY
            // Reuse the library Track (with its real DB id) whenever the audio is
            // already on the device, so playback queue indexing stays correct.
            val matched = allTracks.firstOrNull { it.uri == resolvedUri }
            SearchResult(
                metadata = metadata,
                source = if (downloaded) localSource else querySource,
                sourceLabel = label,
                track = matched?.copy(uri = resolvedUri) ?: Track(
                    id = stableTrackId(metadata.trackId.source, metadata.trackId.value),
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album.ifBlank { if (isLocal) "" else "SoundCloud" },
                    duration = metadata.durationMs,
                    uri = resolvedUri,
                    albumId = 0L
                ),
                playable = playable,
                preview = previewOnly,
                downloadable = downloadable,
                downloaded = downloaded,
                unavailable = unavailable
            )
        }
    }

    private fun renderSearchOutcome(outcome: SourceSearchResult, mapped: List<SearchResult>, query: String) {
        val results = searchResultsContainer ?: return
        val status = searchStatusContainer ?: return
        results.removeAllViews()
        status.removeAllViews()
        when (outcome) {
            is SourceSearchResult.Success -> {
                status.visibility = View.GONE
                results.visibility = View.VISIBLE
                results.addView(label("${mapped.size} result${if (mapped.size == 1) "" else "s"} for \"$query\"", 12, textMuted, false).apply {
                    setPadding(0, 0, 0, dp(10))
                })
                mapped.forEachIndexed { index, entry ->
                    results.addView(buildSearchResultRow(entry))
                    if (index < mapped.lastIndex) results.addView(dividerRow())
                }
            }
            is SourceSearchResult.Empty -> {
                results.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.addView(buildStateCard("No results", outcome.message.ifBlank { "Nothing matched \"$query\"." }, false))
            }
            is SourceSearchResult.NotConfigured -> {
                results.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.addView(buildActionCard("SoundCloud is not configured", outcome.message, "Open Settings") { showTab(4) })
            }
            is SourceSearchResult.Error -> {
                results.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.addView(buildActionCard("Search failed", outcome.message, "Retry") { runSearch(query) })
            }
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
                if (result.playable) {
                    playSearchResult(result)
                } else {
                    Toast.makeText(this@MainActivity, "This track is not playable from its source.", Toast.LENGTH_SHORT).show()
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
        ArtworkLoader.loadArtwork(this, result.track, dp(52), iv)
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
            result.preview -> Color.rgb(255, 190, 92)
            result.downloaded -> Color.rgb(120, 220, 160)
            else -> lightPurple
        }
        meta.addView(label(result.sourceLabel, 11, sourceColor, false))
        top.addView(meta)
        row.addView(top)

        val capabilityText = when {
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
            imageTintList = ColorStateList.valueOf(if (favoriteTrackIds.contains(result.track.id)) purple else textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            isClickable = true
            isFocusable = true
            foreground = ripple()
            contentDescription = "Favorite"
            setOnClickListener {
                toggleFavorite(result.track)
                imageTintList = ColorStateList.valueOf(if (favoriteTrackIds.contains(result.track.id)) purple else textMuted)
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

    private fun stableTrackId(source: String, value: String): Long {
        val input = "$source:$value".toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input)
        var result = 0L
        for (i in 0..7) {
            result = (result shl 8) or (digest[i].toLong() and 0xFFL)
        }
        return if (result == 0L) 1L else result
    }

    private fun playRemoteTrack(track: Track, metadata: SourceMetadata?) {
        if (metadata == null) {
            triggerPlay(track)
            return
        }
        val source: MusicSource = if (metadata.trackId.source == soundCloudSource.sourceId) soundCloudSource else localSource
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
                svc?.playTrack(playable, listOf(playable))
            }
        }.start()
    }

    private fun playSearchResult(result: SearchResult) {
        if (!result.playable) {
            Toast.makeText(this, "This track is not playable from its source.", Toast.LENGTH_SHORT).show()
            return
        }
        if (result.source.sourceId == localSource.sourceId) {
            triggerPlay(result.track)
        } else {
            playRemoteTrack(result.track, result.metadata)
        }
    }

    private fun addSearchResultToQueue(result: SearchResult) {
        if (!result.playable) {
            Toast.makeText(this, "This track cannot be queued.", Toast.LENGTH_SHORT).show()
            return
        }
        if (result.source.sourceId == localSource.sourceId) {
            svc?.enqueue(result.track)
            Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val streamResult = try {
                result.source.stream(result.metadata)
            } catch (e: Exception) {
                com.aurora.app.source.StreamResult(uri = null, metadata = result.metadata, error = e.message ?: "Unavailable")
            }
            runOnUiThread {
                val uri = streamResult.uri
                if (uri == null) {
                    Toast.makeText(this, streamResult.error ?: "Could not queue this track.", Toast.LENGTH_SHORT).show()
                } else {
                    svc?.enqueue(result.track.copy(uri = uri))
                    Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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
                    Toast.makeText(this, capability.reason.ifBlank { "This track is not downloadable." }, Toast.LENGTH_LONG).show()
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
            append("Playable: ").append(if (result.playable) "yes" else "no").append('\n')
            append("Downloadable: ")
                .append(if (result.downloaded) "already downloaded" else if (result.downloadable) "yes" else "no")
                .append('\n')
            append("Source track id: ").append(result.metadata.trackId.value)
        }
        AlertDialog.Builder(this)
            .setTitle("Track details")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun downloadRemoteTrack(metadata: SourceMetadata) {
        Thread {
            val capability = soundCloudSource.checkDownloadAvailability(metadata)
            if (capability.availability != DownloadAvailability.AVAILABLE) {
                runOnUiThread {
                    Toast.makeText(this, capability.reason.ifBlank { "This track is not officially downloadable from SoundCloud." }, Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            downloadManager.queue(soundCloudSource, metadata)
            runOnUiThread {
                Toast.makeText(this, "Download queued: ${metadata.title}", Toast.LENGTH_SHORT).show()
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
        page.addView(label("Your local collection", 13, textSecondary, false))
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

    /** Builds a compact segmented control row (Aurora pill style). */
    private fun buildSegmentedRow(
        labels: List<String>,
        initial: Int,
        tabStore: MutableList<FrameLayout>,
        onChange: (Int) -> Unit
    ): LinearLayout {
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
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).also { if (idx > 0) it.leftMargin = dp(6) }
                setPadding(dp(6), dp(6), dp(6), dp(6))
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener { onChange(idx) }
            }
            val tv = label(labelText, 11, if (idx == initial) text else textSecondary, idx == initial).apply {
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(MP, MP)
            }
            tab.addView(tv)
            row.addView(tab)
            tabStore.add(tab)
        }
        return row
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

    /** Classifies an in-library track into the All / Local / Downloaded / Online buckets. */
    private fun trackBucket(track: Track): TrackFilter {
        return when (track.uri.scheme?.lowercase()) {
            "file" -> TrackFilter.DOWNLOADED          // files inside Aurora-managed storage (imports + downloads)
            "http", "https" -> TrackFilter.ONLINE     // network source tracks when present
            else -> TrackFilter.LOCAL                 // device MediaStore tracks
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
            val (title, message) = when (trackFilter) {
                TrackFilter.ALL -> "No tracks" to "Add music to your device or import files into Aurora."
                TrackFilter.LOCAL -> "No local tracks" to "Audio already stored on this device appears here."
                TrackFilter.DOWNLOADED -> "No downloaded tracks" to "Tracks you import or download into Aurora appear here."
                TrackFilter.ONLINE -> "No online tracks" to "SoundCloud tracks saved to your library appear here."
            }
            container.addView(buildStateCard(title, message, false))
            return
        }
        container.addView(label("${visible.size} tracks", 12, textMuted, false).apply { setPadding(0, 0, 0, dp(12)) })
        visible.forEachIndexed { i, track ->
            container.addView(buildTrackRow(track, i + 1, true))
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
    private fun showLibraryDrillDown(title: String, tracks: List<Track>) {
        val container = libraryContainer ?: return
        activeDownloadView = false
        container.removeAllViews()
        val word = if (tracks.size == 1) "track" else "tracks"
        container.addView(buildSettingRow("‹  $title", "$word • tap a track to play", "Back") { renderLibrarySection() })
        container.addView(vGap(4))
        tracks.forEachIndexed { i, track ->
            container.addView(buildTrackRow(track, i + 1, true))
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
        page.addView(vGap(6))
        page.addView(label("Playback & appearance", 13, textSecondary, false))
        page.addView(vGap(18))

        page.addView(settingGroupLabel("Playback"))
        page.addView(buildToggleRow("Gapless playback", "Seamless track transitions", true) { })
        page.addView(buildToggleRow("Crossfade", "Smooth transitions between tracks", false) { })
        page.addView(buildToggleRow("Normalize volume", "Keep listening levels steady", true) { })

        page.addView(vGap(20))
        page.addView(settingGroupLabel("Appearance"))
        page.addView(buildSettingRow("Dark theme", "AMOLED-inspired look", "ON"))
        page.addView(buildSettingRow("Accent color", "Aurora purple", "Purple"))
        page.addView(buildToggleRow("Use dynamic colors", "Match artwork tinting", false) { })

        page.addView(vGap(20))
        page.addView(settingGroupLabel("Library"))
        page.addView(buildSettingRow("Rescan storage", "Refresh local music indexing", "↻") { loadLibrary() })
        page.addView(buildSettingRow("Track filter", "Tracks under 30 seconds hidden", "30s"))

        page.addView(vGap(20))
        page.addView(settingGroupLabel("SoundCloud"))
        soundCloudSettingsGroup = buildSoundCloudSettingsGroup()
        page.addView(soundCloudSettingsGroup)

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
            group.addView(buildSettingRow("Connected to SoundCloud", "A user session is active", "Connected"))
            group.addView(vGap(10))
            group.addView(buildPrimaryButton("Disconnect") {
                soundCloudSource.clearAuthentication()
                Toast.makeText(this@MainActivity, "Disconnected from SoundCloud", Toast.LENGTH_SHORT).show()
                refreshSoundCloudSettings()
            })
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
        card.addView(label(
            when {
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

    private fun showDownloadsScreen() {
        activeDownloadView = true
        libraryContainer?.removeAllViews()
        val list = downloadManager.getAll().sortedByDescending { it.state.ordinal }
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
        val fallbackTrack = Track(0L, progress.title.ifBlank { "Aurora" }, progress.artist.ifBlank { "Local" }, "", progress.totalBytes.coerceAtLeast(0L), android.net.Uri.EMPTY, 0L)
        ArtworkLoader.loadArtwork(this, fallbackTrack, dp(52), art)
        artWrap.addView(art)
        header.addView(artWrap)

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        meta.addView(label(progress.title.ifBlank { "Audio transfer" }, 14, text, true))
        meta.addView(vGap(2))
        meta.addView(label(progress.artist.ifBlank { progress.source }, 12, textSecondary, false))
        header.addView(meta)

        if (progress.state == DownloadState.COMPLETED || progress.state == DownloadState.FAILED || progress.state == DownloadState.CANCELLED || progress.state == DownloadState.PAUSED) {
            val badge = TextView(this).apply {
                text = when (progress.state) {
                    DownloadState.COMPLETED -> "Completed"
                    DownloadState.FAILED -> "Failed"
                    DownloadState.CANCELLED -> "Cancelled"
                    DownloadState.PAUSED -> "Paused"
                    else -> progress.state.name
                }
                setTextColor(if (progress.state == DownloadState.FAILED) Color.rgb(255, 100, 100) else purple)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            header.addView(badge)
        }
        card.addView(header)

        if (progress.state in setOf(DownloadState.DOWNLOADING, DownloadState.RESOLVING, DownloadState.PROCESSING, DownloadState.VERIFYING, DownloadState.QUEUED)) {
            card.addView(vGap(10))
            val bar = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.argb(40, 124, 92, 252))
                    cornerRadius = dp(99).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(MP, dp(8))
            }
            val fillWidth = ((progress.progress.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100) * resources.displayMetrics.density).toInt()
            val fill = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(purple)
                    cornerRadius = dp(99).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(fillWidth.coerceAtLeast(0), dp(8))
            }
            val wrapper = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(MP, dp(8)) }
            wrapper.addView(bar)
            wrapper.addView(fill)
            card.addView(wrapper)
            card.addView(vGap(8))
            val bytesText = if (progress.totalBytes > 0L) {
                "${progress.downloadedBytes / 1024L} KB / ${progress.totalBytes / 1024L} KB"
            } else {
                progress.state.name
            }
            card.addView(label(bytesText, 11, textSecondary, false))
            card.addView(vGap(6))
            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val speed = label(if (progress.speedBytesPerSecond > 0f) "${progress.speedBytesPerSecond / 1024f} KB/s" else "Queued", 11, textMuted, false)
            footer.addView(speed)
            footer.addView(spacerH())
            val action = label(if (progress.state == DownloadState.DOWNLOADING) "Cancel" else "Cancel", 11, purple, true).apply {
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener { downloadManager.cancel(progress.jobId) }
            }
            footer.addView(action)
            card.addView(footer)
        } else {
            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val sub = label(
                when (progress.state) {
                    DownloadState.COMPLETED -> "Completed"
                    DownloadState.FAILED -> progress.error ?: "Transfer failed"
                    DownloadState.CANCELLED -> "Cancelled"
                    DownloadState.PAUSED -> "Paused"
                    else -> "Ready"
                },
                11,
                if (progress.state == DownloadState.FAILED) Color.rgb(255, 120, 120) else textSecondary,
                false
            )
            footer.addView(sub)
            footer.addView(spacerH())
            if (progress.state != DownloadState.COMPLETED) {
                footer.addView(label("Retry", 11, purple, true).apply {
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

    private fun retryDownload(progress: DownloadProgress) {
        val source: MusicSource = if (progress.source == soundCloudSource.sourceId) soundCloudSource else localSource
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
        sourceMetadata: SourceMetadata? = null,
        canDownload: Boolean = false
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
                if (source == "SoundCloud" && sourceMetadata != null) {
                    playRemoteTrack(track, sourceMetadata)
                } else if (track.uri.scheme == "http" || track.uri.scheme == "https") {
                    svc?.playTrack(track, listOf(track))
                } else {
                    triggerPlay(track)
                }
            }
        }
        val art = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(if (compact) dp(46) else dp(54), if (compact) dp(46) else dp(54)).also { it.rightMargin = dp(12) }
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
        ArtworkLoader.loadArtwork(this, track, if (compact) dp(46) else dp(54), iv)
        art.addView(iv)
        row.addView(art)

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        meta.addView(label(track.title, 14, text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        meta.addView(vGap(2))
        meta.addView(label("${track.artist} • ${track.album}", 12, textSecondary, false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (source == "SoundCloud") {
            meta.addView(vGap(2))
            meta.addView(label("Remote • $source", 11, lightPurple, false))
        }
        row.addView(meta)
        row.addView(label(track.durationLabel, 12, textMuted, false).apply {
            setPadding(dp(8), 0, dp(10), 0)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (source == "SoundCloud" && sourceMetadata != null) {
            val actionText = if (canDownload) "Download" else "Unavailable"
            val actionBtn = TextView(this).apply {
                text = actionText
                setTextColor(if (canDownload) purple else textMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rounded(surface, 10)
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener {
                    if (canDownload) downloadRemoteTrack(sourceMetadata)
                }
                contentDescription = "Download remote track"
            }
            actions.addView(actionBtn)
        }

        val heart = ImageView(this).apply {
            setImageResource(R.drawable.ic_heart)
            imageTintList = ColorStateList.valueOf(if (favoriteTrackIds.contains(track.id)) purple else textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.leftMargin = if (source == "SoundCloud") dp(8) else dp(10) }
            isClickable = true
            isFocusable = true
            foreground = ripple()
            setOnClickListener { toggleFavorite(track) }
            contentDescription = "Favorite track"
        }
        actions.addView(heart)
        row.addView(actions)
        return row
    }

    private fun toggleFavorite(track: Track) {
        val wasFav = favoriteTrackIds.contains(track.id)
        if (wasFav) favoriteTrackIds.remove(track.id) else favoriteTrackIds.add(track.id)
        persistState()
        fpHeartBtn?.imageTintList = ColorStateList.valueOf(if (favoriteTrackIds.contains(track.id)) purple else textMuted)
        if (svc?.currentTrack?.id == track.id) {
            heroFavBtn?.imageTintList = ColorStateList.valueOf(if (favoriteTrackIds.contains(track.id)) purple else textMuted)
        }
        if (selectedTab == 0) populateAllViews(allTracks)
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
            setOnClickListener { svc?.togglePlayPause() }
            contentDescription = "Play or pause"
        }
        miniPlayBtn = play
        val next = ImageView(this).apply {
            setImageResource(R.drawable.ic_skip_next)
            imageTintList = ColorStateList.valueOf(textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.leftMargin = dp(8) }
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
            val totalLabel = if (favoriteTrackIds.contains(cur.id)) "Remove from favorites" else "Add to favorites"
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
        fpTitle = label("Aurora Track", 30, text, true)
        fpTitle?.maxLines = 2
        fpTitle?.ellipsize = TextUtils.TruncateAt.END
        fpArtist = label("Aurora Artist", 14, textSecondary, false)
        fpAlbum = label("Your Library", 11, textMuted, false)
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
            setOnClickListener { svc?.previous() }
        }
        val play = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            background = circle(purple).apply { setStroke(dp(2), Color.argb(90, 255, 255, 255)) }
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
            setOnClickListener { svc?.next() }
        }
        val repeat = ImageView(this).apply {
            setImageResource(R.drawable.ic_repeat)
            imageTintList = ColorStateList.valueOf(if (isRepeat) purple else textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            setOnClickListener {
                isRepeat = !isRepeat
                svc?.setRepeatEnabled(isRepeat)
                persistState()
                imageTintList = ColorStateList.valueOf(if (isRepeat) purple else textMuted)
            }
        }
        fpRepeatBtn = repeat
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
        pills.addView(buildActionPill(R.drawable.ic_nav_library, "Device", false) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Audio output")
                .setMessage("Device selection is not available in the current build.")
                .setPositiveButton("Close", null)
                .show()
        })
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

        val queue = svc?.queue ?: emptyList()
        val currentIndex = svc?.queueIndex ?: -1
        val upcoming = if (queue.isEmpty() || currentIndex < 0 || currentIndex >= queue.lastIndex) {
            emptyList()
        } else {
            queue.drop(currentIndex + 1).take(5)
        }

        if (upcoming.isEmpty()) {
            container.addView(label("Queue is empty", 12, textSecondary, false).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
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
        col.addView(vGap(10))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        queueContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(queueContainer)
        col.addView(scroll, LinearLayout.LayoutParams(MP, 0, 1f))
        overlay.addView(col)
        return overlay
    }

    private fun updateQueueUI() {
        val container = queueContainer ?: return
        container.removeAllViews()
        val queue = svc?.queue ?: emptyList()
        if (queue.isEmpty()) {
            container.addView(buildStateCard("Queue is empty", "Play a track to build your current listening queue.", false))
            return
        }
        queue.forEachIndexed { idx, track ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (idx == (svc?.queueIndex ?: -1)) rounded(purpleSoft, 14) else null
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.bottomMargin = dp(8) }
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener { triggerPlay(track) }
            }
            val art = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).also { it.rightMargin = dp(12) }
                background = rounded(surface, 10)
                clipToOutline = true
                outlineProvider = roundRectOutline(10)
            }
            val iv = ImageView(this).apply { layoutParams = FrameLayout.LayoutParams(MP, MP); scaleType = ImageView.ScaleType.CENTER_CROP }
            ArtworkLoader.loadArtwork(this, track, dp(46), iv)
            art.addView(iv)
            row.addView(art)
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WC, 1f) }
            textCol.addView(label(track.title, 14, text, true))
            textCol.addView(vGap(2))
            textCol.addView(label(track.artist, 12, textSecondary, false))
            row.addView(textCol)
            row.addView(label("⋮", 18, textSecondary, false))
            container.addView(row)
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
            R.drawable.ic_nav_home to "Home",
            R.drawable.ic_nav_search to "Search",
            R.drawable.ic_nav_discover to "Discover",
            R.drawable.ic_nav_library to "Library",
            R.drawable.ic_nav_settings to "Settings"
        )
        items.forEachIndexed { idx, pair ->
            val (iconRes, labelText) = pair
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, MP, 1f)
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                isFocusable = true
                foreground = ripple()
                setOnClickListener { showTab(idx) }
            }
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(if (idx == selectedTab) purple else textMuted)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            val lbl = label(labelText, 10, if (idx == selectedTab) purple else textMuted, idx == selectedTab)
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
        navTabs.forEachIndexed { idx, tab ->
            tab.removeAllViews()
            val iconRes = listOf(R.drawable.ic_nav_home, R.drawable.ic_nav_search, R.drawable.ic_nav_discover, R.drawable.ic_nav_library, R.drawable.ic_nav_settings)[idx]
            val color = if (idx == selectedTab) purple else textMuted
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(color)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            val title = label(listOf("Home", "Search", "Discover", "Library", "Settings")[idx], 10, color, idx == selectedTab)
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
}
