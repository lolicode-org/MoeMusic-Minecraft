package org.lolicode.moemusic.platform.client.audio

import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundSource
import org.lolicode.moemusic.core.config.ModConfigManager

/**
 * Applies client-local suppression rules for vanilla sound categories while MoeMusic is active.
 */
object VanillaSoundBlocker {

    @JvmStatic
    fun shouldBlock(source: SoundSource): Boolean {
        if (!ClientAudioPlayer.isPlaying) return false

        val clientConfig = ModConfigManager.config.client
        return when (source) {
            SoundSource.MUSIC -> clientConfig.blockVanillaMusic
            SoundSource.RECORDS -> clientConfig.blockRecords
            else -> false
        }
    }

    /**
     * Stops currently active vanilla sounds in blocked categories.
     *
     * This complements the SoundEngine mixin, which only prevents future starts.
     */
    @JvmStatic
    fun stopBlockedSoundsIfNeeded() {
        if (!ClientAudioPlayer.isPlaying) return

        val clientConfig = ModConfigManager.config.client
        if (!clientConfig.blockVanillaMusic && !clientConfig.blockRecords) return

        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            if (!ClientAudioPlayer.isPlaying) return@execute

            val latestConfig = ModConfigManager.config.client
            if (latestConfig.blockVanillaMusic) {
                minecraft.soundManager.stop(null, SoundSource.MUSIC)
            }
            if (latestConfig.blockRecords) {
                minecraft.soundManager.stop(null, SoundSource.RECORDS)
            }
        }
    }
}
