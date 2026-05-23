package org.lolicode.moemusic.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import org.lolicode.moemusic.MoeMusic
import org.lolicode.moemusic.MoeMusicFabricBuildInfo
import org.lolicode.moemusic.platform.client.bootstrap.ClientRuntimeBootstrap
import org.lolicode.moemusic.platform.client.bootstrap.ClientShortcutController
import org.lolicode.moemusic.platform.client.bootstrap.MoeMusicClientKeyBindingRegistry
import org.lolicode.moemusic.platform.client.bootstrap.MoeMusicClientKeyBindings
import org.lolicode.moemusic.platform.client.playback.ClientPlaybackHandler
import org.lolicode.moemusic.platform.client.ui.config.ConfigScreenAccess
import org.lolicode.moemusic.platform.client.ui.config.MoeMusicConfigScreen
import org.lolicode.moemusic.platform.client.network.ClientNetworkSetup

object MoeMusicClient : ClientModInitializer {

    private lateinit var keyBindings: MoeMusicClientKeyBindings

    override fun onInitializeClient() {
        MoeMusic.logger.info("MoeMusic client initializing…")

        ClientPlaybackHandler.initializeClientMetadata(
            MoeMusicFabricBuildInfo.MOD_VERSION,
        )

        ConfigScreenAccess.clear()
        if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
            ConfigScreenAccess.register { parent -> MoeMusicConfigScreen.build(parent) }
        }

        // On a remote client (connecting to a dedicated server), SERVER_STARTING never fires
        // on the client JVM, so the client runtime must initialize from CLIENT_STARTED instead.
        // This fires after ALL mod initializers have run, giving external plugins time to
        // register themselves before MoeMusic dispatches its once-per-client onClientRuntimeLoad hook.
        ClientLifecycleEvents.CLIENT_STARTED.register {
            val loader = FabricLoader.getInstance()
            ClientRuntimeBootstrap.onClientStarted(
                configDir = loader.configDir.resolve("moemusic"),
                gameDir = loader.gameDir,
            )
        }

        // Register S→C packet receivers
        ClientNetworkSetup.register()

        keyBindings = MoeMusicClientKeyBindingRegistry.register(KeyBindingHelper::registerKeyBinding)

        // On JOIN: send the initial client hello/state, restart any needed sync loop,
        // and show the shortcut tip once.
        ClientPlayConnectionEvents.JOIN.register { _, _, mc ->
            ClientShortcutController.onConnectionJoined(mc, keyBindings)
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ClientShortcutController.onConnectionDisconnected()
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register {
            ClientRuntimeBootstrap.onClientStopping()
        }

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            ClientShortcutController.handleEndClientTick(mc, keyBindings)
        }

        MoeMusic.logger.info("MoeMusic client initialized.")
    }
}
