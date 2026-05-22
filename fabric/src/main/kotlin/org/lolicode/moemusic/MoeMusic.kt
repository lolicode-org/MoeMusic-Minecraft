package org.lolicode.moemusic

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import org.lolicode.moemusic.core.session.ServerConnectionEventsDispatcher
import org.lolicode.moemusic.platform.runtime.MoePlatform
import org.lolicode.moemusic.platform.player.MinecraftUserRegistry
import org.lolicode.moemusic.platform.command.MusicCommands
import org.lolicode.moemusic.platform.network.NetworkSetup
import org.lolicode.moemusic.platform.permission.installFabricPermissionChecker
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object MoeMusic : ModInitializer {
    const val MOD_ID = "moemusic"
    val logger: Logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        logger.info("MoeMusic server initializing…")

        MoePlatform.configureRuntimeInfo(
            loaderId = MoeMusicFabricBuildInfo.LOADER_ID,
            modVersion = MoeMusicFabricBuildInfo.MOD_VERSION,
        )
        installFabricPermissionChecker()

        // Initialise network channel, packet registry, and platform bootstrap
        val loader = FabricLoader.getInstance()
        NetworkSetup.setup(
            configDir = loader.configDir.resolve("moemusic"),
            gameDir = loader.gameDir,
        )

        ServerLifecycleEvents.SERVER_STARTING.register {
            NetworkSetup.startServer()
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            ServerConnectionEventsDispatcher.connected(MinecraftUserRegistry.snapshot(handler.player))
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            ServerConnectionEventsDispatcher.disconnected(MinecraftUserRegistry.snapshot(handler.player))
            NetworkSetup.handleDisconnect(handler.player.uuid)
        }

        // Register /music commands
        CommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            MusicCommands.register(dispatcher)
        }

        // Integrated singleplayer keeps the logical server runtime alive across world closes,
        // while a dedicated server tears it down with the process.
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            MoePlatform.serverShutdown(finalRuntime = server.isDedicatedServer)
        }

        logger.info("MoeMusic server initialized.")
    }
}
