package org.lolicode.moemusic.platform.client.bootstrap

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.resources.Identifier
import org.lolicode.moemusic.api.model.PlaybackState
import org.lolicode.moemusic.core.audio.LavaPlayerNativeBootstrap
import org.lolicode.moemusic.core.config.ClientVolume
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.plugin.PluginDiscoveryReport
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
import org.slf4j.LoggerFactory
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
                    InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_M,
                    keyCategory,
                )
            ),
            openConfigKey = registerKeyMapping(
                KeyMapping(
                    "key.moemusic.open_config",
                    InputConstants.Type.KEYBOARD,
                    InputConstants.UNKNOWN.value,
                    keyCategory,
                )
            ),
            playPauseKey = registerKeyMapping(
                KeyMapping(
                    "key.moemusic.play_pause",
                    InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_PAUSE,
                    keyCategory,
                )
            ),
            nextTrackKey = registerKeyMapping(
                KeyMapping(
                    "key.moemusic.next_track",
                    InputConstants.Type.KEYBOARD,
                    InputConstants.UNKNOWN.value,
                    keyCategory,
                )
            ),
            volumeUpKey = registerKeyMapping(
                KeyMapping(
                    "key.moemusic.volume_up",
                    InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_PAGEUP,
                    keyCategory,
                )
            ),
            volumeDownKey = registerKeyMapping(
                KeyMapping(
                    "key.moemusic.volume_down",
                    InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_PAGEDOWN,
                    keyCategory,
                )
            ),
        )
}

private val logger = LoggerFactory.getLogger("MoeMusic/Client")

private fun isInitialScreenReady(mc: Minecraft): Boolean {
    if (mc.overlay != null) return false
    val current = mc.screen ?: return false
    return current is TitleScreen || current !is GenericMessageScreen
}

object ClientRuntimeBootstrap {

    var pendingDiscoveryReport: PluginDiscoveryReport? = null
        private set
    var resolvedGameDir: Path? = null
        private set
    var resolvedConfigDir: Path? = null
        private set

    fun onClientStarted(configDir: Path, gameDir: Path? = null) {
        resolvedConfigDir = configDir
        resolvedGameDir = gameDir
        LavaPlayerNativeBootstrap.configure(configDir = configDir, gameDir = gameDir)
        ModConfigManager.load(configDir)
        ContentFilterRuntime.applyConfig(ModConfigManager.config)
        val report = PluginManager.initialize(configDir)
        if (report.hasIssues) {
            pendingDiscoveryReport = report
            logger.warn(
                "Plugin issues detected during client discovery: {} duplicate(s), {} incompatible, {} failure(s)",
                report.duplicatePlugins.size,
                report.incompatiblePlugins.size,
                report.failedPlugins.size,
            )
        }
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

object ClientPluginIssuePresenter {

    private var issueScreenPending = true

    fun handleClientTick(mc: Minecraft) {
        if (!issueScreenPending || mc.player != null || !isInitialScreenReady(mc)) return
        issueScreenPending = false
        val report = ClientRuntimeBootstrap.pendingDiscoveryReport
            ?: PluginManager.lastDiscoveryReport
        if (report != null && report.hasIssues) {
            val currentScreen = mc.screen
            logger.info(
                "Displaying PluginIssueScreen for {} plugin issue(s)",
                report.duplicatePlugins.size + report.incompatiblePlugins.size + report.failedPlugins.size,
            )
            mc.setScreen(
                PluginIssueScreen(
                    parent = currentScreen,
                    report = report,
                    gameDir = ClientRuntimeBootstrap.resolvedGameDir ?: mc.gameDirectory.toPath(),
                    configDir = ClientRuntimeBootstrap.resolvedConfigDir,
                )
            )
        }
    }

    fun dismiss() {
        issueScreenPending = false
    }
}

object ClientConnectionCoordinator {

    fun onConnectionJoined(mc: Minecraft, openGuiKey: KeyMapping) {
        ClientPluginIssuePresenter.dismiss()
        ClientPlaybackHandler.onConnectionJoined()
        maybeShowOpenGuiTip(mc, openGuiKey)
    }

    fun onConnectionDisconnected() {
        ClientPlaybackHandler.onConnectionDisconnected()
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
}

object ClientShortcutController {

    private const val VOLUME_STEP_PERCENT = 5

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

    private fun handlePlayPauseKey(mc: Minecraft) {
        if (mc.player == null || mc.connection == null) return
        when (ClientPlaybackHandler.currentContext?.state) {
            is PlaybackState.Playing ->
                ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.PAUSE)
            is PlaybackState.Paused, null ->  // null indicates STOPPED state, should also resume
                ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.RESUME)
            else -> Unit
        }
    }

    private fun handleNextTrackKey(mc: Minecraft) {
        if (mc.player == null || mc.connection == null) return
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
