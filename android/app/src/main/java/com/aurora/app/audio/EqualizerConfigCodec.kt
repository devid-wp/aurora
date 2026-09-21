package com.aurora.app.audio

/**
 * Dependency-free text codec for [EqualizerConfig], stored in the existing
 * Aurora SharedPreferences.
 *
 * Format: `version;enabled;preamp;preset;g1,g2,...`
 * Example: `1;1;2;Rock;5,4,3,1,-1,-1,1,3,4,5`
 *
 * Decoding is defensive: unknown versions and malformed payloads return null so
 * the caller falls back to defaults, and a gain list from an older/different
 * band layout is resampled to the canonical size (configuration migration).
 */
object EqualizerConfigCodec {
    private const val FIELD_SEPARATOR = ";"
    private const val GAIN_SEPARATOR = ","

    fun encode(config: EqualizerConfig): String {
        val normalized = config.normalized()
        return listOf(
            normalized.schemaVersion.toString(),
            if (normalized.enabled) "1" else "0",
            normalized.preampDb.toString(),
            normalized.preset,
            normalized.gainsDb.joinToString(GAIN_SEPARATOR)
        ).joinToString(FIELD_SEPARATOR)
    }

    fun decode(raw: String?): EqualizerConfig? {
        if (raw.isNullOrBlank()) return null
        val fields = raw.split(FIELD_SEPARATOR)
        if (fields.size < 5) return null
        val version = fields[0].trim().toIntOrNull() ?: return null
        if (version <= 0 || version > EqualizerConfig.SCHEMA_VERSION) return null
        val enabled = fields[1].trim() == "1"
        val preamp = fields[2].trim().toIntOrNull() ?: 0
        val preset = fields[3].ifBlank { EqualizerPresets.CUSTOM }
        val gains = fields[4].split(GAIN_SEPARATOR).mapNotNull { it.trim().toIntOrNull() }
        if (gains.isEmpty()) return null
        return EqualizerConfig(
            enabled = enabled,
            preset = preset,
            gainsDb = gains,
            preampDb = preamp,
            schemaVersion = version
        ).normalized()
    }
}
