package org.lolicode.moemusic.platform.client.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.core.protocol.proto.PlaybackControlAction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.ArrayDeque

/**
 * Guards automatic track skip actions triggered by local playback failures in single-player mode.
 *
 * Prevents flooding upstream music service APIs by:
 * 1. Enforcing a minimum delay ([delayMs]) before dispatching the skip action.
 * 2. Limiting the maximum number of auto-skips ([maxAutoSkips]) allowed within a rolling time window ([windowMs]).
 * 3. Cancelling any pending auto-skips if the user interacts with playback controls, disconnects,
 *    or if a new track begins playing.
 */
class SingleplayerAutoSkipGuard(
    private val isSingleplayer: () -> Boolean = {
        try {
            Minecraft.getInstance().singleplayerServer != null
        } catch (_: Throwable) {
            false
        }
    },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    val delayMs: Long = DEFAULT_DELAY_MS,
    val maxAutoSkips: Int = DEFAULT_MAX_AUTO_SKIPS,
    val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onSkipAction: () -> Unit = {
        try {
            val mc = Minecraft.getInstance()
            mc.execute {
                if (mc.singleplayerServer == null || mc.connection == null) return@execute
                ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.SKIP)
            }
        } catch (e: Throwable) {
            logger.warn("Failed to dispatch singleplayer auto-skip action: {}", e.message)
        }
    },
) {

    private val lock = Any()
    private val autoSkipTimestamps: ArrayDeque<Long> = ArrayDeque()
    private var pendingJob: Job? = null
    private var pendingTrackId: String? = null

    /**
     * Called when a track fails playback permanently on the client.
     *
     * If currently in single-player mode and within the auto-skip rate limit,
     * schedules an auto-skip action to be dispatched after [delayMs].
     *
     * @param track the failed track information
     * @return `true` if an auto-skip was scheduled, `false` if rejected or ignored
     */
    fun onPlaybackFailure(track: TrackInfo): Boolean {
        if (!isSingleplayer()) {
            return false
        }

        synchronized(lock) {
            val now = clock()
            trimTimestampsLocked(now)

            if (autoSkipTimestamps.size >= maxAutoSkips) {
                logger.warn(
                    "Auto-skip limit reached ({} skips in {}s); suppressing auto-skip for track '{}' ({}) to avoid flooding upstream API",
                    maxAutoSkips,
                    windowMs / 1000,
                    track.title.ifBlank { track.id },
                    track.id,
                )
                cancelPendingLocked()
                return false
            }

            cancelPendingLocked()
            val trackId = track.id
            pendingTrackId = trackId
            pendingJob = scope.launch {
                delay(delayMs)
                executeSkip(trackId)
            }
            logger.debug(
                "Scheduled auto-skip for failed track '{}' in {} ms",
                track.title.ifBlank { track.id },
                delayMs,
            )
            return true
        }
    }

    private fun executeSkip(expectedTrackId: String) {
        synchronized(lock) {
            if (pendingTrackId != expectedTrackId) {
                return
            }
            if (!isSingleplayer()) {
                cancelPendingLocked()
                return
            }
            val now = clock()
            trimTimestampsLocked(now)
            if (autoSkipTimestamps.size >= maxAutoSkips) {
                logger.warn(
                    "Auto-skip limit reached before execution ({} skips in {}s); auto-skip aborted for track id '{}'",
                    maxAutoSkips,
                    windowMs / 1000,
                    expectedTrackId,
                )
                cancelPendingLocked()
                return
            }
            autoSkipTimestamps.addLast(now)
            cancelPendingLocked()
        }
        logger.info("Executing auto-skip after local playback failure for track id '{}'", expectedTrackId)
        onSkipAction()
    }

    /**
     * Cancels any pending delayed auto-skip task.
     *
     * Should be called on user playback control, new track playback start, or context clear.
     *
     * @return `true` if a pending auto-skip was cancelled, `false` otherwise
     */
    fun cancelPending(): Boolean {
        synchronized(lock) {
            val wasPending = pendingJob != null
            cancelPendingLocked()
            return wasPending
        }
    }

    /**
     * Resets the guard, cancelling any pending auto-skip and clearing recorded skip timestamps.
     *
     * Should be called when disconnecting from a world / server or when playback recovers.
     */
    fun reset() {
        synchronized(lock) {
            cancelPendingLocked()
            autoSkipTimestamps.clear()
        }
    }

    /**
     * Returns whether an auto-skip task is currently pending delay completion.
     */
    val isPending: Boolean
        get() = synchronized(lock) { pendingJob != null }

    /**
     * Returns the count of auto-skips executed within the current sliding time window.
     */
    val skipCountInWindow: Int
        get() = synchronized(lock) {
            trimTimestampsLocked(clock())
            autoSkipTimestamps.size
        }

    private fun cancelPendingLocked() {
        pendingJob?.cancel()
        pendingJob = null
        pendingTrackId = null
    }

    private fun trimTimestampsLocked(now: Long) {
        val cutoff = now - windowMs
        while (autoSkipTimestamps.isNotEmpty() && autoSkipTimestamps.first() <= cutoff) {
            autoSkipTimestamps.removeFirst()
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(SingleplayerAutoSkipGuard::class.java)

        /** Default delay in milliseconds before dispatching an auto-skip action (1.5 seconds). */
        const val DEFAULT_DELAY_MS: Long = 1_500L

        /** Default maximum number of auto-skips allowed within the sliding window. */
        const val DEFAULT_MAX_AUTO_SKIPS: Int = 5

        /** Default sliding window duration in milliseconds (30 seconds). */
        const val DEFAULT_WINDOW_MS: Long = 30_000L
    }
}
