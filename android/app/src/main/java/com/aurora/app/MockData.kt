package com.aurora.app

/** Local-only mock data. No network, no backend, no auth. */
object MockData {

    data class Track(
        val title: String,
        val artist: String,
        val albumArt: String,   // emoji stand-in for album art colour/icon
        val duration: String
    )

    data class Album(
        val title: String,
        val artist: String,
        val albumArt: String,
        val year: String
    )

    val recentlyPlayed: List<Track> = listOf(
        Track("Neon Drift",        "Synthwave Echo",   "🌊", "3:42"),
        Track("Violet Hour",       "Aurora Dreams",    "🌸", "4:15"),
        Track("Dark Matter",       "The Void Choir",   "🌑", "5:01"),
        Track("Starfield",         "Cosmo & the Keys", "✨", "3:28"),
        Track("Midnight Signal",   "Nova Static",      "📡", "4:47"),
        Track("Glass Rain",        "Lunar Palette",    "🔮", "3:55")
    )

    val library: List<Album> = listOf(
        Album("Echoes of Purple",  "Aurora Dreams",    "🎵", "2024"),
        Album("Submerge",          "Deep Frequency",   "🎧", "2023"),
        Album("Celestial Drift",   "Cosmo & the Keys", "🌌", "2024"),
        Album("Signal Lost",       "Nova Static",      "📻", "2022"),
        Album("Phantom Bloom",     "The Void Choir",   "🌺", "2023"),
        Album("Binary Sunset",     "Synthwave Echo",   "🌅", "2024")
    )

    val nowPlaying: Track =
        Track("Violet Hour", "Aurora Dreams", "🌸", "4:15")
}
