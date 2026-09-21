package com.aurora.app

import com.aurora.app.source.SourceCapability
import com.aurora.app.source.resolveSearchAvailability
import com.aurora.app.source.sourceDisplayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the Search screen's capability decisions: the UI must only
 * ever offer actions the source actually grants, while a verified local copy
 * stays playable even if the remote source has since blocked or removed it.
 */
class SearchAvailabilityTest {

    @Test
    fun streamableTrack_isPlayableAndStreamOnly() {
        val result = resolveSearchAvailability(
            capabilities = setOf(SourceCapability.STREAM),
            downloaded = false,
            isLocal = false,
            hasLocalUri = false
        )

        assertTrue(result.playable)
        assertFalse(result.preview)
        assertFalse(result.unavailable)
        assertFalse(result.downloadable)
    }

    @Test
    fun previewOnlyTrack_isPlayableAndMarkedPreview() {
        val result = resolveSearchAvailability(
            capabilities = setOf(SourceCapability.PREVIEW),
            downloaded = false,
            isLocal = false,
            hasLocalUri = false
        )

        assertTrue(result.playable)
        assertTrue(result.preview)
        assertFalse(result.unavailable)
    }

    @Test
    fun blockedTrackWithoutLocalCopy_isUnavailable() {
        val result = resolveSearchAvailability(
            capabilities = setOf(SourceCapability.BLOCKED),
            downloaded = false,
            isLocal = false,
            hasLocalUri = false
        )

        assertFalse(result.playable)
        assertTrue(result.unavailable)
    }

    @Test
    fun blockedTrackWithLocalCopy_staysPlayable() {
        val result = resolveSearchAvailability(
            capabilities = setOf(SourceCapability.BLOCKED),
            downloaded = true,
            isLocal = false,
            hasLocalUri = false
        )

        assertTrue("a verified local copy must remain playable", result.playable)
        assertFalse(result.unavailable)
        assertFalse("already local tracks are never offered for download", result.downloadable)
    }

    @Test
    fun localDeviceTrackWithUri_isPlayableAndDownloadable() {
        val result = resolveSearchAvailability(
            capabilities = setOf(SourceCapability.LOCAL, SourceCapability.STREAM),
            downloaded = false,
            isLocal = true,
            hasLocalUri = true
        )

        assertTrue(result.playable)
        assertTrue(result.downloadable)
        assertFalse(result.unavailable)
    }

    @Test
    fun localTrackWithoutUri_isUnavailable() {
        val result = resolveSearchAvailability(
            capabilities = setOf(SourceCapability.LOCAL),
            downloaded = false,
            isLocal = true,
            hasLocalUri = false
        )

        assertFalse(result.playable)
        assertTrue(result.unavailable)
    }

    @Test
    fun remoteTrackWithExplicitDownload_isDownloadable() {
        val result = resolveSearchAvailability(
            capabilities = setOf(SourceCapability.STREAM, SourceCapability.DOWNLOAD),
            downloaded = false,
            isLocal = false,
            hasLocalUri = false
        )

        assertTrue(result.playable)
        assertTrue(result.downloadable)
    }

    @Test
    fun externalPlaybackItem_isNotLocallyPlayableButStillActionable() {
        val result = resolveSearchAvailability(
            capabilities = setOf(SourceCapability.EXTERNAL_PLAYBACK),
            downloaded = false,
            isLocal = false,
            hasLocalUri = false
        )

        assertFalse("Aurora must not claim it can play Spotify audio", result.playable)
        assertFalse(result.unavailable)
        assertTrue(result.external)
        assertFalse(result.downloadable)
    }

    @Test
    fun downloadedLocalCopy_winsOverExternalPlayback() {
        val result = resolveSearchAvailability(
            capabilities = setOf(SourceCapability.EXTERNAL_PLAYBACK),
            downloaded = true,
            isLocal = false,
            hasLocalUri = false
        )

        assertTrue(result.playable)
        assertFalse(result.external)
    }

    @Test
    fun sourceDisplayNames_areNotHardcodedToSoundCloud() {
        assertEquals("SoundCloud", sourceDisplayName("soundcloud"))
        assertEquals("Audius", sourceDisplayName("audius"))
        assertEquals("Spotify", sourceDisplayName("spotify"))
        assertEquals("On device", sourceDisplayName("aurora_local"))
        // Unknown/future providers must never be mislabelled as SoundCloud.
        assertEquals("Bandcamp", sourceDisplayName("bandcamp"))
    }
}
