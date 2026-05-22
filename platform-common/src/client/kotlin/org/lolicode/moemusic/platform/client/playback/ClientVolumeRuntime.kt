package org.lolicode.moemusic.platform.client.playback

import org.lolicode.moemusic.api.client.ClientVolumeOverride
import org.lolicode.moemusic.clientcore.playback.ClientVolumeController
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.platform.client.audio.ClientAudioPlayer

internal object ClientVolumeRuntime {

    private val controller = ClientVolumeController(ClientAudioPlayer::setVolume)

    val configuredVolumePercent: Int
        get() = controller.configuredVolumePercent

    val effectiveVolumePercent: Int
        get() = controller.effectiveVolumePercent

    fun initializeConfiguredVolume(percent: Int) {
        controller.setConfiguredVolumePercent(percent)
    }

    fun setConfiguredVolumePercent(percent: Int) {
        controller.setConfiguredVolumePercent(percent)
    }

    fun setAndPersistConfiguredVolumePercent(percent: Int) {
        controller.setConfiguredVolumePercent(percent)
        persistConfiguredVolume()
    }

    fun persistConfiguredVolume() {
        ModConfigManager.updateClient { client ->
            client.copy(volume = controller.configuredVolumePercent)
        }
    }

    fun setTransientVolumeOverride(ownerId: String, override: ClientVolumeOverride) {
        controller.setTransientOverride(ownerId, override)
    }

    fun clearTransientVolumeOverride(ownerId: String) {
        controller.clearTransientOverride(ownerId)
    }

    fun clearAllTransientVolumeOverrides() {
        controller.clearAllTransientOverrides()
    }
}
