package org.lolicode.moemusic.platform.client.bootstrap

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.lolicode.moemusic.api.model.PlaybackState
import org.lolicode.moemusic.core.audio.LavaPlayerNativeBootstrap
import org.lolicode.moemusic.core.config.ClientVolume
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.protocol.proto.PlaybackControlAction
import org.lolicode.moemusic.platform.client.network.ClientNetworkSetup
import org.lolicode.moemusic.platform.client.playback.ClientPlaybackHandler
import org.lolicode.moemusic.platform.client.playback.ClientPlaybackServiceImpl
import org.lolicode.moemusic.platform.client.playback.ClientRequestServiceImpl
import org.lolicode.moemusic.platform.client.playback.ClientVolumeRuntime
import org.lolicode.moemusic.platform.client.ui.*
import org.lolicode.moemusic.platform.client.ui.config.ConfigScreenAccess
import org.lolicode.moemusic.platform.runtime.MoePlatform
import org.lolicode.moemusic.platform.text.McText
import java.nio.file.Path

data class MoeMusicClientKeyBindings(
    val openGuiKey: KeyMapping,
    val openConfigKey: KeyMapping,
    val playPauseKey: KeyMapping,
    val nextTrackKey: KeyMapping,
    val volumeUpKey: KeyMapping,
    val volumeDownKey: KeyMapping,
)

object MoeMusicClientKeyBindingRegistry {

    private val keyCategory: KeyMapping.Category = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("moemusic", "general")
    )

    fun register(registerKeyMapping: (KeyMapping) -> KeyMapping): MoeMusicClientKeyBindings =
        MoeMusicClientKeyBindings(
            openGuiKey = registerKeyMapping(
                KeyMapping(
                    "key.moemusic.open_gui",
                    InputConstants.Type.KEYSYM,
                    InputConstants.KEY_M,
                    keyCategory,
                )
            ),
            openConfigKey = registerKeyMapping(
                KeyMapping(
                    "key.moemusic.open_config",
                    InputConstants.Type.KEYSYM,
                    -1,
                    keyCategory,
                )
            ),
            playPauseKey = registerKeyMapping(
                KeyMapping(
                    "key.moemusic.play_pause",
                    InputConstants.Type.KEYSYM,
                    InputConstants.KEY_PAUSE,
                    keyCategory,
                )
            ),
            nextTrackKey = registerKeyMapping(
                KeyMapping(
                    "key.moemusic.next_track",
                    InputConstants.Type.KEYSYM,
                    -1,
                    keyCategory,
                )
            ),
            volumeUpKey = registerKeyMapping(
                KeyMapping(
                    "key.moemusic.volume_up",
                    InputConstants.Type.KEYSYM,
                    InputConstants.KEY_PAGEUP,
                    keyCategory,
                )
            ),
            volumeDownKey = registerKeyMapping(
                KeyMapping(
                    "key.moemusic.volume_down",
                    InputConstants.Type.KEYSYM,
                    InputConstants.KEY_PAGEDOWN,
                    keyCategory,
                )
            ),
        )
}

object ClientRuntimeBootstrap {

    fun onClientStarted(configDir: Path, gameDir: Path? = null) {
        LavaPlayerNativeBootstrap.configure(configDir = configDir, gameDir = gameDir)
        ModConfigManager.load(configDir)
        ContentFilterRuntime.applyConfig(ModConfigManager.config)
        PluginManager.initialize(configDir)
        Localization.validateConfiguredDefaultLanguage()
        ClientVolumeRuntime.initializeConfiguredVolume(ModConfigManager.config.client.volume)
        MoePlatform.clientInitIfNeeded(ClientPlaybackServiceImpl, ClientRequestServiceImpl)
    }

    fun onClientStopping() {
        ClientNetworkSetup.stopSyncLoop()
        persistClientVolume()
        MoePlatform.clientShutdown()
    }

    internal fun persistClientVolume() {
        ClientVolumeRuntime.persistConfiguredVolume()
    }
}

object ClientShortcutController {

    private const val VOLUME_STEP_PERCENT = 5

    fun onConnectionJoined(mc: Minecraft, keyBindings: MoeMusicClientKeyBindings) {
        ClientPlaybackHandler.onConnectionJoined()
        maybeShowOpenGuiTip(mc, keyBindings.openGuiKey)
    }

    fun onConnectionDisconnected() {
        ClientPlaybackHandler.onConnectionDisconnected()
    }

    fun handleEndClientTick(mc: Minecraft, keyBindings: MoeMusicClientKeyBindings) {
        while (keyBindings.openGuiKey.consumeClick()) {
            if (mc.screen == null) {
                openMusicPlayerScreen(mc)
            }
        }
        if (mc.screen != null) return

        while (keyBindings.openConfigKey.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(ConfigScreenAccess.buildOrFallback(null))
            }
        }

        if (mc.screen != null) return

        while (keyBindings.playPauseKey.consumeClick()) {
            handlePlayPauseKey(mc)
        }
        while (keyBindings.nextTrackKey.consumeClick()) {
            handleNextTrackKey(mc)
        }
        while (keyBindings.volumeUpKey.consumeClick()) {
            adjustClientVolume(mc, VOLUME_STEP_PERCENT)
        }
        while (keyBindings.volumeDownKey.consumeClick()) {
            adjustClientVolume(mc, -VOLUME_STEP_PERCENT)
        }
    }

    private fun openMusicPlayerScreen(mc: Minecraft) {
        val availabilityIssue = ClientPlaybackHandler.currentAvailabilityIssue(mc)
        mc.setScreen(
            if (availabilityIssue != null) {
                MusicPlayerUnavailableScreen(null, availabilityIssue)
            } else {
                MusicPlayerScreen()
            }
        )
    }

    private fun maybeShowOpenGuiTip(mc: Minecraft, openGuiKey: KeyMapping) {
        if (!ClientPlaybackHandler.isPlaybackEnabledForCurrentServer(mc)) return
        if (ModConfigManager.config.client.joinShortcutTipShown) return

        showWrappedSystemToast(
            mc,
            ClientToastIds.openGuiTip,
            McText.translatable("tip.moemusic.open_gui.title"),
            McText.translatable(
                "tip.moemusic.open_gui.body",
                openGuiKey.translatedKeyMessage,
            ),
        )
        showPersistentRuntimeWarning(
            mc,
            McText.translatable("tip.moemusic.open_gui.title"),
            McText.translatable(
                "tip.moemusic.open_gui.body",
                openGuiKey.translatedKeyMessage,
            ),
        )
        ModConfigManager.updateClient { client -> client.copy(joinShortcutTipShown = true) }
    }

    private fun handlePlayPauseKey(mc: Minecraft) {
        if (mc.player == null || mc.connection == null) return
        when (ClientPlaybackHandler.currentContext?.state) {
            is PlaybackState.Playing ->
                ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.PAUSE)
            is PlaybackState.Paused ->
                ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.RESUME)
            else -> Unit
        }
    }

    private fun handleNextTrackKey(mc: Minecraft) {
        if (mc.player == null || mc.connection == null) return
        if (ClientPlaybackHandler.currentContext == null) return
        ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.SKIP)
    }

    private fun adjustClientVolume(mc: Minecraft, deltaPercent: Int) {
        val newVolumePercent = ClientVolume.normalizePercent(
            ClientVolumeRuntime.configuredVolumePercent + deltaPercent
        )
        ClientVolumeRuntime.setAndPersistConfiguredVolumePercent(newVolumePercent)
        mc.player?.sendOverlayMessage(
            McText.translatable(
                "screen.moemusic.now_playing.volume",
                newVolumePercent,
            )
        )
    }
}
