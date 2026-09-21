package com.aurora.app

import com.aurora.app.audio.EqualizerBandInfo
import com.aurora.app.audio.EqualizerBands
import com.aurora.app.audio.EqualizerConfig
import com.aurora.app.audio.EqualizerController
import com.aurora.app.audio.EqualizerEngine
import com.aurora.app.audio.EqualizerGain
import com.aurora.app.audio.EqualizerPresets
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the controller's engine lifecycle and canonical→hardware band mapping
 * using a fake engine, so no Android audio effect is required.
 */
class EqualizerControllerTest {

    private class FakeEngine(
        override val available: Boolean = true,
        override val bands: List<EqualizerBandInfo> = defaultBands()
    ) : EqualizerEngine {
        var released = false
        var lastEnabled: Boolean? = null
        val bandGains = mutableMapOf<Int, Float>()

        override val bandCount: Int get() = bands.size
        override val enabledState: Boolean? get() = lastEnabled
        override val effectCreated: Boolean get() = true
        override val bandLevelRangeMb: List<Int> get() = listOf(-1500, 1500)

        override fun lastWrittenMillibels(band: Int): Int? =
            bandGains[band]?.let { (it * 100f).roundToInt() }

        override fun readBackMillibels(band: Int): Int? = lastWrittenMillibels(band)

        override fun setEnabled(enabled: Boolean) {
            lastEnabled = enabled
        }

        override fun setBandGain(band: Int, gainDb: Float) {
            bandGains[band] = gainDb
        }

        override fun release() {
            released = true
        }

        companion object {
            fun defaultBands(): List<EqualizerBandInfo> = listOf(
                EqualizerBandInfo(0, 62, -15f, 15f),
                EqualizerBandInfo(1, 250, -15f, 15f),
                EqualizerBandInfo(2, 1000, -15f, 15f),
                EqualizerBandInfo(3, 4000, -15f, 15f),
                EqualizerBandInfo(4, 16000, -15f, 15f)
            )
        }
    }

    private fun configWith(gainAt3: Int, enabled: Boolean = true, preamp: Int = 0): EqualizerConfig {
        val gains = MutableList(10) { 0 }.also { it[3] = gainAt3 }
        return EqualizerConfig(enabled = enabled, preset = "Custom", gainsDb = gains, preampDb = preamp)
    }

    @Test
    fun attachMapsCanonicalGainsToHardwareBands() {
        val created = mutableListOf<Int>()
        val engine = FakeEngine()
        val controller = EqualizerController { sessionId ->
            created.add(sessionId)
            engine
        }

        controller.attachToSession(1234, configWith(gainAt3 = 6, preamp = 2))

        assertEquals(listOf(1234), created)
        assertTrue(controller.isInitialized)
        assertTrue(controller.isSupported)
        assertEquals(true, engine.lastEnabled)
        // Canonical band 3 (250Hz) maps to hardware band 1, plus the +2dB preamp.
        assertEquals(8f, engine.bandGains[1])
        // Canonical band 2 (62Hz) maps to hardware band 0, preamp only.
        assertEquals(2f, engine.bandGains[0])
    }

    @Test
    fun disabledConfigDisablesEffectWithoutWritingBands() {
        val engine = FakeEngine()
        val controller = EqualizerController { engine }

        controller.attachToSession(10, configWith(gainAt3 = 6, enabled = false))

        assertEquals(false, engine.lastEnabled)
        assertTrue(engine.bandGains.isEmpty())
    }

    @Test
    fun negativePreampOffsetsEveryBand() {
        val engine = FakeEngine()
        val controller = EqualizerController { engine }

        controller.attachToSession(10, configWith(gainAt3 = 4, preamp = -3))

        assertEquals(1f, engine.bandGains[1])
        assertEquals(-3f, engine.bandGains[0])
    }

    @Test
    fun releaseReleasesTheEffectAndReattachReleasesThePrevious() {
        val engines = mutableListOf<FakeEngine>()
        val controller = EqualizerController {
            FakeEngine().also { engines.add(it) }
        }

        controller.attachToSession(1, configWith(3))
        controller.attachToSession(2, configWith(3))

        assertEquals(2, engines.size)
        assertTrue("switching sessions must release the old effect", engines[0].released)
        assertFalse(engines[1].released)

        controller.release()
        assertTrue(engines[1].released)
        assertFalse(controller.isInitialized)
    }

    @Test
    fun nonPositiveSessionIdDoesNotCreateAnEngine() {
        var factoryCalled = false
        val controller = EqualizerController {
            factoryCalled = true
            FakeEngine()
        }

        controller.attachToSession(0, configWith(3))

        assertFalse(factoryCalled)
        assertFalse(controller.isInitialized)
    }

    @Test
    fun factoryFailureIsSwallowed() {
        val controller = EqualizerController { throw IllegalStateException("no effect") }

        controller.attachToSession(7, configWith(3))

        assertFalse(controller.isInitialized)
        assertFalse(controller.isSupported)
    }

    @Test
    fun presetConfigReachesEveryMappedHardwareBand() {
        val engine = FakeEngine()
        val controller = EqualizerController { engine }
        val preset = EqualizerConfig.default().withEnabled(true).withPreset(EqualizerPresets.ROCK)
        val rock = EqualizerPresets.gainsFor(EqualizerPresets.ROCK)!!

        controller.attachToSession(99, preset)

        // The controller must apply exactly the peak-preserving hardware mapping
        // (each canonical band's full gain to its nearest hardware band).
        val expected = EqualizerBands.mapToHardwareBands(rock, engine.bands.map { it.centerFreqHz })
        expected.forEachIndexed { index, value ->
            assertEquals(value, engine.bandGains[index]!!, 0.001f)
        }
        assertTrue("the preset must actually reach the hardware", expected.any { it != 0f })
    }

    @Test
    fun controllerGainsConvertToTheExpectedMillibelLevels() {
        val engine = FakeEngine()
        val controller = EqualizerController { engine }
        val gains = MutableList(10) { 0 }.also { it[1] = 6; it[9] = -6 }

        controller.attachToSession(5, EqualizerConfig(enabled = true, preset = "Custom", gainsDb = gains))

        // Hardware band 0 (62Hz -> canonical 1): +6 dB -> +600 mB.
        assertEquals(600, EqualizerGain.toMillibels(engine.bandGains[0]!!, -1500, 1500))
        // Hardware band 4 (16kHz -> canonical 9): -6 dB -> -600 mB.
        assertEquals(-600, EqualizerGain.toMillibels(engine.bandGains[4]!!, -1500, 1500))
    }

    @Test
    fun releaseStopsFurtherWrites() {
        val engine = FakeEngine()
        val controller = EqualizerController { engine }
        controller.attachToSession(5, configWith(6))
        val before = engine.bandGains.toMap()

        controller.release()
        controller.apply(configWith(9))

        assertEquals("no band writes may happen after release", before, engine.bandGains.toMap())
        assertTrue(engine.released)
    }

    @Test
    fun unknownHardwareFrequenciesStillMapEveryCanonicalBand() {
        // Simulates a device that reports no center frequencies: the old
        // nearest-neighbour mapping collapsed everything onto canonical 0.
        val engine = FakeEngine(bands = (0 until 10).map { EqualizerBandInfo(it, 0, -15f, 15f) })
        val controller = EqualizerController { engine }
        val gains = MutableList(10) { 0 }.also { it[4] = 6; it[8] = -4 }

        controller.attachToSession(9, EqualizerConfig(enabled = true, preset = "Custom", gainsDb = gains))

        assertEquals(6f, engine.bandGains[4])
        assertEquals(-4f, engine.bandGains[8])
    }

    @Test
    fun diagnosticSnapshotCapturesSessionBandsConversionsAndReadBack() {
        val engine = FakeEngine()
        val controller = EqualizerController { engine }
        val gains = MutableList(10) { 0 }.also { it[1] = 6; it[9] = -6 }

        controller.attachToSession(4242, EqualizerConfig(enabled = true, preset = "Custom", gainsDb = gains))

        val snapshot = controller.lastSnapshot!!
        assertEquals(4242, snapshot.sessionId)
        assertTrue(snapshot.sessionValid)
        assertTrue(snapshot.effectCreated)
        assertEquals(5, snapshot.numberOfBands)
        assertEquals(-1500, snapshot.minLevelMb)
        assertEquals(1500, snapshot.maxLevelMb)
        assertEquals(true, snapshot.enabledReadBack)
        assertEquals(5, snapshot.bands.size)

        // Hardware band 0 (62Hz -> canonical 1): +6 dB -> +600 mB, read back.
        val band0 = snapshot.bands[0]
        assertEquals(1, band0.canonicalIndex)
        assertEquals(6f, band0.requestedDb)
        assertEquals(600, band0.requestedMillibels)
        assertEquals(600, band0.writtenMillibels)
        assertEquals(600, band0.readBackMillibels)
        assertEquals(6f, band0.readBackDb)

        // Report exposes the per-band conversions for logs.
        val report = controller.diagnosticReport()
        assertTrue(report.contains("reqMb=600"))
        assertTrue(report.contains("readMb=600"))
    }

    @Test
    fun diagnosticSnapshotRecordsDisabledStateWithoutBandWrites() {
        val engine = FakeEngine()
        val controller = EqualizerController { engine }

        controller.attachToSession(7, configWith(6, enabled = false))

        val snapshot = controller.lastSnapshot!!
        assertEquals(false, snapshot.enabledRequested)
        assertEquals(false, snapshot.enabledReadBack)
        assertTrue(snapshot.bands.isEmpty())
    }

    @Test
    fun lastSessionIdAndDiagnosticAreExposed() {
        val controller = EqualizerController { FakeEngine() }
        controller.attachToSession(77, configWith(1))

        assertEquals(77, controller.lastSessionId)
        assertTrue(controller.diagnostic().contains("77"))
    }

    @Test
    fun unavailableEngineIsNotSupportedAndApplyIsNoOp() {
        val engine = FakeEngine(available = false)
        val controller = EqualizerController { engine }

        controller.attachToSession(7, configWith(3))
        controller.apply(configWith(9))

        assertTrue(controller.isInitialized)
        assertFalse(controller.isSupported)
        assertNull(engine.lastEnabled)
        assertTrue(engine.bandGains.isEmpty())
    }

    /** Engine whose every operation throws — the controller must absorb it. */
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
    fun applyAbsorbsSetEnabledFailure() {
        val controller = EqualizerController { ThrowingEngine(enabledThrows = true) }
        controller.attachToSession(5, configWith(3))
        // Must not throw.
        controller.apply(configWith(6))
    }

    @Test
    fun applyAbsorbsSetBandLevelFailure() {
        val controller = EqualizerController { ThrowingEngine(bandThrows = true) }
        controller.attachToSession(5, configWith(3))
        // Must not throw.
        controller.apply(configWith(6))
    }

    @Test
    fun releaseAbsorbsEngineReleaseFailure() {
        val controller = EqualizerController { ThrowingEngine(releaseThrows = true) }
        controller.attachToSession(5, configWith(3))
        // Must not throw.
        controller.release()
        assertFalse(controller.isInitialized)
    }

    @Test
    fun attachAbsorbsFactoryFailureAndStaysUsable() {
        var calls = 0
        val controller = EqualizerController {
            calls++
            if (calls == 1) throw RuntimeException("creation failed") else FakeEngine()
        }

        controller.attachToSession(5, configWith(3))
        assertFalse(controller.isInitialized)

        controller.attachToSession(6, configWith(3))
        assertTrue(controller.isInitialized)
    }
}
