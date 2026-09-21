package com.aurora.app.database.repositories

import com.aurora.app.database.entities.DownloadEntity
import com.aurora.app.source.SourceMetadata

/**
 * Saved online entries that are already represented by a completed download.
 *
 * A download persists the finished audio as a separate `aurora_imported` row
 * (content-hash id) and records the originating `(source, sourceTrackId)` on
 * the [DownloadEntity]. The saved online row keeps its own stable id, so a
 * plain id comparison would leave the remote entry visible alongside the
 * downloaded file. Matching on the source identity instead lets the Library
 * show the real file once — under Downloaded, not Online.
 *
 * Pure and side-effect free so the reconciliation is unit testable without a
 * database or an Activity.
 */
internal fun supersededSavedOnlineIds(
    savedMetadata: Map<Long, SourceMetadata>,
    downloads: List<DownloadEntity>
): Set<Long> {
    if (savedMetadata.isEmpty() || downloads.isEmpty()) return emptySet()
    val completedSourceKeys = downloads.asSequence()
        .filter { it.status == "completed" }
        .filter { it.source.isNotBlank() && it.sourceTrackId.isNotBlank() }
        .map { it.source to it.sourceTrackId }
        .toSet()
    if (completedSourceKeys.isEmpty()) return emptySet()
    return savedMetadata
        .filterValues { it.trackId.source to it.trackId.value in completedSourceKeys }
        .keys
}
