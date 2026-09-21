package com.aurora.app

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Unit tests for the pure queue/navigation engine behind the player: repeat
 * OFF/ALL/ONE, shuffle next/previous, and queue consistency. No MediaPlayer or
 * Service is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackQueueTest {

    private fun track(id: Long): Track = Track(
        id = id,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        duration = 100_000L,
        uri = Uri.parse("file:///track-$id.mp3"),
        albumId = 0L
    )

    private fun queueOf(count: Int, seed: Int = 42): PlaybackQueue {
        val queue = PlaybackQueue(Random(seed))
        queue.setQueue((1L..count).map(::track), 0)
        return queue
    }

    @Test
    fun repeatOff_advances_then_stopsAtEnd() {
        val queue = queueOf(3)

        assertEquals(2L, queue.next(false)?.id)
        assertEquals(3L, queue.next(false)?.id)
        assertNull("repeat OFF must stop after the last track", queue.next(false))
        // The cursor stays on the last track so the UI keeps showing it.
        assertEquals(2, queue.index)
    }

    @Test
    fun repeatAll_wrapsToStart() {
        val queue = queueOf(3)
        queue.setRepeatMode(RepeatMode.ALL)

        assertEquals(2L, queue.next(false)?.id)
        assertEquals(3L, queue.next(false)?.id)
        assertEquals("repeat ALL must wrap to the first track", 1L, queue.next(false)?.id)
    }

    @Test
    fun repeatOne_repeatsCurrentTrackOnCompletion() {
        val queue = queueOf(3)
        assertEquals(2L, queue.next(false)?.id)          // move to track 2
        queue.setRepeatMode(RepeatMode.ONE)

        repeat(3) {
            val replay = queue.onCompleted()
            assertEquals("repeat ONE must replay the same track", 2L, replay?.id)
            assertEquals(1, queue.index)
        }
    }

    @Test
    fun repeatOne_manualNextStillAdvances() {
        val queue = queueOf(3)
        queue.setRepeatMode(RepeatMode.ONE)

        val next = queue.next(userInitiated = true)
        assertEquals("a manual Next must advance even in repeat ONE", 2L, next?.id)
    }

    @Test
    fun repeatOff_manualNextAtEndStops() {
        val queue = queueOf(2)
        queue.next(true)
        assertNull(queue.next(true))
    }

    @Test
    fun shuffle_visitsEveryTrackBeforeRepeating() {
        val queue = queueOf(5)
        queue.setShuffleEnabled(true)
        queue.setRepeatMode(RepeatMode.ALL)

        val visited = mutableListOf(queue.index)
        repeat(4) {
            val next = queue.next(false)
            assertNotNull(next)
            visited.add(queue.index)
        }
        assertEquals("shuffle must cover every track in a full pass", 5, visited.toSet().size)
        assertNotNull("repeat ALL must wrap after a full shuffled pass", queue.next(false))
    }

    @Test
    fun shuffle_previousReturnsRealPlayHistory() {
        val queue = queueOf(5, seed = 7)
        queue.setShuffleEnabled(true)

        val first = queue.next(false)!!.id
        val second = queue.next(false)!!.id

        assertEquals(second, queue.current?.id)
        assertEquals("Previous must return to the previously played track", first, queue.previous()?.id)
    }

    @Test
    fun previous_beforeStartReturnsCurrentForRestart() {
        val queue = queueOf(3)
        assertEquals(1L, queue.previous()?.id)
        assertEquals(0, queue.index)
    }

    @Test
    fun previous_stepsBackInOrder() {
        val queue = queueOf(3)
        queue.next(true)
        queue.next(true)
        assertEquals(3L, queue.current?.id)
        assertEquals(2L, queue.previous()?.id)
    }

    @Test
    fun enqueue_keepsQueueConsistentAndDeduplicates() {
        val queue = PlaybackQueue(Random(1))
        queue.setQueue(listOf(track(1)), 0)

        assertTrue(queue.enqueue(track(2)))
        assertTrue(queue.enqueue(track(3)))
        assertFalse("duplicate enqueue must not change the queue", queue.enqueue(track(2)))
        assertEquals(listOf(1L, 2L, 3L), queue.tracks.map { it.id })
        assertEquals(0, queue.index)
    }

    @Test
    fun enqueue_onEmptyQueueBecomesCurrent() {
        val queue = PlaybackQueue()
        assertTrue(queue.isEmpty)
        assertTrue(queue.enqueue(track(9)))
        assertEquals(9L, queue.current?.id)
        assertEquals(0, queue.index)
    }

    @Test
    fun setQueueToTrack_matchesByIdentityThenId() {
        val one = track(1)
        val two = track(2)
        val queue = PlaybackQueue()

        queue.setQueueToTrack(two, listOf(one, two))
        assertEquals(1, queue.index)
        assertSame(two, queue.current)

        // A different uri with the same id still anchors correctly.
        val twoOtherUri = two.copy(uri = Uri.parse("file:///other.mp3"))
        queue.setQueueToTrack(twoOtherUri, listOf(one, two))
        assertEquals(1, queue.index)
    }

    @Test
    fun clear_resetsEverything() {
        val queue = queueOf(3)
        queue.next(true)
        queue.clear()
        assertTrue(queue.isEmpty)
        assertEquals(-1, queue.index)
        assertNull(queue.current)
    }

    // ── Shuffle guarantees ────────────────────────────────────────────────

    @Test
    fun shuffle_enablingDoesNotImmediatelyRepeatCurrent() {
        val queue = queueOf(6, seed = 3)
        queue.next(true) // move away from the first track
        val currentId = queue.current!!.id

        queue.setShuffleEnabled(true)
        repeat(5) {
            val next = queue.next(true)!!
            assertNotEquals("shuffle must not replay the current track first", currentId, next.id)
        }
    }

    @Test
    fun shuffle_neverRepeatsBeforePoolExhausted() {
        val queue = queueOf(5, seed = 11)
        val startId = queue.current!!.id
        queue.setShuffleEnabled(true)

        val visited = mutableListOf<Long>()
        while (true) {
            val next = queue.next(userInitiated = true) ?: break
            visited.add(next.id)
        }

        assertEquals("a full shuffle pass must visit every other track exactly once", 4, visited.size)
        assertEquals("no duplicates before the pool is exhausted", visited.size, visited.toSet().size)
        assertFalse("the starting track must not repeat within one pass", visited.contains(startId))
    }

    @Test
    fun shuffle_isDeterministicForTheSameSeed() {
        val first = queueOf(8, seed = 42).apply { setShuffleEnabled(true) }
        val second = queueOf(8, seed = 42).apply { setShuffleEnabled(true) }

        val a = (1..7).map { first.next(true)!!.id }
        val b = (1..7).map { second.next(true)!!.id }

        assertEquals("an injected seeded Random must make shuffle deterministic", a, b)
    }

    @Test
    fun shuffle_doesNotDestroyQueueOrder() {
        val queue = queueOf(5, seed = 7)
        val original = queue.tracks.map { it.id }
        queue.setShuffleEnabled(true)
        repeat(4) { queue.next(true) }

        assertEquals("the UI queue order must stay intact while shuffling", original, queue.tracks.map { it.id })
        assertTrue("shuffle must stay enabled after navigation", queue.shuffleEnabled)
    }

    @Test
    fun shuffle_repeatOneStillReplaysCurrentOnCompletion() {
        val queue = queueOf(5, seed = 5)
        queue.setShuffleEnabled(true)
        queue.next(true)
        val currentId = queue.current!!.id
        val indexBefore = queue.index
        queue.setRepeatMode(RepeatMode.ONE)

        val replay = queue.onCompleted()

        assertEquals(currentId, replay?.id)
        assertEquals("repeat ONE must not advance the cursor", indexBefore, queue.index)
    }

    @Test
    fun shuffle_manualNextStillAdvancesInRepeatOne() {
        val queue = queueOf(5, seed = 5)
        queue.setShuffleEnabled(true)
        queue.setRepeatMode(RepeatMode.ONE)
        val before = queue.current!!.id

        val next = queue.next(userInitiated = true)

        assertNotNull(next)
        assertNotEquals("a manual Next must advance even in shuffle + repeat ONE", before, next!!.id)
    }

    @Test
    fun shuffle_remembersHistoryForPrevious() {
        val queue = queueOf(6, seed = 21)
        queue.setShuffleEnabled(true)
        val first = queue.next(true)!!.id
        val second = queue.next(true)!!.id

        assertEquals("Previous must return to the actually played track", second, queue.current!!.id)
        assertEquals(first, queue.previous()!!.id)
    }

    // ── Upcoming (shuffle/repeat-aware "Up Next") ─────────────────────────

    @Test
    fun upcoming_matchesTheRealShufflePlayOrder() {
        val queue = queueOf(6, seed = 9)
        queue.setShuffleEnabled(true)

        val predicted = queue.upcoming(4).map { it.id }
        val actual = (1..4).map { queue.next(true)!!.id }

        assertEquals(predicted, actual)
    }

    @Test
    fun upcoming_repeatOneReportsCurrentTrack() {
        val queue = queueOf(4)
        queue.next(true)
        queue.setRepeatMode(RepeatMode.ONE)

        assertEquals(listOf(queue.current!!.id), queue.upcoming(5).map { it.id })
    }

    @Test
    fun upcoming_repeatAllWrapsInOrder() {
        val queue = queueOf(3)
        queue.next(true) // index 1
        queue.setRepeatMode(RepeatMode.ALL)

        assertEquals(listOf(3L, 1L, 2L), queue.upcoming(3).map { it.id })
    }

    @Test
    fun upcoming_repeatOffStopsAtEndOfQueue() {
        val queue = queueOf(3)
        queue.next(true)
        queue.next(true) // last track

        assertTrue(queue.upcoming(5).isEmpty())
    }

    // ── Queue maintenance ─────────────────────────────────────────────────

    @Test
    fun removeAt_keepsCursorAndQueueConsistent() {
        val queue = queueOf(4) // 1,2,3,4
        queue.next(true)       // current: track 2 at index 1

        queue.removeAt(3)      // remove trailing track 4
        assertEquals(listOf(1L, 2L, 3L), queue.tracks.map { it.id })
        assertEquals(2L, queue.current?.id)

        queue.removeAt(0)      // remove track 1, before the cursor
        assertEquals(2L, queue.current?.id)

        queue.removeAt(0)      // remove the current track -> hand over to next
        assertEquals(3L, queue.current?.id)
    }

    @Test
    fun enqueueUnderShuffle_staysReachableInTheShuffledOrder() {
        val queue = queueOf(3, seed = 5)
        queue.setShuffleEnabled(true)
        assertTrue(queue.enqueue(track(99)))

        val seen = mutableSetOf<Long>()
        while (true) {
            val next = queue.next(true) ?: break
            seen.add(next.id)
        }

        assertTrue("a track enqueued under shuffle must eventually play", seen.contains(99L))
    }

    @Test
    fun previous_stillWorksAfterALongRepeatAllSession() {
        val queue = queueOf(3)
        queue.setRepeatMode(RepeatMode.ALL)
        repeat(600) { queue.next(true) }

        // History is bounded, but the immediately preceding track is still known.
        assertEquals(3L, queue.previous()?.id)
    }
}
