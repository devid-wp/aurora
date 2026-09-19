package com.aurora.app

import androidx.test.core.app.ApplicationProvider
import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.RecentlyPlayedEntity
import com.aurora.app.database.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentlyPlayedDaoTest {
    private val db: AuroraDatabase = AuroraDatabase.inMemory(ApplicationProvider.getApplicationContext())

    @Test
    fun recently_played_tracks_are_sorted_by_time() {
        db.trackDao().upsert(
            TrackEntity(
                id = 7L,
                title = "Song 1",
                artist = "Artist",
                album = "Album",
                durationMs = 123000L,
                uri = "content://audio/recent/7"
            )
        )
        db.trackDao().upsert(
            TrackEntity(
                id = 8L,
                title = "Song 2",
                artist = "Artist",
                album = "Album",
                durationMs = 124000L,
                uri = "content://audio/recent/8"
            )
        )

        db.recentlyPlayedDao().insert(RecentlyPlayedEntity(trackId = 7L, playedAt = 100L))
        db.recentlyPlayedDao().insert(RecentlyPlayedEntity(trackId = 8L, playedAt = 200L))

        val latest = db.recentlyPlayedDao().getRecent(10)
        assertEquals(2, latest.size)
        assertEquals(8L, latest.first().trackId)
    }
}
