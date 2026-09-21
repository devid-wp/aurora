package com.aurora.app

import kotlin.random.Random

/**
 * Repeat modes exposed by the player UI.
 *
 *  - [OFF] never repeats; the queue stops on the last track.
 *  - [ALL] repeats the whole queue, wrapping at the end.
 *  - [ONE] repeats the current track when it finishes; a manual Next still
 *    advances to the next track.
 */
enum class RepeatMode { OFF, ALL, ONE }

/**
 * Pure, Android-free queue and navigation logic for the player.
 *
 * The queue is kept in its original order (never mutated by shuffle); shuffle
 * is tracked through a separate order plus a small history so that Previous
 * returns to the track that was actually played before. This keeps the queue
 * shown in the UI consistent with playback at all times and makes the tricky
 * next/previous/repeat behavior unit-testable without a MediaPlayer.
 */
class PlaybackQueue(private val random: Random = Random.Default) {

    private val items = mutableListOf<Track>()
    private val shuffleOrder = mutableListOf<Int>()
    private val history = mutableListOf<Int>()
    private var shufflePos = -1

    private companion object {
        /**
         * Upper bound on the back-history used by [previous]. Playback can run
         * for hours under repeat-ALL; without a bound the history list would
         * grow for every track played. Only the most recent entries are needed
         * to step backwards.
         */
        const val MAX_HISTORY = 256
    }

    /** Ordered queue exactly as shown in the UI. */
    val tracks: List<Track> get() = items

    /** Index of the current track in [tracks], or -1 when the queue is empty. */
    var index: Int = -1
        private set

    var shuffleEnabled: Boolean = false
        private set

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    val current: Track?
        get() = items.getOrNull(index)

    val isEmpty: Boolean get() = items.isEmpty()

    /** Replaces the queue and anchors it on [startIndex] (clamped). */
    fun setQueue(tracks: List<Track>, startIndex: Int) {
        items.clear()
        items.addAll(tracks)
        index = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.lastIndex)
        resetNavigation()
    }

    /**
     * Replaces the queue and anchors it on [track], matching by identity
     * (id + uri) first and by id as a fallback. Never silently anchors on the
     * wrong duplicate.
     */
    fun setQueueToTrack(track: Track, tracks: List<Track>) {
        val exact = tracks.indexOfFirst { it.id == track.id && it.uri == track.uri }
        val byId = if (exact >= 0) exact else tracks.indexOfFirst { it.id == track.id }
        setQueue(tracks, if (byId >= 0) byId else 0)
    }

    /**
     * Appends [track] when it is not already queued. Returns true when the
     * queue changed. An enqueue on an empty queue makes the track current.
     */
    fun enqueue(track: Track): Boolean {
        if (items.any { sameTrack(it, track) }) return false
        val wasEmpty = items.isEmpty()
        items.add(track)
        if (wasEmpty) {
            index = 0
            resetNavigation()
        } else if (shuffleEnabled) {
            // Keep the new entry reachable in the shuffled order.
            shuffleOrder.add(items.lastIndex)
        }
        return true
    }

    fun clear() {
        items.clear()
        index = -1
        resetNavigation()
    }

    /**
     * Jumps straight to the track at [newIndex] (a tap in the Queue screen).
     * The queue order itself never changes, so the UI stays consistent.
     *
     * @return the new current track, or null when the index is out of range.
     */
    fun playAt(newIndex: Int): Track? {
        if (items.isEmpty() || newIndex !in items.indices) return null
        index = newIndex
        history.clear()
        history.add(index)
        syncShufflePosition(index)
        return current
    }

    /**
     * Removes the track at [position] and repairs the cursor, shuffle order and
     * history so the queue stays playable.
     *
     * Removing the current track hands playback to the following entry (or the
     * previous one when the last entry was removed); removing any other track
     * leaves the current track untouched.
     *
     * @return the removed track, or null when the position is out of range.
     */
    fun removeAt(position: Int): Track? {
        if (position !in items.indices) return null
        val removed = items.removeAt(position)
        if (items.isEmpty()) {
            index = -1
            resetNavigation()
            return removed
        }
        index = when {
            position < index -> index - 1
            position > index -> index
            else -> position.coerceAtMost(items.lastIndex)
        }
        resetNavigation()
        return removed
    }

    /**
     * The next tracks that will actually play, in real play order — shuffle and
     * repeat aware — without mutating any state. Used by the "Up next" list.
     *
     * Honest limits: under shuffle the order after the current pass is random,
     * and under [RepeatMode.ONE] the current track simply replays, so at most
     * one entry is reported for those cases.
     */
    fun upcoming(limit: Int): List<Track> {
        if (limit <= 0 || items.isEmpty() || index !in items.indices) return emptyList()
        if (repeatMode == RepeatMode.ONE) return listOf(items[index])

        val ordered: List<Track> = if (shuffleEnabled) {
            val remaining = if (shufflePos in shuffleOrder.indices) {
                shuffleOrder.drop(shufflePos + 1)
            } else {
                emptyList()
            }
            remaining.mapNotNull { items.getOrNull(it) }
        } else {
            val tail = items.drop(index + 1)
            if (repeatMode == RepeatMode.ALL && tail.size < limit) {
                tail + items.take((limit - tail.size).coerceAtMost(items.size))
            } else {
                tail
            }
        }
        return ordered.take(limit)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        if (shuffleEnabled == enabled) return
        shuffleEnabled = enabled
        resetNavigation()
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
    }

    /**
     * Advances to the next track.
     *
     * @param userInitiated true for a skip button, false for natural track
     *   completion. Only natural completion honors [RepeatMode.ONE]; a manual
     *   next always advances.
     * @return the next track, or null when playback must stop (repeat OFF at
     *   the end of the queue / shuffled order).
     */
    fun next(userInitiated: Boolean): Track? {
        if (items.isEmpty()) return null
        if (repeatMode == RepeatMode.ONE && !userInitiated) return current

        if (shuffleEnabled) {
            if (shufflePos < shuffleOrder.lastIndex) {
                shufflePos++
                index = shuffleOrder[shufflePos]
            } else if (repeatMode == RepeatMode.ALL) {
                // A full pass finished: reshuffle the rest, never replaying the
                // current track immediately.
                val others = items.indices.filter { it != index }.shuffled(random)
                shuffleOrder.clear()
                shuffleOrder.addAll(others)
                if (shuffleOrder.isEmpty()) {
                    // Single-item queue: wrap onto itself.
                    shufflePos = 0
                    shuffleOrder.add(index)
                } else {
                    shufflePos = 0
                    index = shuffleOrder[0]
                }
            } else {
                return null
            }
        } else {
            if (index < items.lastIndex) {
                index++
            } else if (repeatMode == RepeatMode.ALL) {
                index = 0
            } else {
                return null
            }
        }
        recordHistory(index)
        return current
    }

    /** Appends to the back-history, dropping the oldest entry past the cap. */
    private fun recordHistory(position: Int) {
        history.add(position)
        if (history.size > MAX_HISTORY) history.removeAt(0)
    }

    /**
     * Steps to the previously played track. Returns the current track when
     * there is nothing before it (the caller then restarts from position 0).
     */
    fun previous(): Track? {
        if (items.isEmpty()) return null

        // Preferred path: walk back through the real play history. This makes
        // Previous correct under shuffle too.
        if (history.size >= 2) {
            history.removeAt(history.lastIndex)
            index = history.last()
            syncShufflePosition(index)
            return current
        }

        if (shuffleEnabled) {
            if (shufflePos > 0) {
                shufflePos--
                index = shuffleOrder[shufflePos]
                history.clear()
                history.add(index)
                return current
            }
            return current
        }

        if (index > 0) {
            index--
            history.clear()
            history.add(index)
            return current
        }
        if (repeatMode == RepeatMode.ALL) {
            index = items.lastIndex
            history.clear()
            history.add(index)
            return current
        }
        return current
    }

    /** Natural end-of-track handling: repeat ONE replays, otherwise advance. */
    fun onCompleted(): Track? =
        if (repeatMode == RepeatMode.ONE) current else next(userInitiated = false)

    private fun resetNavigation() {
        history.clear()
        if (index >= 0) history.add(index)
        rebuildShuffle(anchor = index)
    }

    private fun rebuildShuffle(anchor: Int) {
        shuffleOrder.clear()
        if (items.isEmpty()) {
            shufflePos = -1
            return
        }
        val safeAnchor = anchor.coerceIn(0, items.lastIndex)
        shuffleOrder.add(safeAnchor)
        shuffleOrder.addAll(items.indices.filter { it != safeAnchor }.shuffled(random))
        shufflePos = 0
    }

    private fun syncShufflePosition(target: Int) {
        val pos = shuffleOrder.indexOf(target)
        if (pos >= 0) shufflePos = pos
    }

    private fun sameTrack(a: Track, b: Track): Boolean = a.id == b.id && a.uri == b.uri
}
