package com.aurora.app

import com.aurora.app.audio.EqualizerBands
import com.aurora.app.audio.EqualizerConfig
import com.aurora.app.audio.EqualizerPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the equalizer configuration model: defaults, persistence
 * fields, preset application, custom detection, reset and clamping.
 */
class EqualizerConfigTest {

    @Test
    fun defaultConfiguration_isDisabledFlatTenBand() {
        val config = EqualizerConfig.default()

        assertFalse(config.enabled)
        assertEquals(EqualizerPresets.FLAT, config.preset)
        assertEquals(EqualizerBands.BAND_COUNT, config.gainsDb.size)
        assertTrue(config.gainsDb.all { it == 0 })
        assertEquals(0, config.preampDb)
    }

    @Test
    fun enableAndDisableAreReflectedInConfig() {
        val enabled = EqualizerConfig.default().withEnabled(true)
        assertTrue(enabled.enabled)
        assertFalse(enabled.withEnabled(false).enabled)
    }

    @Test
    fun bandGainIsStoredAndClampedToValidRange() {
        val config = EqualizerConfig.default().withBandGain(3, 7)
        assertEquals(7, config.gainsDb[3])

        assertEquals(EqualizerBands.DEFAULT_MAX_DB, config.withBandGain(3, 99).gainsDb[3])
        assertEquals(EqualizerBands.DEFAULT_MIN_DB, config.withBandGain(3, -99).gainsDb[3])
    }

    @Test
    fun invalidBandIndexIsIgnored() {
        val config = EqualizerConfig.default().withBandGain(99, 5)
        assertEquals(EqualizerBands.BAND_COUNT, config.gainsDb.size)
        assertTrue(config.gainsDb.all { it == 0 })
    }

    @Test
    fun applyingPresetSetsItsCurve() {
        val rock = EqualizerConfig.default().withPreset(EqualizerPresets.ROCK)

        assertEquals(EqualizerPresets.ROCK, rock.preset)
        assertEquals(EqualizerPresets.gainsFor(EqualizerPresets.ROCK), rock.gainsDb)
    }

    @Test
    fun editingAnEditedPresetBecomesCustom() {
        val rock = EqualizerConfig.default().withPreset(EqualizerPresets.ROCK)
        val edited = rock.withBandGain(0, 0)

        assertEquals(EqualizerPresets.CUSTOM, edited.preset)
        assertNotEquals(rock.gainsDb, edited.gainsDb)
    }

    @Test
    fun detectRecognizesExactPresetCurvesAndFallsBackToCustom() {
        assertEquals(EqualizerPresets.ROCK, EqualizerPresets.detect(EqualizerPresets.gainsFor(EqualizerPresets.ROCK)!!))
        assertEquals(EqualizerPresets.FLAT, EqualizerPresets.detect(EqualizerBands.flatGains()))
        assertEquals(EqualizerPresets.CUSTOM, EqualizerPresets.detect(listOf(9, 9, 9, 9, 9, 9, 9, 9, 9, 9)))
    }

    @Test
    fun editingBackToAPresetCurveReDetectsThatPreset() {
        val custom = EqualizerConfig.default().withBandGain(0, 4).withBandGain(1, 4)
        val restored = custom.withPreset(EqualizerPresets.ROCK).withBandGain(0, 5).withBandGain(1, 4)

        assertEquals(EqualizerPresets.ROCK, restored.preset)
    }

    @Test
    fun resetToFlatClearsTheCurveButKeepsEnabledAndPreamp() {
        val config = EqualizerConfig.default()
            .withEnabled(true)
            .withPreset(EqualizerPresets.ROCK)
            .withPreamp(4)

        val flat = config.resetToFlat()

        assertEquals(EqualizerPresets.FLAT, flat.preset)
        assertTrue(flat.gainsDb.all { it == 0 })
        assertTrue("reset must not disable the equalizer", flat.enabled)
        assertEquals(4, flat.preampDb)
    }

    @Test
    fun preampIsClamped() {
        assertEquals(EqualizerBands.DEFAULT_MAX_PREAMP_DB, EqualizerConfig.default().withPreamp(100).preampDb)
        assertEquals(EqualizerBands.DEFAULT_MIN_PREAMP_DB, EqualizerConfig.default().withPreamp(-100).preampDb)
    }

    @Test
    fun normalizedResamplesLegacyGainCountsAndClamps() {
        val legacy = EqualizerConfig.default().copy(gainsDb = listOf(3, 3, 3, 3, 3))

        val normalized = legacy.normalized()

        assertEquals(EqualizerBands.BAND_COUNT, normalized.gainsDb.size)
        assertTrue(normalized.gainsDb.all { it == 3 })
    }

    @Test
    fun allPresetCurvesAreWithinRange() {
        EqualizerPresets.CURVES.values.forEach { curve ->
            assertEquals(EqualizerBands.BAND_COUNT, curve.size)
            curve.forEach {
                assertTrue("preset gain $it out of range", it in EqualizerBands.DEFAULT_MIN_DB..EqualizerBands.DEFAULT_MAX_DB)
            }
        }
    }

    @Test
    fun nearestCanonicalBand_mapsFrequenciesToCanonicalIndices() {
        assertEquals(0, EqualizerBands.nearestCanonicalIndex(31))
        assertEquals(5, EqualizerBands.nearestCanonicalIndex(1000))
        assertEquals(9, EqualizerBands.nearestCanonicalIndex(16000))
        // 120Hz is closest to the 125Hz canonical band.
        assertEquals(2, EqualizerBands.nearestCanonicalIndex(120))
    }
}
