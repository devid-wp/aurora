package com.aurora.app

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackResumeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: org.robolectric.android.controller.ServiceController<PlaybackService>? = null
    private lateinit var service: PlaybackService
    private val playStates = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        service = controller!!.get()
        service.listener = object : PlaybackService.PlaybackListener {
            override fun onTrackChanged(track: Track) = Unit
            override fun onPlayStateChanged(isPlaying: Boolean) {
                playStates.add(isPlaying)
            }
            override fun onProgressUpdate(posMs: Int, durMs: Int) = Unit
        }
    }

    @After
    fun tearDown() {
        controller?.destroy()
    }

    private fun testTrack(id: Long = 1L): Track {
        val track = Track(
            id = id,
            title = "Resume Track",
            artist = "Tester",
            album = "Album",
            duration = 180_000L,
            uri = Uri.parse("file:///resume-track-$id.mp3"),
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
    fun pause_then_play_resumes_playback() {
        val track = testTrack()
        service.playTrack(track, listOf(track))
        awaitPlaying()
        assertTrue("expected playing after playTrack", service.isPlaying)

        service.togglePlayPause()
        idleMain()
        assertFalse("expected paused after toggle", service.isPlaying)
        val pausedPos = service.positionMs

        service.togglePlayPause()
        idleMain()
        assertTrue("expected playing after second toggle (resume)", service.isPlaying)
        assertEquals("resume must continue from pause position", pausedPos, service.positionMs)
        assertTrue("listener must observe resume", playStates.last())
    }

    @Test
    fun seek_pause_play_resumes_from_seek_position() {
        val track = testTrack()
        service.playTrack(track, listOf(track))
        awaitPlaying()
        assertTrue(service.isPlaying)

        service.seekTo(60_000)
        idleMain()
        service.togglePlayPause()
        idleMain()
        assertFalse(service.isPlaying)

        service.togglePlayPause()
        idleMain()
        assertTrue("expected resume after seek+pause", service.isPlaying)
        assertEquals(60_000, service.positionMs)
    }

    @Test
    fun multiple_pause_resume_cycles_work() {
        val track = testTrack()
        service.playTrack(track, listOf(track))
        awaitPlaying()
        repeat(3) {
            service.togglePlayPause()
            idleMain()
            assertFalse("cycle $it: expected paused", service.isPlaying)
            service.togglePlayPause()
            idleMain()
            assertTrue("cycle $it: expected resumed", service.isPlaying)
        }
    }

    @Test
    fun seek_while_paused_then_play_resumes_from_seek_position() {
        val track = testTrack()
        service.playTrack(track, listOf(track))
        awaitPlaying()
        service.togglePlayPause()
        idleMain()
        assertFalse(service.isPlaying)

        service.seekTo(90_000)
        idleMain()
        assertFalse("seek while paused must stay paused", service.isPlaying)

        service.togglePlayPause()
        idleMain()
        assertTrue("expected resume after seek-while-paused", service.isPlaying)
        assertEquals(90_000, service.positionMs)
    }

    @Test
    fun resume_after_failed_start_recovers_instead_of_sticking() {        val bad = Track(
            id = 999L,
            title = "Bad",
            artist = "Tester",
            album = "Album",
            duration = 60_000L,
            uri = Uri.parse("file:///missing-file.mp3"),
            albumId = 0L
        )
        service.playTrack(bad, listOf(bad))
        idleMain()
        assertFalse("unpreparable track must not report playing", service.isPlaying)

        val good = testTrack(2L)
        service.playTrack(good, listOf(good))
        awaitPlaying()
        assertTrue("healthy track must play after failed start", service.isPlaying)

        service.togglePlayPause()
        idleMain()
        assertFalse(service.isPlaying)
        service.togglePlayPause()
        idleMain()
        assertTrue("resume must work after recovery", service.isPlaying)
    }

    @Test
    fun resume_after_denied_audio_focus_recovers_when_focus_granted() {
        val audioManager = context.getSystemService(android.media.AudioManager::class.java)
        val shadowAudio = Shadows.shadowOf(audioManager)
        shadowAudio.setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        val track = testTrack(3L)
        service.playTrack(track, listOf(track))
        idleMain()
        assertFalse("denied focus must not report playing", service.isPlaying)
        assertEquals("track is still selected", track.id, service.currentTrack?.id)

        shadowAudio.setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        service.togglePlayPause()
        awaitPlaying()
        assertTrue("resume must rebuild playback once focus is granted", service.isPlaying)
    }
}
