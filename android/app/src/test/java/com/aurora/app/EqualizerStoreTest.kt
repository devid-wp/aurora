package com.aurora.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aurora.app.audio.EqualizerConfig
import com.aurora.app.audio.EqualizerPresets
import com.aurora.app.audio.EqualizerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Persistence tests for the equalizer configuration in Aurora's prefs store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EqualizerStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: EqualizerStore

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences("equalizer-store-test", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        store = EqualizerStore(prefs)
    }

    @Test
    fun loadingWithoutSavedValue_returnsDefaultConfig() {
        assertEquals(EqualizerConfig.default(), store.load())
    }

    @Test
    fun saveThenLoad_roundTripsEnabledGainsAndPreset() {
        val config = EqualizerConfig.default()
            .withEnabled(true)
            .withPreset(EqualizerPresets.BASS_BOOST)
            .withPreamp(2)

        store.save(config)

        val loaded = store.load()
        assertTrue(loaded.enabled)
        assertEquals(EqualizerPresets.BASS_BOOST, loaded.preset)
        assertEquals(EqualizerPresets.gainsFor(EqualizerPresets.BASS_BOOST), loaded.gainsDb)
        assertEquals(2, loaded.preampDb)
    }

    @Test
    fun clearResetsToDefault() {
        store.save(EqualizerConfig.default().withEnabled(true))
        store.clear()
        assertFalse(store.load().enabled)
    }
}
