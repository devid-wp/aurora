package com.aurora.app

import com.aurora.app.audio.EqualizerBands
import com.aurora.app.audio.EqualizerConfig
import com.aurora.app.audio.EqualizerConfigCodec
import com.aurora.app.audio.EqualizerPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for equalizer persistence encoding, including migration. */
class EqualizerConfigCodecTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val config = EqualizerConfig(
            enabled = true,
            preset = EqualizerPresets.VOCAL,
            gainsDb = EqualizerPresets.gainsFor(EqualizerPresets.VOCAL)!!,
            preampDb = 3
        )

        val decoded = EqualizerConfigCodec.decode(EqualizerConfigCodec.encode(config))

        assertEquals(config.enabled, decoded?.enabled)
        assertEquals(config.preset, decoded?.preset)
        assertEquals(config.gainsDb, decoded?.gainsDb)
        assertEquals(config.preampDb, decoded?.preampDb)
    }

    @Test
    fun flatDisabledConfigRoundTrips() {
        val decoded = EqualizerConfigCodec.decode(EqualizerConfigCodec.encode(EqualizerConfig.default()))
        assertEquals(EqualizerConfig.default(), decoded)
    }

    @Test
    fun missingOrMalformedPayloadReturnsNull() {
        assertNull(EqualizerConfigCodec.decode(null))
        assertNull(EqualizerConfigCodec.decode(""))
        assertNull(EqualizerConfigCodec.decode("garbage"))
        assertNull(EqualizerConfigCodec.decode("1;1;0;Rock"))
        assertNull(EqualizerConfigCodec.decode("1;1;0;Rock;not,numbers"))
    }

    @Test
    fun unknownFutureVersionIsRejected() {
        assertNull(EqualizerConfigCodec.decode("99;1;0;Flat;0,0,0,0,0,0,0,0,0,0"))
    }

    @Test
    fun legacyGainCountIsMigratedToTenBands() {
        // A payload saved with a 5-band layout must resample, not crash.
        val decoded = EqualizerConfigCodec.decode("1;1;2;Custom;3,3,3,3,3")

        assertEquals(EqualizerBands.BAND_COUNT, decoded?.gainsDb?.size)
        assertTrue(decoded!!.gainsDb.all { it == 3 })
        assertEquals(2, decoded.preampDb)
        assertTrue(decoded.enabled)
    }

    @Test
    fun outOfRangeStoredGainsAreClampedOnDecode() {
        val decoded = EqualizerConfigCodec.decode("1;0;0;Custom;99,-99,0,0,0,0,0,0,0,0")
        assertEquals(EqualizerBands.DEFAULT_MAX_DB, decoded?.gainsDb?.get(0))
        assertEquals(EqualizerBands.DEFAULT_MIN_DB, decoded?.gainsDb?.get(1))
    }
}
