package com.aurora.app

import com.aurora.app.database.entities.DownloadEntity
import com.aurora.app.database.repositories.supersededSavedOnlineIds
import com.aurora.app.source.SourceMetadata
import com.aurora.app.source.SourceTrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for reconciling saved online library entries with completed
 * downloads. A finished download persists its own content-hash row, so the
 * saved remote entry must be matched by source identity instead of by track id
 * to avoid showing the same song as both Online and Downloaded.
 */
class LibraryReconciliationTest {

    private fun metadata(id: Long, source: String, value: String): Pair<Long, SourceMetadata> =
        id to SourceMetadata(
            trackId = SourceTrackId(source, value),
            title = "Track $value",
            artist = "Artist",
            album = "Album"
        )

    private fun completedDownload(source: String, value: String, trackId: Long?): DownloadEntity =
        DownloadEntity(
            downloadId = value.hashCode().toLong(),
            trackId = trackId,
            source = source,
            sourceTrackId = value,
            title = "Track $value",
            artist = "Artist",
            uri = "https://example.com/$value",
            status = "completed"
        )

    @Test
    fun savedEntryWithCompletedDownload_isSuperseded() {
        val saved = mapOf(metadata(11L, "audius", "abc"))
        val downloads = listOf(completedDownload("audius", "abc", trackId = 999L))

        assertEquals(setOf(11L), supersededSavedOnlineIds(saved, downloads))
    }

    @Test
    fun savedEntryWithoutDownload_isKept() {
        val saved = mapOf(
            metadata(11L, "audius", "abc"),
            metadata(22L, "audius", "def")
        )
        val downloads = listOf(completedDownload("audius", "abc", trackId = 999L))

        assertEquals(setOf(11L), supersededSavedOnlineIds(saved, downloads))
    }

    @Test
    fun downloadFromDifferentSource_doesNotSupersede() {
        val saved = mapOf(metadata(11L, "audius", "abc"))
        val downloads = listOf(completedDownload("soundcloud", "abc", trackId = 999L))

        assertTrue(supersededSavedOnlineIds(saved, downloads).isEmpty())
    }

    @Test
    fun unfinishedDownload_doesNotSupersede() {
        val saved = mapOf(metadata(11L, "audius", "abc"))
        val downloads = listOf(
            completedDownload("audius", "abc", trackId = 999L).copy(status = "downloading")
        )

        assertTrue(supersededSavedOnlineIds(saved, downloads).isEmpty())
    }

    @Test
    fun emptyInputs_returnNoSupersededIds() {
        assertTrue(supersededSavedOnlineIds(emptyMap(), emptyList()).isEmpty())
        assertTrue(
            supersededSavedOnlineIds(mapOf(metadata(11L, "audius", "abc")), emptyList()).isEmpty()
        )
    }

    @Test
    fun localCopySupersedesSavedEntryEvenWithoutDownloadJob() {
        // The download job record may have been removed while the file stays in
        // the library; the saved remote entry must still stay hidden.
        val saved = mapOf(
            metadata(11L, "audius", "abc"),
            metadata(22L, "audius", "def")
        )

        val superseded = supersededSavedOnlineIds(
            savedMetadata = saved,
            downloads = emptyList(),
            localCopySourceKeys = setOf("audius" to "abc")
        )

        assertEquals(setOf(11L), superseded)
    }

    @Test
    fun localCopyFromAnotherSourceDoesNotSupersede() {
        val saved = mapOf(metadata(11L, "audius", "abc"))

        val superseded = supersededSavedOnlineIds(
            savedMetadata = saved,
            downloads = emptyList(),
            localCopySourceKeys = setOf("soundcloud" to "abc")
        )

        assertTrue(superseded.isEmpty())
    }
}
