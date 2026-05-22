package org.lolicode.moemusic.platform.client.audio

import net.minecraft.client.sounds.SoundEngine
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.clientcore.audio.ClientAudioPlayerRuntime
import org.lolicode.moemusic.clientcore.audio.LavaPlayerTrackLoader
import org.lolicode.moemusic.clientcore.audio.PcmRingBuffer
import org.lolicode.moemusic.client.mixin.MixinSoundEngine

/**
 * Platform adapter that wires the shared [ClientAudioPlayerRuntime] to the OpenAL backend.
 *
 * The actual playback orchestration lives in `:client-core`; this object stays in
 * `:platform-common` only to supply the Minecraft-owned audio sink.
 */
object ClientAudioPlayer {

    private val ringBuffer = PcmRingBuffer()
    private val loader = LavaPlayerTrackLoader()
    private val output: OpenAlAudioOutput
    private val runtime: ClientAudioPlayerRuntime

    init {
        output = OpenAlAudioOutput(ringBuffer) { runtime.reportPlaybackPosition(it) }
        runtime = ClientAudioPlayerRuntime(
            ringBuffer = ringBuffer,
            loader = loader,
            output = output,
        )
    }

    @get:JvmName("isPlaying")
    val isPlaying: Boolean
        get() = runtime.isPlaying

    val volume: Float
        get() = runtime.volume

    /**
     * Load [playback] and start playback from [seekMs].
     *
     * Stops any current playback first. If [playback] fails to load, [onError] is called.
     */
    fun play(playback: PlaybackResource, seekMs: Long = 0, onError: (String) -> Unit = {}) =
        runtime.play(playback, seekMs, onError)

    /** Pause playback (freezes audio output; decoder continues buffering). */
    fun pause() = runtime.pause()

    /** Resume from current position. */
    fun resume() = runtime.resume()

    /**
     * Seek to [positionMs] in the current track.
     * Flushes the ring buffer and instructs the loader to seek the decoder.
     */
    fun seek(positionMs: Long) = runtime.seek(positionMs)

    /** Stop all playback and release resources. Blocks until the decode thread exits. */
    fun stop() = runtime.stop()

    /**
     * Called by [MixinSoundEngine] at the HEAD of [SoundEngine.reload] / [SoundEngine.destroy]
     * **before** [stop] clears state. Saves the current playback resource and computed seek
     * position so [restoreAfterReload] can resume playback after the new OpenAL context is ready.
     */
    fun saveStateForReload() = runtime.saveStateForReload()

    /**
     * Called by [MixinSoundEngine] at the TAIL of [SoundEngine.reload] after the new OpenAL
     * context is current. Restores playback using the state saved by [saveStateForReload].
     *
     * Works regardless of whether the ALC context handle changed (handles PipeWire/PulseAudio
     * where the handle is stable even across device switches).
     */
    fun restoreAfterReload() = runtime.restoreAfterReload()

    /** Discard any saved-for-reload state. Call on server DISCONNECT so that the next
     *  [SoundEngine.reload] (triggered by world change) does NOT restore pre-disconnect audio. */
    fun clearSavedState() = runtime.clearSavedState()

    fun currentPositionMs(): Long = runtime.currentPositionMs()

    /** Set client-local playback volume in the 0.0 .. 1.0 range. */
    fun setVolume(value: Float) = runtime.setVolume(value)
}
