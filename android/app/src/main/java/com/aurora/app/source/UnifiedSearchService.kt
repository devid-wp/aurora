package com.aurora.app.source

/** Per-provider outcome of a unified search, so the UI can stay honest. */
sealed class ProviderSearchState {
    data class Success(val count: Int) : ProviderSearchState()
    data class Empty(val message: String) : ProviderSearchState()
    data class NotConfigured(val message: String) : ProviderSearchState()
    data class Failed(val message: String) : ProviderSearchState()
}

data class ProviderSearchReport(
    val sourceId: String,
    val displayName: String,
    val state: ProviderSearchState
)

data class UnifiedSearchOutcome(
    val results: List<SourceMetadata>,
    val reports: List<ProviderSearchReport>
) {
    val isEmpty: Boolean get() = results.isEmpty()
    val succeededProviders: List<ProviderSearchReport>
        get() = reports.filter { it.state is ProviderSearchState.Success }
    val failedProviders: List<ProviderSearchReport>
        get() = reports.filter { it.state is ProviderSearchState.Failed }
    val unconfiguredProviders: List<ProviderSearchReport>
        get() = reports.filter { it.state is ProviderSearchState.NotConfigured }
}

/**
 * Queries every registered [MusicSource] and merges the results into one list.
 *
 * Deduplication is source-aware: the identity is `<source>:<sourceTrackId>`, so
 * the same song from two providers stays two results (never merged because the
 * title/artist happened to match), while duplicate ids from one provider
 * collapse. A failure in one provider is reported but never removes another
 * provider's results — a Spotify outage cannot break Local/Audius/SoundCloud.
 */
class UnifiedSearchService(private val sources: List<MusicSource>) {

    fun search(query: String): UnifiedSearchOutcome = search(query, null)

    /**
     * Same unified search, but reports the merged result set after each provider
     * finishes so the UI can show fast sources (Local) immediately while slower
     * online providers populate asynchronously. [complete] is true on the final
     * report. Existing callers keep using [search].
     */
    fun search(
        query: String,
        onProgress: ((outcome: UnifiedSearchOutcome, complete: Boolean) -> Unit)?
    ): UnifiedSearchOutcome {
        val reports = mutableListOf<ProviderSearchReport>()
        val collected = LinkedHashMap<String, SourceMetadata>()

        fun snapshot(): UnifiedSearchOutcome = UnifiedSearchOutcome(collected.values.toList(), reports.toList())

        sources.forEachIndexed { index, source ->
            val result = try {
                source.searchDetailed(query)
            } catch (e: Exception) {
                SourceSearchResult.Error(e.message ?: "Search failed")
            }
            val displayName = sourceDisplayName(source.sourceId)
            when (result) {
                is SourceSearchResult.Success -> {
                    result.results.forEach { collected.putIfAbsent(identity(it), it) }
                    reports += ProviderSearchReport(source.sourceId, displayName, ProviderSearchState.Success(result.results.size))
                }
                is SourceSearchResult.Empty ->
                    reports += ProviderSearchReport(source.sourceId, displayName, ProviderSearchState.Empty(result.message))
                is SourceSearchResult.NotConfigured ->
                    reports += ProviderSearchReport(source.sourceId, displayName, ProviderSearchState.NotConfigured(result.message))
                is SourceSearchResult.Error ->
                    reports += ProviderSearchReport(source.sourceId, displayName, ProviderSearchState.Failed(result.message))
            }
            onProgress?.invoke(snapshot(), index == sources.lastIndex)
        }

        return snapshot()
    }

    private fun identity(metadata: SourceMetadata): String =
        "${metadata.trackId.source}:${metadata.trackId.value}"
}
