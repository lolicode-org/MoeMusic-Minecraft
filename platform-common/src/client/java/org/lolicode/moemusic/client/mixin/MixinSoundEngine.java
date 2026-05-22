package org.lolicode.moemusic.client.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.lwjgl.openal.ALC10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lolicode.moemusic.platform.client.audio.ClientAudioPlayer;
import org.lolicode.moemusic.platform.client.audio.OpenAlAudioOutput;
import org.lolicode.moemusic.platform.client.audio.VanillaSoundBlocker;

@SuppressWarnings("UnusedMixin")
@Mixin(SoundEngine.class)
public class MixinSoundEngine {

    /**
     * Prevent selected vanilla sound categories from starting while MoeMusic is actively
     * playing on this client.
     */
    @Inject(
            method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onPlay(SoundInstance instance, CallbackInfo ci) {
        if (VanillaSoundBlocker.shouldBlock(instance.getSource())) {
            ci.cancel();
        }
    }

    /**
     * Before the SoundEngine tears down its OpenAL resources, snapshot the current
     * playback position so we can resume after the engine is re-created.
     * <p>
     * This fires at the HEAD of both {@code reload()} (which calls destroy internally
     * on some MC versions) and {@code destroy()} (game shutdown / sound settings reload).
     * Saving twice is harmless — the second save overwrites the first with a slightly
     * later position, which is fine.
     */
    @Inject(method = "reload", at = @At("HEAD"))
    private void onBeforeReload(CallbackInfo ci) {
        ClientAudioPlayer.INSTANCE.saveStateForReload();
    }

    /**
     * After reload() has finished and the new OpenAL context is current on this thread,
     * capture the handle and restore any audio that was playing before the reload.
     * <p>
     * This approach works regardless of whether the ALC handle changed (PipeWire/PulseAudio
     * on Linux reuses the same handle even when the physical device changes, so comparing
     * old vs. new handle is unreliable).
     */
    @Inject(method = "reload", at = @At("TAIL"))
    private void onAfterReload(CallbackInfo ci) {
        long ctx = ALC10.alcGetCurrentContext();
        if (ctx != 0L) {
            OpenAlAudioOutput.Companion.setContextHandle(ctx);
        }
        ClientAudioPlayer.INSTANCE.restoreAfterReload();
        VanillaSoundBlocker.stopBlockedSoundsIfNeeded();
    }

    /**
     * Stop our audio player cleanly when Minecraft's sound engine is destroyed
     * (game shutdown). Save state first in case this is part of a reload cycle.
     */
    @Inject(method = "destroy", at = @At("HEAD"))
    private void onDestroy(CallbackInfo ci) {
        ClientAudioPlayer.INSTANCE.saveStateForReload();
        ClientAudioPlayer.INSTANCE.stop();
    }
}
