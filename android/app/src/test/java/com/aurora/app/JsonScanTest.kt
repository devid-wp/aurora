package com.aurora.app

import com.aurora.app.source.JsonScan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Direct tests for the structural JSON scanner, including the nested-object
 * shadowing case that motivated it (Spotify track names live after nested
 * album/artist names in the same object).
 */
class JsonScanTest {

    private val track = """
        {
          "album": { "name": "Album Name", "artists": [ {"name": "Album Artist"} ] },
          "artists": [ {"name": "Artist One"}, {"name": "Artist Two"} ],
          "duration_ms": 210000,
          "id": "track-1",
          "name": "Real Track Name",
          "explicit": true
        }
    """.trimIndent()

    @Test
    fun topLevelString_isNotShadowedByNestedObjects() {
        assertEquals("Real Track Name", JsonScan.topLevelString(track, "name"))
    }

    @Test
    fun topLevelValue_extractsNestedObjectsAndArrays() {
        val album = JsonScan.topLevelValue(track, "album")!!
        assertEquals("Album Name", JsonScan.topLevelString(album, "name"))

        val artists = JsonScan.topLevelArrayObjects(track, "artists")
        assertEquals(2, artists.size)
        assertEquals("Artist One", JsonScan.topLevelString(artists[0], "name"))
        assertEquals("Artist Two", JsonScan.topLevelString(artists[1], "name"))
    }

    @Test
    fun topLevelLong_parsesNumbers() {
        assertEquals(210000L, JsonScan.topLevelLong(track, "duration_ms"))
        assertEquals(0L, JsonScan.topLevelLong(track, "missing"))
    }

    @Test
    fun missingKey_returnsEmptyOrDefaults() {
        assertEquals("", JsonScan.topLevelString(track, "nope"))
        assertEquals(0L, JsonScan.topLevelLong(track, "nope"))
        assertEquals(emptyList<String>(), JsonScan.topLevelArrayObjects(track, "nope"))
    }

    @Test
    fun escapedStrings_areUnescaped() {
        val json = """{"title":"He said \"hi\" \u0041"}"""
        assertEquals("He said \"hi\" A", JsonScan.topLevelString(json, "title"))
    }

    @Test
    fun arrayOfObjectsInsideArrayOfObjects_isParsed() {
        val json = """{"tracks":{"items":[{"name":"a"},{"name":"b"}],"total":2}}"""
        val container = JsonScan.topLevelValue(json, "tracks")!!
        val items = JsonScan.topLevelArrayObjects(container, "items")
        assertEquals(listOf("a", "b"), items.map { JsonScan.topLevelString(it, "name") })
    }
}
