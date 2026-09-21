package com.aurora.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.aurora.app.audio.AndroidEqualizerEngine
import com.aurora.app.audio.EqualizerConfig
import com.aurora.app.audio.EqualizerController
import com.aurora.app.audio.EqualizerEngine
import com.aurora.app.audio.EqualizerStore
import java.util.concurrent.Executors

/**
 * Foreground Service that owns the MediaPlayer lifecycle and is the single
 * source of truth for track, progress, queue, shuffle and repeat.
 *
 * Queue navigation is delegated to the pure [PlaybackQueue] so next/previous/
 * repeat behavior is deterministic and unit-tested. Online tracks may be queued
 * with an unresolved URI; [streamResolver] is then used to obtain a fresh,
 * short-lived stream URL at the moment the track starts (Audius/SoundCloud
 * stream URLs expire and must never be persisted).
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.aurora.app.TOGGLE"
        const val ACTION_NEXT   = "com.aurora.app.NEXT"
        const val ACTION_PREV   = "com.aurora.app.PREV"
        const val ACTION_STOP   = "com.aurora.app.STOP"

        private const val CHANNEL_ID = "aurora_pb"
        private const val NOTIF_ID   = 7001

        /** PlaybackState extras keys mirroring shuffle/repeat for clients. */
        const val EXTRA_SHUFFLE_ENABLED = "com.aurora.app.extra.SHUFFLE"
        const val EXTRA_REPEAT_MODE     = "com.aurora.app.extra.REPEAT_MODE"

        /** Playback volume while another app transiently owns audio focus. */
        private const val DUCK_VOLUME = 0.2f

        /** Brand accent used to colourise the now-playing notification. */
        private val NOTIF_ACCENT = android.graphics.Color.rgb(124, 92, 252)
    }

    interface PlaybackListener {
        fun onTrackChanged(track: Track)
        fun onPlayStateChanged(isPlaying: Boolean)
        fun onProgressUpdate(posMs: Int, durMs: Int)

        /**
         * The queue contents, order or cursor changed (enqueue, remove, jump).
         * Defaulted so existing listeners keep compiling.
         */
        fun onQueueChanged() {}

        /** A track could not be started or its stream failed. Defaulted. */
        fun onPlaybackError(message: String) {}
    }

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    var listener: PlaybackListener? = null

    /**
     * Resolves a fresh playable stream URI for a track whose [Track.uri] is
     * empty/unresolved. Set by the Activity; may be null when only local
     * playback is possible.
     */
    var streamResolver: ((Track) -> Uri?)? = null

    private val playbackQueue = PlaybackQueue()

    val queue: List<Track> get() = playbackQueue.tracks
    val queueIndex: Int get() = playbackQueue.index
    val shuffleEnabled: Boolean get() = playbackQueue.shuffleEnabled
    val repeatMode: RepeatMode get() = playbackQueue.repeatMode

    val currentTrack: Track?
        get() = playbackQueue.current

    val isPlaying: Boolean
        get() = try { player?.isPlaying == true } catch (_: IllegalStateException) { false }

    val positionMs: Int
        get() = try { player?.currentPosition ?: 0 } catch (_: IllegalStateException) { 0 }

    val durationMs: Int
        get() = try { player?.duration ?: 0 } catch (_: IllegalStateException) { 0 }

    private val binder         = LocalBinder()
    private var player         : MediaPlayer? = null
    private var playerPrepared = false
    private var preparing      = false
    private var shouldPlay     = false
    private var session        : MediaSession? = null
    private var currentArtwork : Bitmap? = null
    private val mainHandler    = Handler(Looper.getMainLooper())
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private val audioManager   : AudioManager by lazy { getSystemService(AudioManager::class.java) }
    private var focusRequest   : AudioFocusRequest? = null
    private var hasAudioFocus  = false

    // ── Equalizer ─────────────────────────────────────────────────────────
    // The effect is attached to Aurora's own player session id, never the
    // global mix, so external playback (Spotify) is unaffected.

    /** Factory for the Android effect; injected by tests with a fake engine. */
    var equalizerEngineFactory: (sessionId: Int) -> EqualizerEngine = { AndroidEqualizerEngine(it) }

    /**
     * Fallback audio session id used only when the MediaPlayer reports none
     * (unit tests). Production always prefers the player's own session id and
     * never assigns one, so playback can never depend on the equalizer.
     */
    var audioSessionIdProvider: () -> Int = { 0 }

    private val equalizerController = EqualizerController { sessionId -> equalizerEngineFactory(sessionId) }
    private val equalizerStore: EqualizerStore by lazy {
        EqualizerStore(getSharedPreferences("aurora_preferences", Context.MODE_PRIVATE))
    }
    private var equalizerConfig: EqualizerConfig = EqualizerConfig.default()

    /** Volume while another app transiently owns focus but lets us duck. */
    private var ducked         = false

    /**
     * Headset/Bluetooth unplug while playing: Android asks us to stop making
     * noise. Registered for the lifetime of the service.
     */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isPlaying) {
                doPause()
            }
        }
    }
    private var noisyReceiverRegistered = false

    /**
     * Monotonic token incremented for every playback start. Async callbacks
     * (stream resolution, artwork, onPrepared) compare against it so a stale
     * callback can never hijack a newer track.
     */
    private var prepareToken = 0

    private val progressTick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            if (mp.isPlaying) {
                try {
                    listener?.onProgressUpdate(mp.currentPosition, mp.duration)
                } catch (_: IllegalStateException) {}
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TOGGLE -> togglePlayPause()
                ACTION_NEXT   -> next()
                ACTION_PREV   -> previous()
                ACTION_STOP   -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        setupMediaSession()
        equalizerConfig = try { equalizerStore.load() } catch (_: Exception) { EqualizerConfig.default() }

        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(controlReceiver, filter)
        }

        // Headset/Bluetooth disconnect must never leave audio blasting from the
        // phone speaker. Exported system broadcast, so no NOT_EXPORTED flag.
        try {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyReceiverRegistered = true
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT   -> next()
            ACTION_PREV   -> previous()
            ACTION_STOP   -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_STICKY
    }

    /**
     * Swiping Aurora away from Recents: keep playing when audio is active, but
     * do not leave an orphaned foreground service and notification behind when
     * nothing is playing.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaying) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        prepareToken++
        releasePlayer()
        mainHandler.removeCallbacks(progressTick)
        session?.apply {
            isActive = false
            release()
        }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        if (noisyReceiverRegistered) {
            try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
            noisyReceiverRegistered = false
        }
        resolveExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun setupMediaSession() {
        session = MediaSession(this, "AuroraMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = doResume()
                override fun onPause() = doPause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onSkipToQueueItem(id: Long) = playAt(id.toInt())
                override fun onStop() {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    /**
     * Publishes track metadata to the platform. The artwork *URI* is set
     * immediately (so the lock screen / notification can start fetching it),
     * and the decoded bitmap is added once available.
     */
    private fun updateMediaSessionMetadata(track: Track) {
        val position = playbackQueue.index
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.id.toString())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, track.duration)
        // "3 of 12" on the lock screen instead of an anonymous track.
        if (position >= 0) {
            builder.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, (position + 1).toLong())
            builder.putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, playbackQueue.tracks.size.toLong())
        }
        track.artworkUri?.takeIf { it.toString().isNotBlank() }?.let { art ->
            builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, art.toString())
            builder.putString(MediaMetadata.METADATA_KEY_ART_URI, art.toString())
        }
        currentArtwork?.let { art ->
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
            builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
        }
        session?.setMetadata(builder.build())
    }

    /**
     * Publishes the queue to the platform so lock-screen, notification and
     * Android Auto surfaces can list it and skip to an entry. Called only on
     * real queue/cursor changes, never on progress ticks.
     */
    private fun publishMediaQueue() {
        val s = session ?: return
        val tracks = playbackQueue.tracks
        if (tracks.isEmpty()) {
            s.setQueue(emptyList())
            return
        }
        val items = tracks.mapIndexed { i, track ->
            val description = MediaDescription.Builder()
                .setMediaId(i.toString())
                .setTitle(track.title)
                .setSubtitle(track.artist)
                .apply {
                    track.artworkUri?.takeIf { it.toString().isNotBlank() }?.let { setIconUri(it) }
                }
                .build()
            MediaSession.QueueItem(description, i.toLong())
        }
        s.setQueue(items)
    }

    private fun updatePlaybackState() {
        val actions = PlaybackState.ACTION_PLAY or
                      PlaybackState.ACTION_PAUSE or
                      PlaybackState.ACTION_PLAY_PAUSE or
                      PlaybackState.ACTION_SKIP_TO_NEXT or
                      PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                      PlaybackState.ACTION_SEEK_TO or
                      PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or
                      PlaybackState.ACTION_STOP

        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val speed = if (isPlaying) 1.0f else 0.0f

        // The framework PlaybackState has no shuffle/repeat fields (those are
        // AndroidX-Media only), so the real state travels in extras for any
        // client that wants to mirror it.
        val extras = android.os.Bundle().apply {
            putBoolean(EXTRA_SHUFFLE_ENABLED, playbackQueue.shuffleEnabled)
            putString(EXTRA_REPEAT_MODE, playbackQueue.repeatMode.name)
        }

        val pbState = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, positionMs.toLong(), speed)
            .setActiveQueueItemId(playbackQueue.index.toLong())
            .setExtras(extras)
            .build()

        session?.setPlaybackState(pbState)
    }

    // ── Public playback API ───────────────────────────────────────────────

    /** Starts [track] inside the [allTracks] queue (anchored on [track]). */
    fun playTrack(track: Track, allTracks: List<Track>) {
        val exact = allTracks.indexOfFirst { it.id == track.id && it.uri == track.uri }
        val byId = if (exact >= 0) exact else allTracks.indexOfFirst { it.id == track.id }
        if (byId >= 0) {
            playbackQueue.setQueue(allTracks, byId)
        } else {
            // The track is not part of the provided list: play it on its own
            // rather than silently anchoring on an unrelated track.
            playbackQueue.setQueue(listOf(track), 0)
        }
        startPlayback()
        notifyQueueChanged()
    }

    /** Appends [track] without interrupting playback. Returns true when queued. */
    fun enqueue(track: Track): Boolean {
        val wasEmpty = playbackQueue.isEmpty
        if (!playbackQueue.enqueue(track)) return false
        // An enqueue onto an empty queue has nothing playing yet: start it.
        if (wasEmpty) startPlayback()
        notifyQueueChanged()
        return true
    }

    /**
     * Jumps to the queue entry at [position] (a tap in the Queue screen) while
     * keeping the queue itself intact.
     */
    fun playAt(position: Int) {
        if (playbackQueue.playAt(position) == null) return
        startPlayback()
        notifyQueueChanged()
    }

    /**
     * Removes the entry at [position]. When the current track was removed,
     * playback hands over to the following entry; when the queue becomes empty,
     * playback stops cleanly.
     */
    fun removeFromQueue(position: Int) {
        val before = currentTrack
        if (playbackQueue.removeAt(position) == null) return
        val after = currentTrack
        notifyQueueChanged()
        if (after == null) {
            stopPlayback()
        } else if (before?.id != after.id || before?.uri != after.uri) {
            startPlayback()
        }
    }

    /** The next tracks that will actually play (shuffle/repeat aware). */
    fun upcoming(limit: Int = 5): List<Track> = playbackQueue.upcoming(limit)

    fun togglePlayPause() {
        if (isPlaying) doPause() else doResume()
    }

    fun setShuffleEnabled(enabled: Boolean) {
        playbackQueue.setShuffleEnabled(enabled)
        updatePlaybackState()
        notifyQueueChanged()
    }

    fun setRepeatMode(mode: RepeatMode) {
        playbackQueue.setRepeatMode(mode)
        updatePlaybackState()
        notifyQueueChanged()
    }

    /** Backwards-compatible toggle: true maps to [RepeatMode.ALL]. */
    fun setRepeatEnabled(enabled: Boolean) {
        setRepeatMode(if (enabled) RepeatMode.ALL else RepeatMode.OFF)
    }

    fun seekTo(ms: Int) {
        try {
            player?.seekTo(ms)
            updatePlaybackState()
        } catch (_: IllegalStateException) {}
    }

    fun next() {
        if (playbackQueue.isEmpty) return
        val next = playbackQueue.next(userInitiated = true)
        if (next == null) stopAtQueueEnd() else startPlayback()
    }

    fun previous() {
        if (playbackQueue.isEmpty) return
        // Restart the current track when past the restart window, otherwise
        // step back through real playback history.
        if (positionMs > 3_000) {
            seekTo(0)
            return
        }
        playbackQueue.previous()
        startPlayback()
    }

    // ── Internal playback ─────────────────────────────────────────────────

    private fun startPlayback() {
        val track = currentTrack ?: return
        shouldPlay = true
        playerPrepared = false
        val token = ++prepareToken

        listener?.onTrackChanged(track)
        mainHandler.removeCallbacks(progressTick)
        releasePlayer()

        // Never publish the previous track's cover under the new track's
        // metadata while the fresh artwork is still loading.
        currentArtwork = null
        updateMediaSessionMetadata(track)
        publishMediaQueue()
        loadArtwork(track, token)

        if (!requestFocus()) return

        val uri = track.uri
        if (uri != Uri.EMPTY && uri.toString().isNotBlank()) {
            beginPlayer(track, uri, token, retryAllowed = isRemote(uri))
        } else {
            resolveAndPlay(track, token)
        }
        pushForeground()
    }

    /**
     * Resolves a fresh stream URL off the main thread, then starts playback.
     * Online stream URLs are signed and expiring, so they are always resolved
     * at the moment a track starts and never persisted.
     */
    private fun resolveAndPlay(track: Track, token: Int) {
        val resolver = streamResolver
        if (resolver == null) {
            reportFailure(track, "This track is not available offline.")
            return
        }
        resolveExecutor.execute {
            val resolved = try { resolver(track) } catch (_: Exception) { null }
            mainHandler.post {
                if (token != prepareToken) return@post
                if (resolved == null) {
                    reportFailure(track, "Could not stream “${track.title}”.")
                } else {
                    beginPlayer(track, resolved, token, retryAllowed = false)
                }
            }
        }
    }

    private fun isRemote(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** One consistent failure path: honest UI state plus a user-visible reason. */
    private fun reportFailure(track: Track, message: String) {
        preparing = false
        playerPrepared = false
        listener?.onPlayStateChanged(false)
        listener?.onPlaybackError(message)
        updatePlaybackState()
        refreshNotification()
    }

    private fun beginPlayer(track: Track, uri: Uri, token: Int, retryAllowed: Boolean) {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        } catch (_: Exception) {}

        try {
            val scheme = uri.scheme?.lowercase()
            if (scheme == "content" || scheme == "android.resource") {
                mp.setDataSource(applicationContext, uri)
            } else {
                mp.setDataSource(uri.toString())
            }
        } catch (_: Exception) {
            if (player === mp) releasePlayer()
            reportFailure(track, "Could not open “${track.title}”.")
            return
        }

        // Optional equalizer: attached to the player's OWN audio session and
        // fully isolated. It never mutates the MediaPlayer and can never throw
        // into playback; if anything is unavailable, audio continues untouched.
        attachEqualizerIfPossible(mp)

        mp.setOnPreparedListener { prepared ->
            if (token != prepareToken) return@setOnPreparedListener
            playerPrepared = true
            preparing = false
            try {
                if (shouldPlay) {
                    prepared.start()
                    mainHandler.post(progressTick)
                    listener?.onPlayStateChanged(true)
                } else {
                    listener?.onPlayStateChanged(false)
                }
            } catch (_: IllegalStateException) {
                listener?.onPlayStateChanged(false)
            }
            // The session is live now: (re)attach/re-assert the equalizer. Fully
            // isolated — any failure leaves plain MediaPlayer audio untouched.
            attachEqualizerIfPossible(prepared)
            updatePlaybackState()
            pushForeground()
        }
        mp.setOnCompletionListener {
            if (token != prepareToken) return@setOnCompletionListener
            mainHandler.removeCallbacks(progressTick)
            handleCompletion()
        }
        mp.setOnErrorListener { _, what, extra ->
            if (token != prepareToken) return@setOnErrorListener true
            preparing = false
            playerPrepared = false
            if (retryAllowed && streamResolver != null && currentTrack?.id == track.id) {
                // Signed stream URLs expire: resolve a fresh one exactly once
                // instead of leaving the player stuck on a dead URL.
                releasePlayer()
                resolveAndPlay(track, token)
                return@setOnErrorListener true
            }
            reportFailure(track, "Playback failed for “${track.title}” ($what/$extra).")
            true
        }

        try {
            mp.prepareAsync()
            preparing = true
        } catch (_: Exception) {
            if (player === mp) releasePlayer()
            reportFailure(track, "Could not start “${track.title}”.")
        }
    }

    private fun handleCompletion() {
        val nextTrack = playbackQueue.onCompleted()
        if (nextTrack == null) {
            stopAtQueueEnd()
        } else {
            // startPlayback re-reads currentTrack; the queue index is already
            // advanced (or unchanged for repeat ONE).
            startPlayback()
        }
    }

    /** End of queue with repeat off: stop cleanly instead of looping. */
    private fun stopAtQueueEnd() {
        shouldPlay = false
        try {
            player?.let {
                it.seekTo(0)
                if (it.isPlaying) it.pause()
            }
        } catch (_: IllegalStateException) {}
        mainHandler.removeCallbacks(progressTick)
        listener?.onPlayStateChanged(false)
        listener?.onProgressUpdate(0, durationMs)
        updatePlaybackState()
        refreshNotification()
    }

    /** Queue became empty: release the player and drop the foreground state. */
    private fun stopPlayback() {
        shouldPlay = false
        prepareToken++
        mainHandler.removeCallbacks(progressTick)
        releasePlayer()
        currentArtwork = null
        listener?.onPlayStateChanged(false)
        listener?.onProgressUpdate(0, 0)
        updatePlaybackState()
        publishMediaQueue()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Tells the bound UI that queue contents/order/cursor changed. */
    private fun notifyQueueChanged() {
        publishMediaQueue()
        updatePlaybackState()
        listener?.onQueueChanged()
    }

    private fun doPause() {
        shouldPlay = false
        try { player?.pause() } catch (_: IllegalStateException) {}
        mainHandler.removeCallbacks(progressTick)
        listener?.onPlayStateChanged(false)
        updatePlaybackState()
        refreshNotification()
    }

    private fun doResume() {
        if (currentTrack == null) return
        shouldPlay = true
        if (!requestFocus()) return
        val mp = player
        if (playerPrepared && mp != null) {
            // Healthy paused player: resume in place from the same position.
            try {
                mp.start()
                mainHandler.post(progressTick)
                listener?.onPlayStateChanged(true)
                updatePlaybackState()
                pushForeground()
            } catch (_: IllegalStateException) {
                startPlayback()
            }
            return
        }
        // No paused player to resume. If a prepare is already running,
        // onPrepared starts it because shouldPlay is true. Otherwise rebuild
        // the current track so Play never silently does nothing.
        if (!preparing) startPlayback()
    }

    private fun releasePlayer() {
        // Detach the equalizer from the outgoing session before the player is
        // released so no AudioEffect is leaked.
        equalizerController.release()
        val mp = player
        player = null
        playerPrepared = false
        preparing = false
        ducked = false
        if (mp != null) {
            try { mp.reset() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
    }

    /**
     * Resolves the audio session the equalizer may attach to. The MediaPlayer's
     * own session is authoritative and is never mutated; [audioSessionIdProvider]
     * is a test-only override and is `0` in production, so the player's real
     * session is always used there. Returns 0 when no usable session exists.
     */
    private fun resolveEqualizerSessionId(mp: MediaPlayer): Int {
        val override = try { audioSessionIdProvider() } catch (_: Exception) { 0 }
        if (override > 0) return override
        return try { mp.audioSessionId } catch (_: Exception) { 0 }
    }

    /**
     * Optional equalizer setup, fully isolated from the primary MediaPlayer
     * path. Never mutates the player, never blocks, and never lets an effect
     * failure propagate: any problem simply leaves the equalizer bypassed and
     * playback continues normally.
     */
    private fun attachEqualizerIfPossible(mp: MediaPlayer) {
        try {
            val sessionId = resolveEqualizerSessionId(mp)
            if (sessionId <= 0) return
            if (equalizerController.isInitialized &&
                equalizerController.isSupported &&
                equalizerController.lastSessionId == sessionId
            ) {
                // Same player session: just re-assert the config.
                equalizerController.apply(equalizerConfig)
            } else {
                // First valid session, or the player changed its session (some
                // devices report a different id once prepared/started): attach
                // to the session the player actually uses now.
                equalizerController.attachToSession(sessionId, equalizerConfig)
            }
        } catch (_: Exception) {
            // A broken/unsupported equalizer must never affect playback.
            try { equalizerController.release() } catch (_: Exception) {}
        }
    }

    // ── Equalizer public API (used by the bound Activity) ─────────────────

    /** True when an attach has been attempted for the current player session. */
    val equalizerInitialized: Boolean get() = equalizerController.isInitialized

    /** True when the device exposes a usable equalizer for Aurora's session. */
    val equalizerSupported: Boolean get() = equalizerController.isSupported

    /** Compact internal effect diagnostic for logs/tests (never noisy UI). */
    val equalizerDiagnostic: String get() = equalizerController.diagnostic()

    /** Full deterministic attachment/apply snapshot for logs/tests. */
    val equalizerDiagnosticReport: String get() = equalizerController.diagnosticReport()

    /** The active persisted configuration. */
    fun currentEqualizerConfig(): EqualizerConfig = equalizerConfig

    /**
     * Persists and applies [config] to the live effect (when present). Safe to
     * call before playback: the value is stored and applied on the next start.
     */
    fun applyEqualizerConfig(config: EqualizerConfig) {
        equalizerConfig = config.normalized()
        try { equalizerStore.save(equalizerConfig) } catch (_: Exception) {}
        equalizerController.apply(equalizerConfig)
    }

    /**
     * Applies [config] to the live effect without persisting, for live slider
     * dragging. Persistence happens once when the gesture ends via
     * [applyEqualizerConfig], so dragging never writes to disk per frame.
     */
    fun previewEqualizerConfig(config: EqualizerConfig) {
        equalizerConfig = config.normalized()
        equalizerController.apply(equalizerConfig)
    }

    /**
     * Loads cover art off the main thread and refreshes the MediaSession and
     * notification once ready. Falls back to the MediaSession artwork URI if
     * decoding fails.
     */
    private fun loadArtwork(track: Track, token: Int) {
        ArtworkLoader.loadArtworkBitmap(applicationContext, track, 512) { art ->
            if (token != prepareToken) return@loadArtworkBitmap
            currentArtwork = art
            updateMediaSessionMetadata(track)
            refreshNotification()
        }
    }

    // ── Audio focus ───────────────────────────────────────────────────────

    private fun requestFocus(): Boolean {
        if (hasAudioFocus) return true
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // We handle transient ducking ourselves instead of being paused.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasAudioFocus = false
                        doPause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck(true)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        duck(false)
                        // Only auto-resume when we were the ones paused by a
                        // transient loss; a manual pause must stay paused.
                        if (!hasAudioFocus) doResume()
                        hasAudioFocus = true
                    }
                }
            }
            .build()
            .also { focusRequest = it }
        val granted = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
        return granted
    }

    /** Lowers (or restores) playback volume when another app needs the floor. */
    private fun duck(enable: Boolean) {
        if (ducked == enable) return
        ducked = enable
        val level = if (enable) DUCK_VOLUME else 1.0f
        try { player?.setVolume(level, level) } catch (_: IllegalStateException) {}
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun pushForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val track  = currentTrack
        val piFlag = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val openApp  = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            piFlag
        )
        val prevPi   = PendingIntent.getBroadcast(this, 10, Intent(ACTION_PREV),   piFlag)
        val togglePi = PendingIntent.getBroadcast(this, 11, Intent(ACTION_TOGGLE), piFlag)
        val nextPi   = PendingIntent.getBroadcast(this, 12, Intent(ACTION_NEXT),   piFlag)
        val stopPi   = PendingIntent.getBroadcast(this, 13, Intent(ACTION_STOP),   piFlag)

        val playIcon = if (isPlaying) android.R.drawable.ic_media_pause
                       else           android.R.drawable.ic_media_play

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aurora_logo)
            .setContentTitle(track?.title  ?: "Aurora")
            .setContentText(track?.artist ?: "Nothing playing")
            .setSubText(track?.album ?: "Aurora Music")
            .setContentIntent(openApp)
            .setDeleteIntent(stopPi)
            .setColor(NOTIF_ACCENT)
            .setColorized(true)
            .setOngoing(isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPi)
            .addAction(playIcon, if (isPlaying) "Pause" else "Play",     togglePi)
            .addAction(android.R.drawable.ic_media_next,     "Next",     nextPi)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        currentArtwork?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    private fun createChannel() {
        NotificationChannel(CHANNEL_ID, "Now Playing", NotificationManager.IMPORTANCE_LOW)
            .apply {
                description          = "Aurora playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            .also { getSystemService(NotificationManager::class.java)?.createNotificationChannel(it) }
    }
}
