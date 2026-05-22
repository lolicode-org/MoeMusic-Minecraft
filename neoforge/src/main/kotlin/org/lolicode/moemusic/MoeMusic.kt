package org.lolicode.moemusic

import net.minecraft.server.level.ServerPlayer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.session.ServerConnectionEventsDispatcher
import org.lolicode.moemusic.platform.runtime.MoePlatform
import org.lolicode.moemusic.platform.player.MinecraftUserRegistry
import org.lolicode.moemusic.platform.command.MusicCommands
import org.lolicode.moemusic.platform.network.NetworkSetup
import org.lolicode.moemusic.platform.permission.NeoForgePermissionBridge
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Mod(MoeMusic.MOD_ID)
object MoeMusic {
    const val MOD_ID: String = "moemusic"
    val logger: Logger = LoggerFactory.getLogger(MOD_ID)

    init {
        logger.info("MoeMusic server initializing...")

        MoePlatform.configureRuntimeInfo(
            loaderId = MoeMusicNeoForgeBuildInfo.LOADER_ID,
            modVersion = MoeMusicNeoForgeBuildInfo.MOD_VERSION,
        )
        NeoForgePermissionBridge.install()
        NetworkSetup.setup(
            configDir = FMLPaths.CONFIGDIR.get().resolve(MOD_ID),
            gameDir = FMLPaths.GAMEDIR.get(),
        )

        NeoForge.EVENT_BUS.addListener(::onServerStarting)
        NeoForge.EVENT_BUS.addListener(::onServerStopping)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)

        logger.info("MoeMusic server initialized.")
    }

    private fun onServerStarting(event: ServerStartingEvent) {
        NetworkSetup.startServer()
    }

    private fun onServerStopping(event: ServerStoppingEvent) {
        MoePlatform.serverShutdown(finalRuntime = event.server.isDedicatedServer)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        MusicCommands.register(event.dispatcher)
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val locale = UserSessionRegistry.localeFor(player.uuid) ?: "en_us"
        ServerConnectionEventsDispatcher.connected(MinecraftUserRegistry.snapshot(player, locale))
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val locale = UserSessionRegistry.localeFor(player.uuid) ?: "en_us"
        ServerConnectionEventsDispatcher.disconnected(MinecraftUserRegistry.snapshot(player, locale))
        NetworkSetup.handleDisconnect(player.uuid)
    }
}
