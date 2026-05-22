package org.lolicode.moemusic.client

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.ModContainer
import net.neoforged.fml.ModList
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.GameShuttingDownEvent
import org.lolicode.moemusic.MoeMusic
import org.lolicode.moemusic.MoeMusicNeoForgeBuildInfo
import org.lolicode.moemusic.platform.client.bootstrap.ClientRuntimeBootstrap
import org.lolicode.moemusic.platform.client.bootstrap.ClientShortcutController
import org.lolicode.moemusic.platform.client.bootstrap.MoeMusicClientKeyBindingRegistry
import org.lolicode.moemusic.platform.client.bootstrap.MoeMusicClientKeyBindings
import org.lolicode.moemusic.platform.client.playback.ClientPlaybackHandler
import org.lolicode.moemusic.platform.client.ui.config.ConfigScreenAccess
import org.lolicode.moemusic.platform.client.ui.config.MoeMusicConfigScreen
import org.lolicode.moemusic.platform.client.network.ClientNetworkSetup
import org.lolicode.moemusic.platform.client.ui.NowPlayingHud
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.util.function.Supplier

private const val CLOTH_CONFIG_MOD_ID: String = "cloth_config"

@Mod(value = MoeMusic.MOD_ID, dist = [Dist.CLIENT])
object MoeMusicClient {
    private lateinit var keyBindings: MoeMusicClientKeyBindings

    private val modContainer: ModContainer by lazy {
        ModList.get().getModContainerById(MoeMusic.MOD_ID).orElseThrow {
            IllegalStateException("Missing mod container for ${MoeMusic.MOD_ID}")
        }
    }

    init {
        MoeMusic.logger.info("MoeMusic client initializing...")

        ClientPlaybackHandler.initializeClientMetadata(MoeMusicNeoForgeBuildInfo.MOD_VERSION)

        ConfigScreenAccess.clear()
        if (ModList.get().isLoaded(CLOTH_CONFIG_MOD_ID)) {
            ConfigScreenAccess.register(MoeMusicConfigScreen::build)
            modContainer.registerExtensionPoint(
                IConfigScreenFactory::class.java,
                Supplier {
                    IConfigScreenFactory { _, parent ->
                        ConfigScreenAccess.buildOrFallback(parent)
                    }
                },
            )
        }

        ClientNetworkSetup.register()

        MOD_BUS.addListener(::onClientSetup)
        MOD_BUS.addListener(::onRegisterKeyMappings)
        MOD_BUS.addListener(::onRegisterGuiLayers)

        NeoForge.EVENT_BUS.addListener(::onClientStopping)
        NeoForge.EVENT_BUS.addListener(::onClientLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onClientLoggedOut)
        NeoForge.EVENT_BUS.addListener(::onClientTickPost)

        MoeMusic.logger.info("MoeMusic client initialized.")
    }

    private fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        keyBindings = MoeMusicClientKeyBindingRegistry.register { keyMapping ->
            event.register(keyMapping)
            keyMapping
        }
    }

    private fun onRegisterGuiLayers(event: RegisterGuiLayersEvent) {
        event.registerBelow(
            VanillaGuiLayers.SUBTITLE_OVERLAY,
            ResourceLocation.fromNamespaceAndPath(MoeMusic.MOD_ID, "now_playing"),
            NowPlayingHud::render,
        )
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        ClientRuntimeBootstrap.onClientStarted(
            configDir = FMLPaths.CONFIGDIR.get().resolve(MoeMusic.MOD_ID),
            gameDir = FMLPaths.GAMEDIR.get(),
        )
    }

    private fun onClientStopping(event: GameShuttingDownEvent) {
        ClientRuntimeBootstrap.onClientStopping()
    }

    private fun onClientLoggedIn(event: ClientPlayerNetworkEvent.LoggingIn) {
        if (!::keyBindings.isInitialized) return
        ClientShortcutController.onConnectionJoined(Minecraft.getInstance(), keyBindings)
    }

    private fun onClientLoggedOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        ClientShortcutController.onConnectionDisconnected()
    }

    private fun onClientTickPost(event: ClientTickEvent.Post) {
        if (!::keyBindings.isInitialized) return
        ClientShortcutController.handleEndClientTick(Minecraft.getInstance(), keyBindings)
    }
}
