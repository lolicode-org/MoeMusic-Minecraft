package org.lolicode.moemusic.spigot

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.model.TrackAddResult
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.artistDisplay
import org.lolicode.moemusic.api.service.PlaybackActionOutcome
import org.lolicode.moemusic.core.audio.LavaPlayerNativeBootstrap
import org.lolicode.moemusic.core.media.probe.MediaProbeServiceImpl
import org.lolicode.moemusic.core.permission.PermissionServiceImpl
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.ratelimit.RequestRateLimiter
import org.lolicode.moemusic.core.runtime.ServerPluginServices
import org.lolicode.moemusic.core.runtime.ServerRuntimeAdapter
import org.lolicode.moemusic.core.runtime.ServerRuntimeCoordinator
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.session.ServerConnectionEventsDispatcher
import org.lolicode.moemusic.core.user.UserActionServiceImpl
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

class MoeMusicPlugin : JavaPlugin(), Listener {
    private lateinit var channel: SpigotNetworkChannel
    private lateinit var commands: MusicCommand

    override fun onEnable() {
        instance = this
        channel = SpigotNetworkChannel(this)
        channel.register()

        val configDir = dataFolder.toPath()
        LavaPlayerNativeBootstrap.configure(configDir, server.worldContainer.toPath())
        ServerRuntimeCoordinator.serverInit(
            channel = channel,
            configDir = configDir,
            adapter = RuntimeAdapter,
            pluginServicesFactory = ::pluginServices,
        )

        server.pluginManager.registerEvents(this, this)
        server.onlinePlayers.forEach { ServerConnectionEventsDispatcher.connected(SpigotUser.snapshot(it)) }
        commands = MusicCommand(this)
        requireNotNull(getCommand("music")).setExecutor(commands)
        requireNotNull(getCommand("music")).tabCompleter = commands
        logger.info("MoeMusic server initialized for Spigot ${server.bukkitVersion}.")
    }

    override fun onDisable() {
        if (::commands.isInitialized) commands.close()
        if (::channel.isInitialized) channel.unregister()
        runCatching { ServerRuntimeCoordinator.serverShutdown(finalRuntime = true) }
            .onFailure { logger.severe("Failed to shut down MoeMusic: ${it.message}") }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        ServerConnectionEventsDispatcher.connected(SpigotUser.snapshot(event.player))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        ServerConnectionEventsDispatcher.disconnected(SpigotUser.snapshot(event.player))
        handleDisconnect(event.player.uniqueId)
    }

    fun handleRegisteredClientLeave(userId: UUID) {
        val session = UserSessionRegistry.session(userId) ?: return
        if (session.participation != UserSessionRegistry.Participation.ACTIVE) return
        UserSessionRegistry.standby(userId)
        VoteManager.onLeave(userId)
        if (UserSessionRegistry.activeCount() == 0) ServerRuntimeCoordinator.releaseNativeAudienceLeaseIfHeld()
    }

    private fun handleDisconnect(userId: UUID) {
        val removed = UserSessionRegistry.disconnect(userId) ?: return
        if (removed.participation == UserSessionRegistry.Participation.ACTIVE) {
            VoteManager.onLeave(userId)
            if (UserSessionRegistry.activeCount() == 0) ServerRuntimeCoordinator.releaseNativeAudienceLeaseIfHeld()
        }
    }

    private fun pluginServices(
        playbackController: org.lolicode.moemusic.core.playback.ServerPlaybackController,
        trackSubmissionService: org.lolicode.moemusic.core.playback.TrackSubmissionService,
        requestRateLimiter: RequestRateLimiter,
    ): ServerPluginServices {
        val permissions = PermissionServiceImpl()
        return ServerPluginServices(
            permissionService = permissions,
            userActionService = UserActionServiceImpl(
                permissionService = permissions,
                requestRateLimiter = requestRateLimiter,
                searchService = PluginManager.searchService,
                identifierResolutionService = PluginManager.identifierResolutionService,
                trackSubmissionService = trackSubmissionService,
                playbackController = playbackController,
                voteToSkipHandler = { id ->
                    val vote = VoteManager.request(id)
                    PlaybackActionOutcome(vote.success, vote.failure)
                },
            ),
            mediaProbeService = MediaProbeServiceImpl(),
        )
    }

    private object RuntimeAdapter : ServerRuntimeAdapter {
        override fun onUserQueueTrackSkipped(track: TrackInfo, reason: LocalizedText?) {
            val title = track.title.ifBlank { track.id.ifBlank { "queued track" } }
            instance.runOnServerThread {
                SpigotUsers.allActive().forEach { user ->
                    val message = if (reason == null) {
                        LocalizedText.key("action.moemusic.playback.queue_track_skipped", title)
                    } else {
                        LocalizedText.key("action.moemusic.playback.queue_track_skipped_reason", title, reason)
                    }
                    user.player()?.let { Chat.failure(it, message) }
                }
            }
        }

        override fun onTrackSubmitted(track: TrackInfo, result: TrackAddResult) {
            val title = track.title.ifBlank { track.id.ifBlank { "queued track" } }
            val submitter = track.submittedByUserName?.takeIf(String::isNotBlank) ?: "Server"
            instance.runOnServerThread {
                SpigotUsers.allActive().forEach { user ->
                    user.player()?.let {
                        Chat.success(it, LocalizedText.key("action.moemusic.track.queued_broadcast", submitter, title, track.artistDisplay))
                    }
                }
            }
        }

        override fun onServerSessionCleared() = VoteManager.reset()
    }

    internal fun runOnServerThread(block: () -> Unit) {
        if (server.isPrimaryThread) block() else if (isEnabled) server.scheduler.runTask(this, Runnable(block))
    }

    internal fun hasPermission(userId: UUID, permission: String, defaultLevel: Int): Boolean {
        val check = Callable {
            val player = server.getPlayer(userId) ?: return@Callable false
            if (player.isPermissionSet(permission)) {
                player.hasPermission(permission)
            } else {
                defaultLevel <= 0 || player.isOp
            }
        }
        return if (server.isPrimaryThread) check.call() else if (isEnabled) {
            runCatching { server.scheduler.callSyncMethod(this, check).get(5, TimeUnit.SECONDS) }
                .getOrElse { error ->
                    logger.warning("Could not check permission $permission for $userId: ${error.message}")
                    false
                }
        } else {
            false
        }
    }

    companion object {
        internal lateinit var instance: MoeMusicPlugin
            private set
    }
}
