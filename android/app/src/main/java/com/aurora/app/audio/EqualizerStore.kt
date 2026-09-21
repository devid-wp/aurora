package com.aurora.app.audio

import android.content.SharedPreferences

/**
 * Persists the single [EqualizerConfig] in Aurora's existing `aurora_preferences`
 * store — no separate database or preferences file is introduced.
 */
class EqualizerStore(private val preferences: SharedPreferences) {

    fun load(): EqualizerConfig =
        EqualizerConfigCodec.decode(preferences.getString(KEY, null)) ?: EqualizerConfig.default()

    fun save(config: EqualizerConfig) {
        preferences.edit().putString(KEY, EqualizerConfigCodec.encode(config)).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    companion object {
        const val KEY = "equalizer_config"
    }
}
