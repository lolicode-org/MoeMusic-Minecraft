package org.lolicode.moemusic.platform.client.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.lolicode.moemusic.api.model.TrackInfo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleplayerAutoSkipGuardTest {

    private lateinit var scope: CoroutineScope
    private var isSingleplayer = true
    private var currentTime = 1_000L
    private var skipCount = 0
    private val skippedTracks = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        isSingleplayer = true
        currentTime = 1_000L
        skipCount = 0
        skippedTracks.clear()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun createGuard(
        delayMs: Long = 100L,
        maxAutoSkips: Int = 3,
        windowMs: Long = 1_000L,
    ): SingleplayerAutoSkipGuard = SingleplayerAutoSkipGuard(
        isSingleplayer = { isSingleplayer },
        scope = scope,
        delayMs = delayMs,
        maxAutoSkips = maxAutoSkips,
        windowMs = windowMs,
        clock = { currentTime },
        onSkipAction = {
            skipCount++
        },
    )

    private fun dummyTrack(id: String): TrackInfo = TrackInfo(
        id = id,
        title = "Track $id",
        artists = emptyList(),
        durationMs = 180_000L,
    ) {
        sourceId = "test"
    }

    @Test
    fun `auto skip is ignored when not in singleplayer`() {
        isSingleplayer = false
        val guard = createGuard()

        val scheduled = guard.onPlaybackFailure(dummyTrack("track-1"))

        assertFalse(scheduled)
        assertFalse(guard.isPending)
        assertEquals(0, skipCount)
    }

    @Test
    fun `auto skip schedules delayed skip and fires when delay elapses`() = kotlinx.coroutines.runBlocking {
        val guard = createGuard(delayMs = 20L)

        val scheduled = guard.onPlaybackFailure(dummyTrack("track-1"))

        assertTrue(scheduled)
        kotlinx.coroutines.delay(100L)
        assertEquals(1, skipCount)
        assertEquals(1, guard.skipCountInWindow)
        assertFalse(guard.isPending)
    }

    @Test
    fun `cancelPending cancels pending auto-skip before it fires`() {
        // delayMs = 50_000L so coroutine delay suspends
        val guard = createGuard(delayMs = 50_000L)

        val scheduled = guard.onPlaybackFailure(dummyTrack("track-1"))
        assertTrue(scheduled)
        assertTrue(guard.isPending)

        val cancelled = guard.cancelPending()
        assertTrue(cancelled)
        assertFalse(guard.isPending)
        assertEquals(0, skipCount)
    }

    @Test
    fun `rate limiter suppresses auto-skips after reaching max auto skips within window`() {
        val guard = createGuard(delayMs = 0L, maxAutoSkips = 3, windowMs = 5_000L)

        // First 3 failures should successfully auto-skip
        assertTrue(guard.onPlaybackFailure(dummyTrack("t1")))
        assertEquals(1, skipCount)

        currentTime += 100L
        assertTrue(guard.onPlaybackFailure(dummyTrack("t2")))
        assertEquals(2, skipCount)

        currentTime += 100L
        assertTrue(guard.onPlaybackFailure(dummyTrack("t3")))
        assertEquals(3, skipCount)
        assertEquals(3, guard.skipCountInWindow)

        // 4th failure within the same window must be suppressed
        currentTime += 100L
        assertFalse(guard.onPlaybackFailure(dummyTrack("t4")))
        assertEquals(3, skipCount, "4th auto-skip should be suppressed by rate limit")
        assertEquals(3, guard.skipCountInWindow)

        // 5th failure also suppressed
        assertFalse(guard.onPlaybackFailure(dummyTrack("t5")))
        assertEquals(3, skipCount)
    }

    @Test
    fun `auto-skips are permitted again after rolling time window expires`() {
        val guard = createGuard(delayMs = 0L, maxAutoSkips = 2, windowMs = 1_000L)

        // Exhaust 2 auto-skips at t = 1_000
        assertTrue(guard.onPlaybackFailure(dummyTrack("t1")))
        assertTrue(guard.onPlaybackFailure(dummyTrack("t2")))
        assertEquals(2, skipCount)

        // At t = 1_500, still within 1_000ms window: suppressed
        currentTime = 1_500L
        assertFalse(guard.onPlaybackFailure(dummyTrack("t3")))
        assertEquals(2, skipCount)

        // Advance time past the 1_000ms window (e.g. t = 2_100L)
        currentTime = 2_100L
        assertEquals(0, guard.skipCountInWindow)

        // Now a new failure should be allowed to auto-skip
        assertTrue(guard.onPlaybackFailure(dummyTrack("t4")))
        assertEquals(3, skipCount)
        assertEquals(1, guard.skipCountInWindow)
    }

    @Test
    fun `reset clears both pending skip and recorded timestamps`() {
        val guard = createGuard(delayMs = 0L, maxAutoSkips = 1, windowMs = 5_000L)

        assertTrue(guard.onPlaybackFailure(dummyTrack("t1")))
        assertEquals(1, guard.skipCountInWindow)

        // Another failure is blocked
        assertFalse(guard.onPlaybackFailure(dummyTrack("t2")))

        // Reset clears history
        guard.reset()
        assertEquals(0, guard.skipCountInWindow)
        assertFalse(guard.isPending)

        // Now failures are permitted again
        assertTrue(guard.onPlaybackFailure(dummyTrack("t3")))
        assertEquals(2, skipCount)
    }
}
