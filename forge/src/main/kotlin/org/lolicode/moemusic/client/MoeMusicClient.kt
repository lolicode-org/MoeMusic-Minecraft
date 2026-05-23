package org.lolicode.moemusic.client

import net.minecraft.client.Minecraft
import net.minecraftforge.client.ClientRegistry
import net.minecraftforge.client.ConfigGuiHandler
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.gui.OverlayRegistry
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.fml.ModContainer
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
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

private const val CLOTH_CONFIG_MOD_ID: String = "cloth_config"

object MoeMusicClient {
    private var modBusListenersRegistered = false
    private var initialized = false
    private var overlayRegistered = false
    private var shutdownHookRegistered = false
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
                ConfigGuiHandler.ConfigGuiFactory::class.java,
            ) {
                ConfigGuiHandler.ConfigGuiFactory { _, parent ->
                    ConfigScreenAccess.buildOrFallback(parent)
                }
            }
        }

        ClientNetworkSetup.register()

        MinecraftForge.EVENT_BUS.addListener(::onClientLoggedIn)
        MinecraftForge.EVENT_BUS.addListener(::onClientLoggedOut)
        MinecraftForge.EVENT_BUS.addListener(::onClientTick)
        registerShutdownHook()

        MoeMusic.logger.info("MoeMusic client initialized.")
    }

    @JvmStatic
    fun registerModBusListeners() {
        if (modBusListenersRegistered) return
        modBusListenersRegistered = true

        val modEventBus = FMLJavaModLoadingContext.get().modEventBus
        modEventBus.addListener(::onClientSetup)
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        ensureInitialized()
        event.enqueueWork {
            registerKeyBindings()
            registerHudOverlay()
            ClientRuntimeBootstrap.onClientStarted(
                configDir = FMLPaths.CONFIGDIR.get().resolve(MoeMusic.MOD_ID),
                gameDir = FMLPaths.GAMEDIR.get(),
            )
        }
    }

    private fun registerKeyBindings() {
        if (keyBindings != null) return
        keyBindings = MoeMusicClientKeyBindingRegistry.register { keyMapping ->
            ClientRegistry.registerKeyBinding(keyMapping)
            keyMapping
        }
    }

    private fun registerHudOverlay() {
        if (overlayRegistered) return
        overlayRegistered = true
        OverlayRegistry.registerOverlayBottom("moemusic_now_playing") { _, poseStack, partialTick, _, _ ->
            NowPlayingHud.render(poseStack, partialTick)
        }
    }

    private fun registerShutdownHook() {
        if (shutdownHookRegistered) return
        shutdownHookRegistered = true
        Runtime.getRuntime().addShutdownHook(Thread { ClientRuntimeBootstrap.onClientStopping() })
    }

    private fun onClientLoggedIn(event: ClientPlayerNetworkEvent.LoggedInEvent) {
        val bindings = keyBindings ?: return
        ClientShortcutController.onConnectionJoined(Minecraft.getInstance(), bindings)
    }

    private fun onClientLoggedOut(event: ClientPlayerNetworkEvent.LoggedOutEvent) {
        ClientShortcutController.onConnectionDisconnected()
    }

    private fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val bindings = keyBindings ?: return
        ClientShortcutController.handleEndClientTick(Minecraft.getInstance(), bindings)
    }
}
