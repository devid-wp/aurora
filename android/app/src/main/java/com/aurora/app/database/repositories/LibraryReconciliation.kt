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
 * [localCopySourceKeys] lets the caller also supersede a saved entry whose
 * file still exists after its download job was removed, so the library stays
 * coherent without relying on download-job bookkeeping alone.
 *
 * Pure and side-effect free so the reconciliation is unit testable without a
 * database or an Activity.
 */
internal fun supersededSavedOnlineIds(
    savedMetadata: Map<Long, SourceMetadata>,
    downloads: List<DownloadEntity>,
    localCopySourceKeys: Set<Pair<String, String>> = emptySet()
): Set<Long> {
    if (savedMetadata.isEmpty()) return emptySet()
    val completedSourceKeys = downloads.asSequence()
        .filter { it.status == "completed" }
        .filter { it.source.isNotBlank() && it.sourceTrackId.isNotBlank() }
        .map { it.source to it.sourceTrackId }
        .toSet()
    // A verified local file with the same online origin supersedes the remote
    // entry even if its download job record was later removed, so the library
    // never shows the same song twice.
    val supersedingKeys = completedSourceKeys + localCopySourceKeys
    if (supersedingKeys.isEmpty()) return emptySet()
    return savedMetadata
        .filterValues { it.trackId.source to it.trackId.value in supersedingKeys }
        .keys
}
