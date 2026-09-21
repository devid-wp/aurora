package com.aurora.app

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aurora.app.audio.EqualizerBandInfo
import com.aurora.app.audio.EqualizerConfig
import com.aurora.app.audio.EqualizerEngine
import com.aurora.app.audio.EqualizerPresets
import com.aurora.app.audio.EqualizerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import kotlin.math.roundToInt

/**
 * Verifies the equalizer attaches to Aurora's own player session and is applied
 * on playback, and that it is released with the player. Uses a fake engine and
 * an injected session id, so no real audio effect or device is required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EqualizerPlaybackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: org.robolectric.android.controller.ServiceController<PlaybackService>? = null
    private lateinit var service: PlaybackService

    private val createdSessionIds = mutableListOf<Int>()
    private lateinit var engine: RecordingEngine

    private class RecordingEngine : EqualizerEngine {
        override val available: Boolean = true
        override val bands: List<EqualizerBandInfo> = listOf(
            EqualizerBandInfo(0, 62, -15f, 15f),
            EqualizerBandInfo(1, 1000, -15f, 15f),
            EqualizerBandInfo(2, 16000, -15f, 15f)
        )
        override val bandCount: Int get() = bands.size
        override val enabledState: Boolean? get() = if (enabledSet) lastEnabled else null
        override val effectCreated: Boolean get() = true
        override val bandLevelRangeMb: List<Int> get() = listOf(-1500, 1500)
        var enabledSet = false
        var lastEnabled = false
        val bandGains = mutableMapOf<Int, Float>()
        var released = false

        override fun lastWrittenMillibels(band: Int): Int? =
            bandGains[band]?.let { (it * 100f).roundToInt() }

        override fun readBackMillibels(band: Int): Int? = lastWrittenMillibels(band)

        override fun setEnabled(enabled: Boolean) {
            enabledSet = true
            lastEnabled = enabled
        }

        override fun setBandGain(band: Int, gainDb: Float) {
            bandGains[band] = gainDb
        }

        override fun release() {
            released = true
        }
    }

    @Before
    fun setUp() {
        context.getSharedPreferences("aurora_preferences", Context.MODE_PRIVATE).edit().clear().apply()
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        service = controller!!.get()
        service.listener = object : PlaybackService.PlaybackListener {
            override fun onTrackChanged(track: Track) = Unit
            override fun onPlayStateChanged(isPlaying: Boolean) = Unit
            override fun onProgressUpdate(posMs: Int, durMs: Int) = Unit
        }
        service.audioSessionIdProvider = { 4242 }
        service.equalizerEngineFactory = { sessionId ->
            createdSessionIds.add(sessionId)
            RecordingEngine().also { engine = it }
        }
    }

    @After
    fun tearDown() {
        controller?.destroy()
    }

    private fun testTrack(id: Long = 1L): Track {
        val track = Track(
            id = id,
            title = "EQ Track",
            artist = "Tester",
            album = "Album",
            duration = 180_000L,
            uri = Uri.parse("file:///eq-track-$id.mp3"),
            albumId = 0L
        )
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, track.uri),
            ShadowMediaPlayer.MediaInfo(track.duration.toInt(), 0)
        )
        return track
    }

    private fun idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitPlaying(timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idleMain()
            if (service.isPlaying) return
        }
    }

    @Test
    fun equalizerAttachesToPlayerSessionAndAppliesConfigOnPlayback() {
        val gains = MutableList(10) { 0 }.also { it[5] = 4 }
        service.applyEqualizerConfig(
            EqualizerConfig(enabled = true, preset = EqualizerPresets.CUSTOM, gainsDb = gains, preampDb = 1)
        )

        val track = testTrack()
        service.playTrack(track, listOf(track))
        awaitPlaying()

        assertEquals("equalizer must attach to Aurora's own session id", listOf(4242), createdSessionIds)
        assertTrue("enabled state must be applied", engine.enabledSet)
        assertTrue("band gains must be written", engine.bandGains.isNotEmpty())
    }

    @Test
    fun disabledEqualizerIsAppliedAsDisabled() {
        service.applyEqualizerConfig(EqualizerConfig.default())

        val track = testTrack(2L)
        service.playTrack(track, listOf(track))
        awaitPlaying()

        assertTrue(engine.enabledSet)
        assertTrue("disabled equalizer must not write band gains", engine.bandGains.isEmpty())
    }

    @Test
    fun releasingThePlayerReleasesTheEffect() {
        service.applyEqualizerConfig(EqualizerConfig.default().withEnabled(true))
        val track = testTrack(3L)
        service.playTrack(track, listOf(track))
        awaitPlaying()
        assertTrue(engine.bandGains.isNotEmpty())

        service.removeFromQueue(0)
        idleMain()

        assertTrue("effect must be released with the player", engine.released)
    }

    @Test
    fun previewAppliesToTheLiveEffectWithoutPersisting() {
        val persisted = EqualizerConfig(
            enabled = true,
            preset = EqualizerPresets.CUSTOM,
            gainsDb = MutableList(10) { 0 }.also { it[5] = 2 }
        )
        service.applyEqualizerConfig(persisted)
        val track = testTrack(5L)
        service.playTrack(track, listOf(track))
        awaitPlaying()
        val before = engine.bandGains.toMap()
        assertTrue(before.isNotEmpty())

        // A live drag preview must reach the effect immediately...
        service.previewEqualizerConfig(persisted.withBandGain(5, 9))
        assertNotEquals("preview must update the live effect", before, engine.bandGains.toMap())

        // ...but must not write to storage on every frame.
        val store = EqualizerStore(context.getSharedPreferences("aurora_preferences", Context.MODE_PRIVATE))
        assertEquals("preview must not persist", persisted.normalized(), store.load())
    }

    @Test
    fun equalizerFailureNeverBreaksPlayback() {
        service.equalizerEngineFactory = { throw IllegalStateException("no equalizer on this device") }
        service.applyEqualizerConfig(EqualizerConfig.default().withEnabled(true))

        val track = testTrack(4L)
        service.playTrack(track, listOf(track))
        awaitPlaying()

        assertTrue("playback must continue when the equalizer fails", service.isPlaying)
    }

    @Test
    fun replacingThePlayerAttachesTheEffectToTheNewSessionAndReleasesTheOldOne() {
        val sessionIds = listOf(1111, 2222)
        var callIndex = 0
        val engines = mutableListOf<RecordingEngine>()
        service.audioSessionIdProvider = { sessionIds[callIndex.coerceAtMost(sessionIds.lastIndex)] }
        service.equalizerEngineFactory = { id ->
            createdSessionIds.add(id)
            RecordingEngine().also { engines.add(it) }
        }
        service.applyEqualizerConfig(EqualizerConfig.default().withEnabled(true))

        val first = testTrack(10L)
        service.playTrack(first, listOf(first))
        awaitPlaying()
        callIndex = 1
        val second = testTrack(11L)
        service.playTrack(second, listOf(second))
        awaitPlaying()

        assertEquals("each player must attach to its own session", listOf(1111, 2222), createdSessionIds)
        assertEquals(2, engines.size)
        assertTrue("old effect must be released on player replacement", engines[0].released)
        assertTrue(engines[1].bandGains.isNotEmpty())
    }

    @Test
    fun diagnosticReportsTheAttachedSessionAndEnabledState() {
        service.applyEqualizerConfig(EqualizerConfig.default().withEnabled(true))
        val track = testTrack(20L)
        service.playTrack(track, listOf(track))
        awaitPlaying()

        val diagnostic = service.equalizerDiagnostic
        assertTrue("diagnostic should report the live session", diagnostic.contains("session=4242"))

        val report = service.equalizerDiagnosticReport
        assertTrue("report should carry the session", report.contains("session=4242"))
        assertTrue("report should carry the hardware band count", report.contains("bands=3"))
        assertTrue("report should carry per-band conversions", report.contains("reqMb="))
        assertTrue("report should carry the read-back", report.contains("readMb="))
    }

    // ── Playback-regression guards: EQ must never break MediaPlayer ───────

    private class ThrowingEngine(
        private val enabledThrows: Boolean = false,
        private val bandThrows: Boolean = false,
        private val releaseThrows: Boolean = false
    ) : EqualizerEngine {
        override val available: Boolean = true
        override val bands: List<EqualizerBandInfo> = listOf(
            EqualizerBandInfo(0, 62, -15f, 15f),
            EqualizerBandInfo(1, 1000, -15f, 15f)
        )
        override val bandCount: Int get() = bands.size

        override fun setEnabled(enabled: Boolean) {
            if (enabledThrows) throw IllegalStateException("setEnabled failed")
        }

        override fun setBandGain(band: Int, gainDb: Float) {
            if (bandThrows) throw IllegalArgumentException("setBandLevel failed")
        }

        override fun release() {
            if (releaseThrows) throw IllegalStateException("release failed")
        }
    }

    @Test
    fun playbackProceedsWhenSessionResolutionFails() {
        // Session resolution throws: playback must continue regardless.
        service.audioSessionIdProvider = { throw IllegalStateException("no audio session") }
        service.applyEqualizerConfig(EqualizerConfig.default().withEnabled(true))

        val track = testTrack(40L)
        service.playTrack(track, listOf(track))
        awaitPlaying()

        assertTrue("playback must continue without a usable session", service.isPlaying)
    }

    @Test
    fun playbackProceedsWhenSetEnabledThrows() {
        service.equalizerEngineFactory = { ThrowingEngine(enabledThrows = true) }
        service.applyEqualizerConfig(EqualizerConfig.default().withEnabled(true))

        val track = testTrack(41L)
        service.playTrack(track, listOf(track))
        awaitPlaying()

        assertTrue("playback must continue when setEnabled fails", service.isPlaying)
    }

    @Test
    fun playbackProceedsWhenSetBandLevelThrows() {
        service.equalizerEngineFactory = { ThrowingEngine(bandThrows = true) }
        service.applyEqualizerConfig(EqualizerConfig.default().withEnabled(true))

        val track = testTrack(42L)
        service.playTrack(track, listOf(track))
        awaitPlaying()

        assertTrue("playback must continue when setBandLevel fails", service.isPlaying)
    }

    @Test
    fun playbackProceedsWhenEffectReleaseThrows() {
        service.equalizerEngineFactory = { ThrowingEngine(releaseThrows = true) }
        service.applyEqualizerConfig(EqualizerConfig.default().withEnabled(true))

        val first = testTrack(43L)
        service.playTrack(first, listOf(first))
        awaitPlaying()
        assertTrue(service.isPlaying)

        // Replacing the player releases the failing effect first; the next track
        // must still play.
        val second = testTrack(44L)
        service.playTrack(second, listOf(second))
        awaitPlaying()
        assertTrue("playback must continue despite a release failure", service.isPlaying)
    }

    @Test
    fun playbackProceedsWhenEffectCreationThrows() {
        service.equalizerEngineFactory = { throw RuntimeException("Equalizer unavailable") }
        service.applyEqualizerConfig(EqualizerConfig.default().withEnabled(true))

        val track = testTrack(45L)
        service.playTrack(track, listOf(track))
        awaitPlaying()

        assertTrue("playback must continue when the effect cannot be created", service.isPlaying)
    }
}
