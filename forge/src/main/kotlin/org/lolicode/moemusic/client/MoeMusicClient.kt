package org.lolicode.moemusic.client

import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.GameShuttingDownEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.fml.ModContainer
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.loading.FMLPaths
import org.lolicode.moemusic.MoeMusic
import org.lolicode.moemusic.MoeMusicForgeBuildInfo
import org.lolicode.moemusic.platform.client.bootstrap.ClientRuntimeBootstrap
import org.lolicode.moemusic.platform.client.bootstrap.ClientShortcutController
import org.lolicode.moemusic.platform.client.bootstrap.MoeMusicClientKeyBindingRegistry
import org.lolicode.moemusic.platform.client.bootstrap.MoeMusicClientKeyBindings
import org.lolicode.moemusic.platform.client.network.ClientNetworkSetup
import org.lolicode.moemusic.platform.client.playback.ClientPlaybackHandler
import org.lolicode.moemusic.platform.client.ui.NowPlayingHud
import org.lolicode.moemusic.platform.client.ui.config.ConfigScreenAccess
import org.lolicode.moemusic.platform.client.ui.config.MoeMusicConfigScreen
import thedarkcolour.kotlinforforge.forge.MOD_BUS

private const val CLOTH_CONFIG_MOD_ID: String = "cloth_config"

object MoeMusicClient {
    private var modBusListenersRegistered = false
    private var initialized = false
    private var keyBindings: MoeMusicClientKeyBindings? = null

    private val modContainer: ModContainer by lazy {
        ModList.get().getModContainerById(MoeMusic.MOD_ID).orElseThrow {
            IllegalStateException("Missing mod container for ${MoeMusic.MOD_ID}")
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        initialized = true

        MoeMusic.logger.info("MoeMusic client initializing...")

        ClientPlaybackHandler.initializeClientMetadata(MoeMusicForgeBuildInfo.MOD_VERSION)

        ConfigScreenAccess.clear()
        if (ModList.get().isLoaded(CLOTH_CONFIG_MOD_ID)) {
            ConfigScreenAccess.register(MoeMusicConfigScreen::build)
            modContainer.registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory::class.java,
            ) {
                ConfigScreenHandler.ConfigScreenFactory { _, parent ->
                    ConfigScreenAccess.buildOrFallback(parent)
                }
            }
        }

        ClientNetworkSetup.register()

        MinecraftForge.EVENT_BUS.addListener(::onClientStopping)
        MinecraftForge.EVENT_BUS.addListener(::onClientLoggedIn)
        MinecraftForge.EVENT_BUS.addListener(::onClientLoggedOut)
        MinecraftForge.EVENT_BUS.addListener(::onClientTick)

        MoeMusic.logger.info("MoeMusic client initialized.")
    }

    @JvmStatic
    fun registerModBusListeners() {
        if (modBusListenersRegistered) return
        modBusListenersRegistered = true

        MOD_BUS.addListener(::onClientSetup)
        MOD_BUS.addListener(::onRegisterKeyMappings)
        MOD_BUS.addListener(::onAddGuiOverlayLayers)
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        ensureInitialized()
        ClientRuntimeBootstrap.onClientStarted(
            configDir = FMLPaths.CONFIGDIR.get().resolve(MoeMusic.MOD_ID),
            gameDir = FMLPaths.GAMEDIR.get(),
        )
    }

    private fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        ensureInitialized()
        keyBindings = MoeMusicClientKeyBindingRegistry.register { keyMapping ->
            event.register(keyMapping)
            keyMapping
        }
    }

    private fun onAddGuiOverlayLayers(event: RegisterGuiOverlaysEvent) {
        ensureInitialized()
        event.registerBelow(VanillaGuiOverlay.SUBTITLES.id(), "now_playing") { _, guiGraphics, partialTick, _, _ ->
            NowPlayingHud.render(guiGraphics, partialTick)
        }
    }

    private fun onClientStopping(event: GameShuttingDownEvent) {
        ClientRuntimeBootstrap.onClientStopping()
    }

    private fun onClientLoggedIn(event: ClientPlayerNetworkEvent.LoggingIn) {
        val bindings = keyBindings ?: return
        ClientShortcutController.onConnectionJoined(Minecraft.getInstance(), bindings)
    }

    private fun onClientLoggedOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        ClientShortcutController.onConnectionDisconnected()
    }

    private fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val bindings = keyBindings ?: return
        ClientShortcutController.handleEndClientTick(Minecraft.getInstance(), bindings)
    }
}
