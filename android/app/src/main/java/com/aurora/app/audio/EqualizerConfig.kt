package com.aurora.app.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Canonical equalizer band layout.
 *
 * Aurora always models a 10-band EQ so the configuration is stable across
 * devices and restarts. When the Android [android.media.audiofx.Equalizer]
 * exposes a different number of bands, the controller maps each hardware band
 * to the nearest canonical frequency — so a 5-band device still receives a
 * meaningful curve and nothing crashes.
 */
object EqualizerBands {
    const val BAND_COUNT = 10
    const val DEFAULT_MIN_DB = -15
    const val DEFAULT_MAX_DB = 15
    const val DEFAULT_MIN_PREAMP_DB = -15
    const val DEFAULT_MAX_PREAMP_DB = 15

    val CENTER_FREQUENCIES_HZ = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val CENTER_LABELS = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")

    fun clampGain(
        gainDb: Int,
        minDb: Int = DEFAULT_MIN_DB,
        maxDb: Int = DEFAULT_MAX_DB
    ): Int = gainDb.coerceIn(minDb, maxDb)

    fun clampPreamp(
        preampDb: Int,
        minDb: Int = DEFAULT_MIN_PREAMP_DB,
        maxDb: Int = DEFAULT_MAX_PREAMP_DB
    ): Int = preampDb.coerceIn(minDb, maxDb)

    fun flatGains(): List<Int> = List(BAND_COUNT) { 0 }

    /** Index of the canonical band whose center frequency is closest to [freqHz]. */
    fun nearestCanonicalIndex(freqHz: Int): Int {
        var best = 0
        var bestDiff = Int.MAX_VALUE
        CENTER_FREQUENCIES_HZ.forEachIndexed { index, freq ->
            val diff = abs(freq - freqHz)
            if (diff < bestDiff) {
                bestDiff = diff
                best = index
            }
        }
        return best
    }

    /**
     * Per-hardware-band gains implied by the canonical [gains].
     *
     * Each canonical band's **full** gain is applied to its nearest hardware
     * band. This is deliberately peak-preserving: interpolating an isolated
     * canonical band across neighbouring hardware bands diluted it badly on a
     * device with fewer bands than canonical (e.g. +10 dB became ~+1.5 dB at the
     * nearest band), which is why band moves were barely audible. Canonical
     * bands that land on the same hardware band are summed; the engine then
     * clamps to the device's real bandLevelRange, so nothing exceeds hardware
     * limits.
     *
     * When the device reports no usable center frequencies, bands are mapped
     * positionally so nothing collapses onto band 0.
     */
    fun mapToHardwareBands(gains: List<Int>, hardwareFreqs: List<Int>): List<Float> {
        if (hardwareFreqs.isEmpty() || gains.isEmpty()) return List(hardwareFreqs.size) { 0f }
        val result = FloatArray(hardwareFreqs.size)
        val usableFreqs = hardwareFreqs.all { it > 0 }
        gains.forEachIndexed { canonical, gain ->
            if (gain == 0 || canonical !in CENTER_FREQUENCIES_HZ.indices) return@forEachIndexed
            val target = when {
                usableFreqs -> nearestHardwareIndex(CENTER_FREQUENCIES_HZ[canonical], hardwareFreqs)
                hardwareFreqs.size <= 1 -> 0
                else -> (canonical.toFloat() * (hardwareFreqs.size - 1) / (gains.size - 1))
                    .roundToInt()
                    .coerceIn(0, hardwareFreqs.size - 1)
            }
            result[target] += gain.toFloat()
        }
        return result.toList()
    }

    private fun nearestHardwareIndex(freqHz: Int, hardwareFreqs: List<Int>): Int {
        var best = 0
        var bestDiff = Int.MAX_VALUE
        hardwareFreqs.forEachIndexed { index, freq ->
            val diff = abs(freq - freqHz)
            if (diff < bestDiff) {
                bestDiff = diff
                best = index
            }
        }
        return best
    }

    /**
     * Resamples a gain list to [targetCount] entries, used when loading a config
     * saved on a device with a different band layout. Nearest-neighbour is
     * deterministic and never invents gains.
     */
    fun resampleGains(gains: List<Int>, targetCount: Int = BAND_COUNT): List<Int> {
        if (targetCount <= 0) return emptyList()
        if (gains.isEmpty()) return List(targetCount) { 0 }
        if (gains.size == targetCount) return gains
        return List(targetCount) { index ->
            val sourceIndex = (index.toLong() * gains.size / targetCount).toInt().coerceIn(0, gains.lastIndex)
            gains[sourceIndex]
        }
    }
}

/** Built-in Aurora presets, defined in Aurora's own configuration (not hardware). */
object EqualizerPresets {
    const val FLAT = "Flat"
    const val BASS_BOOST = "Bass Boost"
    const val TREBLE = "Treble"
    const val VOCAL = "Vocal"
    const val ROCK = "Rock"
    const val POP = "Pop"
    const val CLASSICAL = "Classical"
    const val CUSTOM = "Custom"

    /** Selectable curve presets (Custom is a state, not a curve). */
    val SELECTABLE = listOf(FLAT, BASS_BOOST, TREBLE, VOCAL, ROCK, POP, CLASSICAL)

    val CURVES: Map<String, List<Int>> = mapOf(
        FLAT to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        BASS_BOOST to listOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0),
        TREBLE to listOf(0, 0, 0, 0, 0, 1, 3, 5, 6, 6),
        VOCAL to listOf(-2, -1, 0, 2, 4, 4, 2, 1, 0, -1),
        ROCK to listOf(5, 4, 3, 1, -1, -1, 1, 3, 4, 5),
        POP to listOf(-1, 0, 2, 4, 4, 2, 0, -1, -1, -1),
        CLASSICAL to listOf(3, 2, 1, 0, 0, 0, -1, -2, -3, -4)
    )

    fun gainsFor(name: String): List<Int>? = CURVES[name]?.let { resampleToCanonical(it) }

    /**
     * Returns the preset whose curve exactly matches [gains], or [CUSTOM].
     * This is how the UI detects that a hand-edited curve is no longer a preset.
     */
    fun detect(gains: List<Int>): String =
        CURVES.entries.firstOrNull { resampleToCanonical(it.value) == gains }?.key ?: CUSTOM

    private fun resampleToCanonical(curve: List<Int>): List<Int> =
        EqualizerBands.resampleGains(curve, EqualizerBands.BAND_COUNT)
}

/**
 * Immutable equalizer configuration: the single persisted model.
 *
 * Gains are stored in whole decibels for the canonical 10 bands. [preampDb] is
 * applied as a global offset on top of the curve when the effect is written to
 * hardware, so Aurora does not depend on a device preamp API.
 */
data class EqualizerConfig(
    val enabled: Boolean = false,
    val preset: String = EqualizerPresets.FLAT,
    val gainsDb: List<Int> = EqualizerPresets.gainsFor(EqualizerPresets.FLAT) ?: EqualizerBands.flatGains(),
    val preampDb: Int = 0,
    val schemaVersion: Int = SCHEMA_VERSION
) {
    /** Clamps, resamples and re-detects the preset so any config is safe to apply. */
    fun normalized(): EqualizerConfig {
        val gains = EqualizerBands.resampleGains(gainsDb, EqualizerBands.BAND_COUNT)
            .map { EqualizerBands.clampGain(it) }
        val resolvedPreset = if (preset == EqualizerPresets.CUSTOM) {
            EqualizerPresets.CUSTOM
        } else {
            EqualizerPresets.detect(gains)
        }
        return copy(
            gainsDb = gains,
            preampDb = EqualizerBands.clampPreamp(preampDb),
            preset = resolvedPreset,
            schemaVersion = SCHEMA_VERSION
        )
    }

    fun withEnabled(value: Boolean): EqualizerConfig = copy(enabled = value)

    /** Applies a preset curve. Selecting [EqualizerPresets.CUSTOM] keeps the curve. */
    fun withPreset(name: String): EqualizerConfig {
        if (name == EqualizerPresets.CUSTOM) return copy(preset = EqualizerPresets.CUSTOM)
        val curve = EqualizerPresets.gainsFor(name) ?: return this
        return copy(preset = name, gainsDb = curve)
    }

    fun withBandGain(
        index: Int,
        gainDb: Int,
        minDb: Int = EqualizerBands.DEFAULT_MIN_DB,
        maxDb: Int = EqualizerBands.DEFAULT_MAX_DB
    ): EqualizerConfig {
        if (index !in gainsDb.indices) return this
        val updated = gainsDb.toMutableList().also {
            it[index] = EqualizerBands.clampGain(gainDb, minDb, maxDb)
        }
        return copy(gainsDb = updated, preset = EqualizerPresets.detect(updated))
    }

    fun withPreamp(db: Int): EqualizerConfig = copy(preampDb = EqualizerBands.clampPreamp(db))

    /** Resets the curve to flat but keeps the enabled state and preamp. */
    fun resetToFlat(): EqualizerConfig =
        copy(preset = EqualizerPresets.FLAT, gainsDb = EqualizerBands.flatGains())

    companion object {
        const val SCHEMA_VERSION = 1
        fun default(): EqualizerConfig = EqualizerConfig()
    }
}
