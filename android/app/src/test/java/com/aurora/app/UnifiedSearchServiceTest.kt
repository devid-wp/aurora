package com.aurora.app

import com.aurora.app.source.DownloadAvailability
import com.aurora.app.source.DownloadCapability
import com.aurora.app.source.DownloadResult
import com.aurora.app.source.MusicSource
import com.aurora.app.source.ProviderSearchState
import com.aurora.app.source.SoundCloudHttpClient
import com.aurora.app.source.SoundCloudResponse
import com.aurora.app.source.SourceCapability
import com.aurora.app.source.SourceMetadata
import com.aurora.app.source.SourceSearchResult
import com.aurora.app.source.SourceTrackId
import com.aurora.app.source.SpotifySource
import com.aurora.app.source.SpotifyToken
import com.aurora.app.source.StreamResult
import com.aurora.app.source.UnifiedSearchService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for the unified search layer: source-aware deduplication and per-provider
 * failure isolation. No Android service or network involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedSearchServiceTest {

    private fun metadata(source: String, value: String, title: String = "Track $value"): SourceMetadata =
        SourceMetadata(
            trackId = SourceTrackId(source, value),
            title = title,
            artist = "Artist",
            album = "Album",
            durationMs = 1000L,
            sourceCapabilities = setOf(SourceCapability.STREAM)
        )

    private class FakeSource(
        override val sourceId: String,
        private val outcome: SourceSearchResult,
        private val boom: Exception? = null
    ) : MusicSource {
        override val capabilities: Set<SourceCapability> = setOf(SourceCapability.STREAM)
        override fun search(query: String): List<SourceMetadata> = emptyList()
        override fun getTrack(id: SourceTrackId): SourceMetadata? = null
        override fun stream(track: SourceMetadata): StreamResult = StreamResult(uri = null)
        override fun checkDownloadAvailability(track: SourceMetadata) =
            DownloadCapability(DownloadAvailability.BLOCKED)
        override fun download(track: SourceMetadata, targetDir: File): DownloadResult =
            DownloadResult(success = false)

        override fun searchDetailed(query: String): SourceSearchResult {
            boom?.let { throw it }
            return outcome
        }
    }

    private fun failingSpotify(): SpotifySource {
        val http = object : SoundCloudHttpClient {
            override fun get(url: String, headers: Map<String, String>) = SoundCloudResponse(-1)
            override fun getBytes(url: String, headers: Map<String, String>) = SoundCloudResponse(-1)
            override fun post(url: String, headers: Map<String, String>) = SoundCloudResponse(-1)
            override fun delete(url: String, headers: Map<String, String>) = SoundCloudResponse(-1)
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) =
                SoundCloudResponse(-1)
        }
        return SpotifySource(
            clientIdProvider = { "cid" },
            redirectUriProvider = { "aurora://spotify/callback" },
            httpClient = http
        ).apply {
            seedSessionForTesting(SpotifyToken(accessToken = "t", expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L))
        }
    }

    @Test
    fun mergesResultsFromAllProviders() {
        val service = UnifiedSearchService(
            listOf(
                FakeSource("a", SourceSearchResult.Success(listOf(metadata("a", "1"), metadata("a", "2")))),
                FakeSource("b", SourceSearchResult.Success(listOf(metadata("b", "1"))))
            )
        )

        val outcome = service.search("q")

        assertEquals(listOf("a:1", "a:2", "b:1"), outcome.results.map { "${it.trackId.source}:${it.trackId.value}" })
        assertEquals(2, outcome.succeededProviders.size)
    }

    @Test
    fun deduplicatesWithinAProviderBySourceIdentity() {
        val service = UnifiedSearchService(
            listOf(
                FakeSource(
                    "a",
                    SourceSearchResult.Success(
                        listOf(
                            metadata("a", "dup", "First"),
                            metadata("a", "dup", "Second"),
                            metadata("a", "other")
                        )
                    )
                )
            )
        )

        val outcome = service.search("q")

        assertEquals(2, outcome.results.size)
        assertEquals("First", outcome.results.first().title)
    }

    @Test
    fun doesNotMergeSameTitleAcrossProviders() {
        val service = UnifiedSearchService(
            listOf(
                FakeSource("audius", SourceSearchResult.Success(listOf(metadata("audius", "1", "Same Song")))),
                FakeSource("soundcloud", SourceSearchResult.Success(listOf(metadata("soundcloud", "1", "Same Song"))))
            )
        )

        val outcome = service.search("q")

        assertEquals("same title from different providers must stay separate", 2, outcome.results.size)
        assertEquals(setOf("audius", "soundcloud"), outcome.results.map { it.trackId.source }.toSet())
    }

    @Test
    fun providerErrorDoesNotRemoveOtherProvidersResults() {
        val service = UnifiedSearchService(
            listOf(
                FakeSource("local", SourceSearchResult.Success(listOf(metadata("local", "1")))),
                FakeSource("spotify", SourceSearchResult.Error("Spotify is unreachable"))
            )
        )

        val outcome = service.search("q")

        assertEquals(1, outcome.results.size)
        assertEquals("local", outcome.results.first().trackId.source)
        assertEquals(1, outcome.failedProviders.size)
        assertTrue(outcome.failedProviders.first().state is ProviderSearchState.Failed)
    }

    @Test
    fun providerExceptionIsIsolated() {
        val service = UnifiedSearchService(
            listOf(
                FakeSource("local", SourceSearchResult.Success(listOf(metadata("local", "1")))),
                FakeSource("boom", SourceSearchResult.Empty(), boom = IllegalStateException("kaboom"))
            )
        )

        val outcome = service.search("q")

        assertEquals(1, outcome.results.size)
        assertEquals(1, outcome.failedProviders.size)
    }

    @Test
    fun notConfiguredAndEmptyProvidersAreReportedHonestly() {
        val service = UnifiedSearchService(
            listOf(
                FakeSource("local", SourceSearchResult.Success(listOf(metadata("local", "1")))),
                FakeSource("spotify", SourceSearchResult.NotConfigured("Spotify is not configured")),
                FakeSource("audius", SourceSearchResult.Empty("No Audius results"))
            )
        )

        val outcome = service.search("q")

        assertEquals(1, outcome.results.size)
        assertEquals(1, outcome.unconfiguredProviders.size)
        assertEquals("spotify", outcome.unconfiguredProviders.first().sourceId)
        assertTrue(outcome.reports.any { it.sourceId == "audius" && it.state is ProviderSearchState.Empty })
    }

    @Test
    fun progressiveSearch_reportsAfterEachProviderAndCompletesOnTheLast() {
        val service = UnifiedSearchService(
            listOf(
                FakeSource("local", SourceSearchResult.Success(listOf(metadata("local", "1")))),
                FakeSource("spotify", SourceSearchResult.Error("Spotify is unreachable"))
            )
        )

        val progress = mutableListOf<Pair<Int, Boolean>>()
        val finalOutcome = service.search("q") { outcome, complete ->
            progress.add(outcome.results.size to complete)
        }

        assertEquals(listOf(1 to false, 1 to true), progress)
        assertEquals(1, finalOutcome.results.size)
        assertTrue(finalOutcome.failedProviders.any { it.sourceId == "spotify" })
    }

    @Test
    fun progressiveSearch_deliversFastLocalResultsBeforeOnlineOnes() {
        val service = UnifiedSearchService(
            listOf(
                FakeSource("aurora_local", SourceSearchResult.Success(listOf(metadata("aurora_local", "1")))),
                FakeSource("audius", SourceSearchResult.Success(listOf(metadata("audius", "2"))))
            )
        )

        val sizes = mutableListOf<Int>()
        service.search("q") { outcome, _ -> sizes.add(outcome.results.size) }

        assertEquals("local results must be reported before online ones append", listOf(1, 2), sizes)
    }

    @Test
    fun spotifyNetworkFailureDoesNotRemoveLocalResults() {
        val local = FakeSource("aurora_local", SourceSearchResult.Success(listOf(metadata("aurora_local", "1", "Local Song"))))
        val service = UnifiedSearchService(listOf(local, failingSpotify()))

        val outcome = service.search("q")

        assertFalse("Spotify failing must not clear other providers", outcome.isEmpty)
        assertEquals("Local Song", outcome.results.first().title)
        assertTrue(outcome.failedProviders.any { it.sourceId == "spotify" })
    }
}
