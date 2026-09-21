package com.aurora.app.audio

import android.media.audiofx.Equalizer
import kotlin.math.roundToInt

/** One hardware equalizer band as reported by the device. */
data class EqualizerBandInfo(
    val index: Int,
    val centerFreqHz: Int,
    val minGainDb: Float,
    val maxGainDb: Float
)

/**
 * Android Equalizer band levels are expressed in **millibels** (mB), not
 * decibels: 1 dB = 100 mB. This is the single place the conversion happens so a
 * unit mismatch cannot creep in, and the result is clamped to the hardware's
 * reported range so `setBandLevel` never receives an out-of-range value.
 */
object EqualizerGain {
    fun toMillibels(gainDb: Float, minMb: Int, maxMb: Int): Int {
        val low = minOf(minMb, maxMb)
        val high = maxOf(minMb, maxMb)
        return (gainDb * 100f).roundToInt().coerceIn(low, high)
    }
}

/** Observable state of the audio effect, used for diagnostics and tests. */
sealed class EqualizerStatus {
    object Released : EqualizerStatus()

    data class Unavailable(val sessionId: Int, val reason: String?) : EqualizerStatus()

    data class Active(val sessionId: Int, val enabled: Boolean, val bands: Int) : EqualizerStatus()
}

/**
 * Per-band diagnostic record of one attachment/apply attempt. Captures the
 * requested gain, the converted millibels, what was written and what hardware
 * actually read back — so a silent mismatch is never discarded.
 */
data class EqualizerBandDiagnostic(
    val hardwareBand: Int,
    val centerFreqHz: Int,
    val canonicalIndex: Int,
    val requestedDb: Float,
    val requestedMillibels: Int,
    val writtenMillibels: Int?,
    val readBackMillibels: Int?,
    val readBackDb: Float?
)

/**
 * Deterministic snapshot of the most recent equalizer attachment/apply attempt.
 * Everything required to explain why the effect is (in)audible without a device
 * in the loop.
 */
data class EqualizerDiagnosticSnapshot(
    val sessionId: Int,
    val sessionValid: Boolean,
    val effectCreated: Boolean,
    val creationError: String?,
    val numberOfBands: Int,
    val minLevelMb: Int,
    val maxLevelMb: Int,
    val enabledRequested: Boolean,
    val enabledReadBack: Boolean?,
    val bands: List<EqualizerBandDiagnostic>,
    val finalStatus: String
)

/**
 * Thin, crash-proof boundary over the Android audio effect. Implementations must
 * never throw: an effect failure must not break playback.
 */
interface EqualizerEngine {
    val available: Boolean
    val bandCount: Int
    val bands: List<EqualizerBandInfo>

    fun setEnabled(enabled: Boolean)
    fun setBandGain(band: Int, gainDb: Float)
    fun release()

    /** Enabled state read back from hardware, or null when unknown. */
    val enabledState: Boolean? get() = null

    /** Reason the effect is unavailable/failing, or null. */
    val unavailableReason: String? get() = null

    /** Observable state for diagnostics. */
    val status: EqualizerStatus
        get() = if (available) {
            EqualizerStatus.Active(0, enabledState ?: false, bandCount)
        } else {
            EqualizerStatus.Released
        }

    // ── Diagnostics (defaulted so simple fakes stay simple) ───────────────

    /** True when the underlying effect object was constructed. */
    val effectCreated: Boolean get() = available

    /** Reason effect construction/initialisation failed, or null. */
    val creationError: String? get() = unavailableReason

    /** Hardware band level range in millibels as `[min, max]`, or empty. */
    val bandLevelRangeMb: List<Int> get() = emptyList()

    /** Millibel level Aurora last wrote for [band], or null. */
    fun lastWrittenMillibels(band: Int): Int? = null

    /** Millibel level read back from hardware for [band], or null. */
    fun readBackMillibels(band: Int): Int? = null
}

/**
 * Real [EqualizerEngine] attached to Aurora's own player audio session id.
 *
 * The effect is created against the session (never session 0 / global mix), so
 * it only shapes Aurora playback and can never attach to another app's audio
 * (for example Spotify's external playback).
 */
class AndroidEqualizerEngine(private val sessionId: Int) : EqualizerEngine {

    private var equalizer: Equalizer? = null
    private var minMb: Int = EqualizerBands.DEFAULT_MIN_DB * 100
    private var maxMb: Int = EqualizerBands.DEFAULT_MAX_DB * 100
    private val writtenMb = mutableMapOf<Int, Int>()
    private val readBackMb = mutableMapOf<Int, Int>()

    override var available: Boolean = false
        private set
    override var bandCount: Int = 0
        private set
    override var bands: List<EqualizerBandInfo> = emptyList()
        private set
    override var enabledState: Boolean? = null
        private set
    override var unavailableReason: String? = null
        private set
    override var effectCreated: Boolean = false
        private set
    override var creationError: String? = null
        private set

    override val bandLevelRangeMb: List<Int> get() = listOf(minMb, maxMb)

    override fun lastWrittenMillibels(band: Int): Int? = writtenMb[band]

    override fun readBackMillibels(band: Int): Int? = readBackMb[band]

    override val status: EqualizerStatus
        get() = if (equalizer != null && available) {
            EqualizerStatus.Active(sessionId, enabledState ?: false, bandCount)
        } else {
            EqualizerStatus.Unavailable(sessionId, creationError ?: unavailableReason)
        }

    init {
        if (sessionId <= 0) {
            creationError = "invalid audio session ($sessionId)"
            unavailableReason = creationError
        } else {
            val effect = try {
                Equalizer(0, sessionId)
            } catch (e: Exception) {
                creationError = "effect creation failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
                unavailableReason = creationError
                null
            }
            effectCreated = effect != null
            if (effect != null) {
                try {
                    val range = effect.bandLevelRange
                    if (range != null && range.size >= 2) {
                        minMb = range[0].toInt()
                        maxMb = range[1].toInt()
                    }
                    bandCount = effect.numberOfBands.toInt()
                    bands = (0 until bandCount).map { band ->
                        EqualizerBandInfo(
                            index = band,
                            centerFreqHz = normalizeCenterFreq(effect.getCenterFreq(band.toShort())),
                            minGainDb = minMb / 100f,
                            maxGainDb = maxMb / 100f
                        )
                    }
                    equalizer = effect
                    available = bandCount > 0
                    if (!available) {
                        creationError = "device exposes no equalizer bands"
                        unavailableReason = creationError
                    }
                } catch (e: Exception) {
                    runCatching { effect.release() }
                    equalizer = null
                    available = false
                    bandCount = 0
                    bands = emptyList()
                    creationError = "effect init failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
                    unavailableReason = creationError
                }
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        val effect = equalizer ?: return
        try {
            effect.enabled = enabled
            // Read back where the API permits so a silently ignored write is
            // captured instead of failing silently.
            enabledState = effect.enabled
        } catch (e: Exception) {
            enabledState = null
            unavailableReason = "setEnabled failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
        }
    }

    override fun setBandGain(band: Int, gainDb: Float) {
        val effect = equalizer ?: return
        val millibels = EqualizerGain.toMillibels(gainDb, minMb, maxMb)
        writtenMb[band] = millibels
        try {
            effect.setBandLevel(band.toShort(), millibels.toShort())
        } catch (e: Exception) {
            unavailableReason = "setBandLevel failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
            return
        }
        val readBack = try {
            effect.getBandLevel(band.toShort()).toInt()
        } catch (e: Exception) {
            unavailableReason = "getBandLevel failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
            null
        }
        if (readBack != null) readBackMb[band] = readBack
    }

    override fun release() {
        val effect = equalizer
        equalizer = null
        available = false
        bandCount = 0
        bands = emptyList()
        enabledState = null
        writtenMb.clear()
        readBackMb.clear()
        try {
            effect?.release()
        } catch (_: Exception) {
        }
    }

    /**
     * `Equalizer.getCenterFreq` documents milliHertz, but some devices report
     * plain Hertz. Normalise both to Hz, and treat 0 as "unknown" so the
     * controller can fall back to a positional mapping instead of collapsing
     * every band onto canonical band 0.
     */
    private fun normalizeCenterFreq(raw: Int): Int = when {
        raw <= 0 -> 0
        raw <= 24_000 -> raw
        else -> raw / 1000
    }
}
