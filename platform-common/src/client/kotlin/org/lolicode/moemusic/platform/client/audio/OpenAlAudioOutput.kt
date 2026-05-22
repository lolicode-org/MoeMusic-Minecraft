package org.lolicode.moemusic.platform.client.audio

import org.lolicode.moemusic.clientcore.audio.PcmRingBuffer
import org.lolicode.moemusic.clientcore.audio.StreamingAudioOutput
import org.lwjgl.openal.AL10.*
import org.lwjgl.openal.AL11
import org.lwjgl.openal.ALC10.*
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import kotlin.math.roundToLong

/**
 * Streams PCM audio from a [PcmRingBuffer] to OpenAL using a pool of streaming buffers.
 *
 * **Thread model:** [start] spawns a single daemon thread (`MoeMusic-OpenAL`) that owns all
 * AL calls. [stop] / [seek] signal it to drain/flush and return.
 *
 * **OpenAL context:** must call [setContextHandle] once from the render thread
 * (e.g. via `MixinSoundEngine`) before [start] is ever called.
 *
 * Format: stereo, 16-bit signed, 48 000 Hz → `AL_FORMAT_STEREO16`.
 */
class OpenAlAudioOutput(
    private val ringBuffer: PcmRingBuffer,
    private val onPlaybackPositionChanged: (Long) -> Unit = {},
) : StreamingAudioOutput {

    private val logger = LoggerFactory.getLogger(OpenAlAudioOutput::class.java)

    @Volatile private var alSource = AL_NONE
    @Volatile private var streamThread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var desiredGain = 1.0f
    @Volatile private var requestedStartPositionMs = 0L

    /** Start streaming from [ringBuffer]. No-op if already running. */
    override fun start(startPositionMs: Long) {
        if (streamThread?.isAlive == true) return
        requestedStartPositionMs = startPositionMs.coerceAtLeast(0L)
        stopRequested = false
        streamThread = Thread({ streamLoop() }, "MoeMusic-OpenAL").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** Stop playback and release all AL resources. Safe to call from any thread. */
    override fun stop() {
        stopRequested = true
        streamThread?.interrupt()
        streamThread?.join(2_000)
        streamThread = null
    }

    /** Flush the ring buffer (seek invalidates buffered audio). */
    override fun seek() {
        ringBuffer.reset()
    }

    /** Update the desired OpenAL gain. Applied on the audio thread during streaming. */
    override fun setGain(gain: Float) {
        desiredGain = gain.coerceIn(0.0f, 1.0f)
    }

    // -------------------------------------------------------------------------
    // Internal — runs entirely on the MoeMusic-OpenAL thread
    // -------------------------------------------------------------------------

    private fun streamLoop() {
        // Make the Minecraft OpenAL context current on this thread
        val ctx = contextHandle
        if (ctx == 0L) {
            logger.error("OpenAL context handle not set; audio disabled.")
            return
        }
        alcMakeContextCurrent(ctx)

        // Allocate source and buffer pool
        val source = alGenSources()
        val buffers = IntArray(BUFFER_COUNT) { alGenBuffers() }
        alSource = source
        var appliedGain = Float.NaN
        var playedFrames = 0L
        var lastReportedPositionMs = requestedStartPositionMs
        val queuedFrameCounts = ArrayDeque<Int>(BUFFER_COUNT)
        val silentBuffer = ShortArray(BUFFER_FRAMES * CHANNELS)

        fun applyGainIfNeeded() {
            val targetGain = desiredGain
            if (appliedGain != targetGain) {
                alSourcef(source, AL_GAIN, targetGain)
                appliedGain = targetGain
            }
        }

        fun reportPlaybackPosition() {
            val headFrames = queuedFrameCounts.peekFirst()?.toLong() ?: 0L
            val headProgressFrames = if (headFrames > 0L && alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING) {
                (alGetSourcef(source, AL11.AL_SEC_OFFSET) * SAMPLE_RATE.toFloat()).roundToLong().coerceAtLeast(0L)
            } else {
                0L
            }
            val effectiveFrames = playedFrames + minOf(headProgressFrames, headFrames)
            lastReportedPositionMs = requestedStartPositionMs + (effectiveFrames * 1000L / SAMPLE_RATE)
            onPlaybackPositionChanged(lastReportedPositionMs)
        }

        // Prime all buffers with silence so the source stays alive while decode catches up.
        for (buf in buffers) {
            alBufferData(buf, AL_FORMAT_STEREO16, silentBuffer, SAMPLE_RATE)
            alSourceQueueBuffers(source, buf)
            queuedFrameCounts.addLast(0)
        }
        applyGainIfNeeded()
        alSourcePlay(source)
        reportPlaybackPosition()

        val scratch = ByteArray(BUFFER_FRAMES * CHANNELS * 2)
        val scratchShorts = java.nio.ByteBuffer.allocateDirect(scratch.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        try {
            while (!stopRequested && !Thread.currentThread().isInterrupted) {
                applyGainIfNeeded()
                val processed = alGetSourcei(source, AL_BUFFERS_PROCESSED)
                if (processed > 0) {
                    repeat(processed) {
                        val buf = IntArray(1)
                        alSourceUnqueueBuffers(source, buf)
                        val finishedFrames = if (queuedFrameCounts.isEmpty()) 0 else queuedFrameCounts.removeFirst()
                        playedFrames += finishedFrames.toLong()
                        val read = ringBuffer.read(scratch, 100)
                        if (read > 0) {
                            scratchShorts.clear()
                            for (i in 0 until read / 2) {
                                scratchShorts.put(
                                    ((scratch[i * 2].toInt() and 0xFF) or
                                    (scratch[i * 2 + 1].toInt() shl 8)).toShort()
                                )
                            }
                            scratchShorts.flip()
                            alBufferData(buf[0], AL_FORMAT_STEREO16, scratchShorts, SAMPLE_RATE)
                            queuedFrameCounts.addLast(read / BYTES_PER_FRAME)
                        } else {
                            alBufferData(buf[0], AL_FORMAT_STEREO16, silentBuffer, SAMPLE_RATE)
                            queuedFrameCounts.addLast(0)
                        }
                        alSourceQueueBuffers(source, buf[0])
                    }
                    if (alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
                        alSourcePlay(source)
                    }
                    reportPlaybackPosition()
                } else {
                    reportPlaybackPosition()
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }
        } finally {
            onPlaybackPositionChanged(lastReportedPositionMs)
            alSourceStop(source)
            alDeleteSources(source)
            alDeleteBuffers(buffers)
            alSource = AL_NONE
        }
    }

    companion object {
        /** Set from render thread via MixinSoundEngine before any playback starts. */
        @Volatile var contextHandle: Long = 0L
            private set

        /** Called from MixinSoundEngine (Java) to set the OpenAL context handle. */
        @JvmStatic
        fun setContextHandle(handle: Long) {
            contextHandle = handle
        }

        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val BUFFER_COUNT = 4
        private const val BYTES_PER_FRAME = CHANNELS * 2
        /** Frames per AL buffer: 20 ms at 48kHz = 960 frames. */
        private const val BUFFER_FRAMES = 960
    }
}
