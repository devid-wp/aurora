package com.aurora.app

import com.aurora.app.audio.EqualizerBands
import com.aurora.app.audio.EqualizerPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the canonical-curve → hardware-band mapping.
 *
 * The earlier log-frequency interpolation diluted an isolated band move across
 * neighbouring hardware bands (on a 5-band device a +10 dB move produced only
 * ~+1.5 dB at the nearest band), which is why the effect was barely audible.
 * The mapping must apply each canonical band's full gain to its nearest
 * hardware band while still never collapsing (e.g. onto band 0).
 */
class EqualizerBandMappingTest {

    private val curve = listOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0)
    private val canonicalFreqs = EqualizerBands.CENTER_FREQUENCIES_HZ.toList()

    @Test
    fun tenBandHardwareMapsOneToOne() {
        assertEquals(curve.map { it.toFloat() }, EqualizerBands.mapToHardwareBands(curve, canonicalFreqs))
    }

    @Test
    fun unknownFrequenciesDoNotCollapseOntoBandZero() {
        assertEquals(curve.map { it.toFloat() }, EqualizerBands.mapToHardwareBands(curve, List(10) { 0 }))
    }

    @Test
    fun singleBandMoveIsAppliedAtFullStrength() {
        // Common 5-band hardware layout.
        val hardware = listOf(60, 230, 910, 3600, 14000)
        val flat = List(10) { 0 }
        val cut = flat.toMutableList().also { it[4] = -10 }
        val boost = flat.toMutableList().also { it[4] = 10 }

        val cutMapped = EqualizerBands.mapToHardwareBands(cut, hardware)
        val boostMapped = EqualizerBands.mapToHardwareBands(boost, hardware)
        val swing = boostMapped.zip(cutMapped).maxOf { (b, c) -> b - c }

        assertEquals("a -10 dB to +10 dB move must deliver the full swing", 20f, swing, 0.001f)
    }

    @Test
    fun boostedBandIsNotDilutedBelowItsRequestedGain() {
        val hardware = listOf(60, 230, 910, 3600, 14000)
        val boost = List(10) { 0 }.toMutableList().also { it[6] = 10 } // 2 kHz

        val mapped = EqualizerBands.mapToHardwareBands(boost, hardware)

        assertEquals("the requested +10 dB must land in full", 10f, mapped.max(), 0.001f)
    }

    @Test
    fun movingEachCanonicalBandChangesSomeHardwareBand() {
        val hardware = listOf(60, 230, 910, 3600, 14000)
        for (band in curve.indices) {
            val before = EqualizerBands.mapToHardwareBands(curve, hardware)
            val moved = curve.toMutableList().also { it[band] = it[band] + 4 }
            val after = EqualizerBands.mapToHardwareBands(moved, hardware)
            assertTrue("canonical band $band must reach audio", before != after)
        }
    }

    @Test
    fun presetReachesHardwareUnchangedOnMatchingLayout() {
        val rock = EqualizerPresets.gainsFor(EqualizerPresets.ROCK)!!
        assertEquals(rock.map { it.toFloat() }, EqualizerBands.mapToHardwareBands(rock, canonicalFreqs))
    }

    @Test
    fun emptyInputsAreSafe() {
        val noGains = EqualizerBands.mapToHardwareBands(emptyList(), canonicalFreqs)
        assertEquals(canonicalFreqs.size, noGains.size)
        assertTrue(noGains.all { it == 0f })
        assertTrue(EqualizerBands.mapToHardwareBands(curve, emptyList()).isEmpty())
    }
}
