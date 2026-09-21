package com.aurora.app

import com.aurora.app.audio.EqualizerGain
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Android Equalizer takes millibels, not decibels. These tests pin the exact
 * conversion (1 dB = 100 mB) and the clamping against the hardware range so a
 * unit mismatch can never silently make the effect inaudible.
 */
class EqualizerGainTest {

    @Test
    fun decibelsConvertToMillibels() {
        assertEquals(600, EqualizerGain.toMillibels(6f, -1500, 1500))
        assertEquals(-600, EqualizerGain.toMillibels(-6f, -1500, 1500))
        assertEquals(1500, EqualizerGain.toMillibels(15f, -1500, 1500))
        assertEquals(-1500, EqualizerGain.toMillibels(-15f, -1500, 1500))
        assertEquals(0, EqualizerGain.toMillibels(0f, -1500, 1500))
        assertEquals(350, EqualizerGain.toMillibels(3.5f, -1500, 1500))
    }

    @Test
    fun gainsClampToTheReportedHardwareRange() {
        // A device that only exposes ±12 dB.
        assertEquals(1200, EqualizerGain.toMillibels(15f, -1200, 1200))
        assertEquals(-1200, EqualizerGain.toMillibels(-15f, -1200, 1200))
        assertEquals(900, EqualizerGain.toMillibels(9f, -900, 900))
        assertEquals(900, EqualizerGain.toMillibels(20f, -900, 900))
    }

    @Test
    fun reversedHardwareRangeIsNormalized() {
        assertEquals(600, EqualizerGain.toMillibels(6f, 1500, -1500))
        assertEquals(-1500, EqualizerGain.toMillibels(-30f, 1500, -1500))
    }
}
