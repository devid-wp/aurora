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
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Foreground Service that owns the MediaPlayer lifecycle.
 * Fully integrates with Android's MediaSession, MediaMetadata, and PlaybackState
 * to power native lock-screen media controls, artwork, status bar notifications,
 * Bluetooth headsets, and Android auto media interfaces.
 */
class PlaybackService : Service() {

    // ── Public contract ───────────────────────────────────────────────────

    companion object {
        const val ACTION_TOGGLE = "com.aurora.app.TOGGLE"
        const val ACTION_NEXT   = "com.aurora.app.NEXT"
        const val ACTION_PREV   = "com.aurora.app.PREV"
        const val ACTION_STOP   = "com.aurora.app.STOP"

        private const val CHANNEL_ID = "aurora_pb"
        private const val NOTIF_ID   = 7001
    }

    /** Delivered on the main thread; implement in the bound Activity. */
    interface PlaybackListener {
        fun onTrackChanged(track: Track)
        fun onPlayStateChanged(isPlaying: Boolean)
        fun onProgressUpdate(posMs: Int, durMs: Int)
    }

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    /** Set / clear from the Activity's onServiceConnected / onStop. */
    var listener: PlaybackListener? = null

    // Read-only state exposed to the Activity
    var queue: List<Track> = emptyList(); private set
    var queueIndex: Int = -1;            private set
    var shuffleEnabled: Boolean = false
        private set
    var repeatEnabled: Boolean = false
        private set

    val currentTrack: Track?
        get() = if (queueIndex in queue.indices) queue[queueIndex] else null

    val isPlaying: Boolean
        get() = try { player?.isPlaying == true } catch (_: IllegalStateException) { false }

    val positionMs: Int
        get() = try { player?.currentPosition ?: 0 } catch (_: IllegalStateException) { 0 }

    val durationMs: Int
        get() = try { player?.duration ?: 0 } catch (_: IllegalStateException) { 0 }

    // ── Internal state ────────────────────────────────────────────────────

    private val binder        = LocalBinder()
    private var player        : MediaPlayer? = null
    private var playerPrepared = false
    private var preparing = false
    private var shouldPlay = false
    private var session       : MediaSession? = null
    private var currentArtwork: Bitmap? = null
    private val mainHandler   = Handler(Looper.getMainLooper())
    private val bgExecutor    = Executors.newSingleThreadExecutor()
    private val audioManager  : AudioManager by lazy { getSystemService(AudioManager::class.java) }
    private var focusRequest  : AudioFocusRequest? = null
    private var hasAudioFocus = false

    // Posts progress ticks to the main thread while playing.
    private val progressTick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            if (mp.isPlaying) {
                try {
                    val pos = mp.currentPosition
                    val dur = mp.duration
                    listener?.onProgressUpdate(pos, dur)
                } catch (_: IllegalStateException) {}
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    // Handles notification action broadcasts.
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

    // ── Life-cycle ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
        setupMediaSession()

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

    override fun onDestroy() {
        releasePlayer()
        mainHandler.removeCallbacks(progressTick)
        session?.apply {
            isActive = false
            release()
        }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── MediaSession Setup & Callbacks ────────────────────────────────────

    private fun setupMediaSession() {
        session = MediaSession(this, "AuroraMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = doResume()
                override fun onPause() = doPause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onStop() {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    private fun updateMediaSessionMetadata(track: Track, art: Bitmap) {
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, track.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, track.duration)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .putBitmap(MediaMetadata.METADATA_KEY_ART, art)
            .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
            .build()
        session?.setMetadata(meta)
    }

    private fun updatePlaybackState() {
        val actions = PlaybackState.ACTION_PLAY or
                      PlaybackState.ACTION_PAUSE or
                      PlaybackState.ACTION_PLAY_PAUSE or
                      PlaybackState.ACTION_SKIP_TO_NEXT or
                      PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                      PlaybackState.ACTION_SEEK_TO or
                      PlaybackState.ACTION_STOP

        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val speed = if (isPlaying) 1.0f else 0.0f
        val currentPos = positionMs.toLong()

        val pbState = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, currentPos, speed)
            .build()

        session?.setPlaybackState(pbState)
    }

    // ── Public Playback API ───────────────────────────────────────────────

    /** Start playing [track] within the given [allTracks] queue. */
    fun playTrack(track: Track, allTracks: List<Track>) {
        queue      = allTracks
        queueIndex = allTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        startPlayback()
    }

    /**
     * Appends [track] to the current queue without interrupting playback.
     * If nothing is loaded yet, the appended track becomes the current one.
     */
    fun enqueue(track: Track) {
        val alreadyQueued = queue.any { it.id == track.id && it.uri == track.uri }
        if (!alreadyQueued) queue = queue + track
        if (queueIndex < 0 && queue.isNotEmpty()) {
            queueIndex = 0
            startPlayback()
        }
    }

    fun togglePlayPause() {
        if (isPlaying) doPause() else doResume()
    }

    fun setShuffleEnabled(enabled: Boolean) {
        shuffleEnabled = enabled
    }

    fun setRepeatEnabled(enabled: Boolean) {
        repeatEnabled = enabled
    }

    fun seekTo(ms: Int) {
        try { 
            player?.seekTo(ms)
            updatePlaybackState()
        } catch (_: IllegalStateException) {}
    }

    fun next() {
        if (queue.isEmpty()) return
        queueIndex = if (shuffleEnabled && queue.size > 1) {
            (queue.indices - queueIndex).random()
        } else {
            (queueIndex + 1) % queue.size
        }
        startPlayback()
    }

    fun previous() {
        if (queue.isEmpty()) return
        // Restart track if more than 3 s in; otherwise go to previous one.
        if (positionMs > 3_000) seekTo(0)
        else {
            queueIndex = (queueIndex - 1 + queue.size) % queue.size
            startPlayback()
        }
    }

    // ── Internal playback ─────────────────────────────────────────────────

    private fun startPlayback() {
        val track = currentTrack ?: return
        shouldPlay = true
        playerPrepared = false
        listener?.onTrackChanged(track)

        // Load artwork asynchronously and update MediaSession + Notification
        bgExecutor.execute {
            val art = ArtworkLoader.getArtworkBitmap(applicationContext, track, 512)
            currentArtwork = art
            mainHandler.post {
                updateMediaSessionMetadata(track, art)
                updatePlaybackState()
                pushForeground()
            }
        }

        mainHandler.removeCallbacks(progressTick)
        releasePlayer()

        if (!requestFocus()) return

        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            try {
                setDataSource(applicationContext, track.uri)
            } catch (e: Exception) {
                listener?.onPlayStateChanged(false)
                updatePlaybackState()
                return@apply
            }
            setOnPreparedListener { mp ->
                playerPrepared = true
                preparing = false
                try {
                    if (shouldPlay) {
                        mp.start()
                        mainHandler.post(progressTick)
                        listener?.onPlayStateChanged(true)
                    } else {
                        listener?.onPlayStateChanged(false)
                    }
                } catch (e: IllegalStateException) {
                    listener?.onPlayStateChanged(false)
                }
                updatePlaybackState()
                pushForeground()
            }
            setOnCompletionListener {
                mainHandler.removeCallbacks(progressTick)
                updatePlaybackState()
                if (repeatEnabled) {
                    startPlayback()
                } else {
                    next()
                }
            }
            setOnErrorListener { _, _, _ ->
                preparing = false
                listener?.onPlayStateChanged(false)
                updatePlaybackState()
                refreshNotification()
                true
            }
            prepareAsync()
            preparing = true
        }
        pushForeground()
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
            // Never rebuilds, so the track is not restarted from 0.
            try {
                mp.start()
                mainHandler.post(progressTick)
                listener?.onPlayStateChanged(true)
                updatePlaybackState()
                pushForeground()
            } catch (_: IllegalStateException) {
                // Existing player is unusable (e.g. error state): rebuild it.
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
        player?.let { mp ->
            try { mp.stop() } catch (_: Exception) {}
            mp.release()
        }
        player = null
        playerPrepared = false
        preparing = false
    }

    // ── Audio focus ───────────────────────────────────────────────────────

    private fun requestFocus(): Boolean {
        if (hasAudioFocus) {
            return true
        }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasAudioFocus = false
                        doPause()
                    }
                    AudioManager.AUDIOFOCUS_GAIN           -> doResume()
                }
            }
            .build()
            .also { focusRequest = it }
        val granted = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
        return granted
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

        val playIcon = if (isPlaying) android.R.drawable.ic_media_pause
                       else           android.R.drawable.ic_media_play

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aurora_logo)
            .setContentTitle(track?.title  ?: "Aurora")
            .setContentText(track?.artist ?: "Nothing playing")
            .setSubText(track?.album ?: "Aurora Music")
            .setContentIntent(openApp)
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

        currentArtwork?.let {
            builder.setLargeIcon(it)
        }

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
