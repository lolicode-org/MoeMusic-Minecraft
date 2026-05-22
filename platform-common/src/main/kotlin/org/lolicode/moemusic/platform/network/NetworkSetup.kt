package org.lolicode.moemusic.platform.network

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.audio.LavaPlayerNativeBootstrap
import org.lolicode.moemusic.core.network.ServerPacketHandlers
import org.lolicode.moemusic.core.network.ServerPacketSessionBridge
import org.lolicode.moemusic.core.protocol.PacketRegistry
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.platform.command.VoteManager
import org.lolicode.moemusic.platform.network.NetworkSetup.handleDisconnect
import org.lolicode.moemusic.platform.network.NetworkSetup.startServer
import org.lolicode.moemusic.platform.player.MinecraftUser
import org.lolicode.moemusic.platform.player.MinecraftUserRegistry
import org.lolicode.moemusic.platform.runtime.MoePlatform
import java.nio.file.Path
import java.util.UUID

/**
 * Loader-agnostic network bootstrap shared by all server platforms.
 *
 * Creates the shared [BadPacketsNetworkChannel] and [PacketRegistry], then lets the loader
 * module invoke [startServer] / [handleDisconnect] from its own lifecycle hooks.
 */
object NetworkSetup {

    lateinit var channel: BadPacketsNetworkChannel
        private set

    lateinit var packetRegistry: PacketRegistry
        private set

    private lateinit var configDir: Path

    /**
     * Initialize packet registration and store [configDir] for later server startup.
     */
    fun setup(configDir: Path, gameDir: Path? = null) {
        LavaPlayerNativeBootstrap.configure(configDir = configDir, gameDir = gameDir)
        this.configDir = configDir
        packetRegistry = PacketRegistry()
        channel = BadPacketsNetworkChannel(packetRegistry)
        channel.register()

        // Register packet handlers into PacketRegistry
        ServerPacketHandlers(channel, UserSessionBridge).registerAll(packetRegistry)
    }

    /**
     * Invoke from the loader's "server starting" hook once all mods finished registration.
     */
    fun startServer() {
        MoePlatform.serverInit(channel, configDir)
    }

    /**
     * Invoke from the loader's player-disconnect hook.
     */
    fun handleDisconnect(userId: UUID) {
        val removed = UserSessionRegistry.disconnect(userId) ?: return
        if (removed.participation == UserSessionRegistry.Participation.ACTIVE) {
            VoteManager.onUserLeave(userId)
            if (UserSessionRegistry.activeCount() == 0) {
                MoePlatform.onNativeAudienceUnavailable()
            }
        }
    }

    internal fun handleRegisteredClientLeave(userId: UUID) {
        val session = UserSessionRegistry.session(userId) ?: return
        if (session.participation != UserSessionRegistry.Participation.ACTIVE) return

        UserSessionRegistry.standby(userId)
        VoteManager.onUserLeave(userId)
        if (UserSessionRegistry.activeCount() == 0) {
            MoePlatform.onNativeAudienceUnavailable()
        }
    }

    private object UserSessionBridge : ServerPacketSessionBridge {
        override fun activate(sender: MoeMusicUser, locale: String): MoeMusicUser {
            val entity = (sender as? MinecraftUser)?.entity()
                ?: return sender.also {
                    UserSessionRegistry.upsert(it, locale, UserSessionRegistry.Participation.ACTIVE)
                }
            return MinecraftUserRegistry.onJoin(entity, locale)
        }

        override fun standby(sender: MoeMusicUser, locale: String): MoeMusicUser {
            val entity = (sender as? MinecraftUser)?.entity()
                ?: return sender.also {
                    UserSessionRegistry.upsert(it, locale, UserSessionRegistry.Participation.STANDBY)
                }
            return MinecraftUserRegistry.onStandby(entity, locale)
        }

        override fun handleRegisteredClientLeave(userId: UUID) {
            NetworkSetup.handleRegisteredClientLeave(userId)
        }
    }
}
