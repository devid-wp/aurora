package com.aurora.app.audio

import kotlin.math.roundToInt

/**
 * Owns the lifecycle of the hardware [EqualizerEngine] and applies the Aurora
 * [EqualizerConfig] to it.
 *
 * Pure with respect to Android: the engine is created through an injected
 * factory, so the mapping/apply logic is fully unit-testable with a fake engine.
 * A new player session detaches the old engine first, so recreating the playback
 * service or switching tracks can never leak an `AudioEffect`.
 */
class EqualizerController(
    private val engineFactory: (sessionId: Int) -> EqualizerEngine
) {
    private var engine: EqualizerEngine? = null

    /** The session id of the most recent attach attempt (0 when none). */
    var lastSessionId: Int = 0
        private set

    /** Snapshot of the most recent apply, for the internal diagnostic path. */
    var lastSnapshot: EqualizerDiagnosticSnapshot? = null
        private set

    /** An attach was attempted for a real player session. */
    val isInitialized: Boolean get() = engine != null

    /** The device actually exposes a usable equalizer effect. */
    val isSupported: Boolean get() = engine?.available == true

    val bandCount: Int get() = engine?.bandCount ?: 0

    val deviceBands: List<EqualizerBandInfo> get() = engine?.bands ?: emptyList()

    /** Observable effect state (session, enabled, bands, failure reason). */
    val status: EqualizerStatus get() = engine?.status ?: EqualizerStatus.Released

    /**
     * Compact, testable diagnostic string. Internal only — never shown as noisy
     * UI. Distinguishes released / invalid session / creation failure / no
     * bands / enable or band write failures.
     */
    fun diagnostic(): String {
        val current = engine ?: return "equalizer[released session=$lastSessionId valid=${lastSessionId > 0}]"
        val enabled = current.enabledState?.toString() ?: "unknown"
        val reason = current.creationError ?: current.unavailableReason ?: "-"
        return "equalizer[available=${current.available} session=$lastSessionId " +
            "valid=${lastSessionId > 0} bands=${current.bandCount} enabled=$enabled reason=$reason]"
    }

    /** Full multi-line snapshot of the last attachment/apply (for logs/tests). */
    fun diagnosticReport(): String {
        val snapshot = lastSnapshot
            ?: return diagnostic()
        val header = "equalizer session=${snapshot.sessionId} valid=${snapshot.sessionValid} " +
            "created=${snapshot.effectCreated} createError=${snapshot.creationError ?: "-"} " +
            "bands=${snapshot.numberOfBands} range=[${snapshot.minLevelMb},${snapshot.maxLevelMb}] " +
            "enabledReq=${snapshot.enabledRequested} enabledRead=${snapshot.enabledReadBack ?: "unknown"}"
        if (snapshot.bands.isEmpty()) return "$header status=${snapshot.finalStatus}"
        val rows = snapshot.bands.joinToString("\n") { band ->
            "  band=${band.hardwareBand} freq=${band.centerFreqHz}Hz canon=${band.canonicalIndex} " +
                "reqDb=${band.requestedDb} reqMb=${band.requestedMillibels} " +
                "wroteMb=${band.writtenMillibels ?: "-"} readMb=${band.readBackMillibels ?: "-"} " +
                "readDb=${band.readBackDb ?: "-"}"
        }
        return "$header status=${snapshot.finalStatus}\n$rows"
    }

    /**
     * Attaches to [sessionId] (Aurora's own MediaPlayer audio session) and applies
     * [config]. A non-positive session id, or an effect that cannot be created,
     * leaves the controller inactive without throwing.
     */
    fun attachToSession(sessionId: Int, config: EqualizerConfig) {
        release()
        lastSessionId = sessionId
        if (sessionId <= 0) return
        engine = try {
            engineFactory(sessionId)
        } catch (_: Exception) {
            null
        }
        if (engine?.available == true) apply(config)
    }

    /** Applies [config] to the current engine, if any. Never throws. */
    fun apply(config: EqualizerConfig) {
        val current = engine ?: return
        if (!current.available) return
        val normalized = config.normalized()
        val range = current.bandLevelRangeMb
        val minMb = range.getOrElse(0) { EqualizerBands.DEFAULT_MIN_DB * 100 }
        val maxMb = range.getOrElse(1) { EqualizerBands.DEFAULT_MAX_DB * 100 }
        try {
            current.setEnabled(normalized.enabled)
            val rows = mutableListOf<EqualizerBandDiagnostic>()
            if (normalized.enabled) {
                // Preamp is applied as a global offset on top of the curve so no
                // separate device preamp effect/API is required.
                val offset = normalized.preampDb
                // Apply each canonical band's full gain to its nearest hardware
                // band (peak-preserving) so a ±10 dB move is not diluted.
                val hardwareGains = EqualizerBands.mapToHardwareBands(
                    normalized.gainsDb,
                    current.bands.map { it.centerFreqHz }
                )
                current.bands.forEach { band ->
                    val gainDb = hardwareGains.getOrElse(band.index) { 0f } + offset
                    current.setBandGain(band.index, gainDb)
                    val readBack = current.readBackMillibels(band.index)
                    rows += EqualizerBandDiagnostic(
                        hardwareBand = band.index,
                        centerFreqHz = band.centerFreqHz,
                        canonicalIndex = diagnosticCanonicalIndex(band, current.bandCount),
                        requestedDb = gainDb,
                        requestedMillibels = EqualizerGain.toMillibels(gainDb, minMb, maxMb),
                        writtenMillibels = current.lastWrittenMillibels(band.index),
                        readBackMillibels = readBack,
                        readBackDb = readBack?.let { it / 100f }
                    )
                }
            }
            lastSnapshot = EqualizerDiagnosticSnapshot(
                sessionId = lastSessionId,
                sessionValid = lastSessionId > 0,
                effectCreated = current.effectCreated,
                creationError = current.creationError,
                numberOfBands = current.bandCount,
                minLevelMb = minMb,
                maxLevelMb = maxMb,
                enabledRequested = normalized.enabled,
                enabledReadBack = current.enabledState,
                bands = rows,
                finalStatus = current.status.toString()
            )
        } catch (_: Exception) {
            // A misbehaving effect must never interrupt playback.
        }
    }

    private fun diagnosticCanonicalIndex(band: EqualizerBandInfo, hardwareCount: Int): Int {
        if (band.centerFreqHz > 0) return EqualizerBands.nearestCanonicalIndex(band.centerFreqHz)
        if (hardwareCount <= 1) return 0
        return (band.index.toFloat() * (EqualizerBands.BAND_COUNT - 1) / (hardwareCount - 1))
            .roundToInt()
            .coerceIn(0, EqualizerBands.BAND_COUNT - 1)
    }

    /** Releases the effect. Safe to call repeatedly. */
    fun release() {
        val current = engine
        engine = null
        try {
            current?.release()
        } catch (_: Exception) {
        }
    }
}
