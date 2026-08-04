package org.lolicode.moemusic.spigot

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.Messenger
import org.bukkit.plugin.messaging.PluginMessageListener
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.network.ServerPacketHandlers
import org.lolicode.moemusic.core.network.ServerPacketSessionBridge
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.PacketRegistry
import org.lolicode.moemusic.core.runtime.ServerRuntimeCoordinator
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.transport.NetworkChannel
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class SpigotNetworkChannel(
    private val plugin: MoeMusicPlugin,
) : NetworkChannel, PluginMessageListener {
    private val registry = PacketRegistry()
    private val byChannel = PacketIds.ALL.associateBy(PacketId::toChannelKey)
    private val pendingOversizedTrackSkip = AtomicReference<String?>()
    private val maxPayloadSize: Int = runCatching {
        Messenger::class.java.getField("MAX_MESSAGE_SIZE").getInt(null)
    }.getOrElse {
        plugin.logger.warning("Could not read Spigot's plugin-message limit; using the 1.18.2 limit.")
        LEGACY_MAX_PAYLOAD_SIZE
    }

    fun register() {
        val messenger = plugin.server.messenger
        S2C_IDS.forEach { messenger.registerOutgoingPluginChannel(plugin, it.toChannelKey()) }
        C2S_IDS.forEach { messenger.registerIncomingPluginChannel(plugin, it.toChannelKey(), this) }
        ServerPacketHandlers(this, SessionBridge).registerAll(registry)
    }

    fun unregister() {
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        val packetId = byChannel[channel] ?: return
        val sender = SpigotUsers.active(player.uniqueId)
            ?: SpigotUser.snapshot(player, Localization.resolveLocale(UserSessionRegistry.localeFor(player.uniqueId)))
        registry.dispatch(packetId, message, sender)
    }

    override fun sendToServer(packetId: PacketId, payload: ByteArray) = Unit

    override fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray) {
        if (UserSessionRegistry.getActive(user.id) == null && packetId !in DIRECT_RESPONSE_IDS) return
        when (val result = SpigotPayloadPolicy.fit(packetId, payload, maxPayloadSize)) {
            is SpigotPayloadPolicy.Result.Send -> {
                logLyricsStripped(packetId, result)
                send(user.id, packetId, result.payload)
            }
            is SpigotPayloadPolicy.Result.Oversized -> handleOversized(packetId, result, listOf(user.id))
        }
    }

    override fun sendToAllClients(packetId: PacketId, payload: ByteArray) {
        val users = SpigotUsers.allActive()
        when (val result = SpigotPayloadPolicy.fit(packetId, payload, maxPayloadSize)) {
            is SpigotPayloadPolicy.Result.Send -> {
                logLyricsStripped(packetId, result)
                users.forEach { send(it.id, packetId, result.payload) }
            }
            is SpigotPayloadPolicy.Result.Oversized -> handleOversized(packetId, result, users.map { it.id })
        }
    }

    private fun send(userId: UUID, packetId: PacketId, payload: ByteArray) {
        val task = Runnable {
            runCatching {
                Bukkit.getPlayer(userId)?.takeIf(Player::isOnline)
                    ?.sendPluginMessage(plugin, packetId.toChannelKey(), payload)
            }.onFailure { error ->
                plugin.logger.severe("Failed to send MoeMusic packet $packetId to $userId: ${error.message}")
            }
        }
        if (Bukkit.isPrimaryThread()) task.run() else if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, task)
    }

    private fun logLyricsStripped(packetId: PacketId, result: SpigotPayloadPolicy.Result.Send) {
        if (result.strippedLyrics) {
            plugin.logger.warning(
                "Stripped lyrics from $packetId to fit Spigot's $maxPayloadSize-byte plugin-message limit.",
            )
        }
    }

    private fun handleOversized(
        packetId: PacketId,
        result: SpigotPayloadPolicy.Result.Oversized,
        affectedUsers: Collection<UUID>,
    ) {
        plugin.logger.severe(
            "Dropping MoeMusic packet $packetId: ${result.payloadSize} bytes exceeds Spigot's " +
                "$maxPayloadSize-byte plugin-message limit after optional-field reduction.",
        )

        val identity = result.trackIdentity
        val skipKey = identity?.let { "${it.sourceId}:${it.trackId}" }
        val shouldSkip = skipKey != null && pendingOversizedTrackSkip.compareAndSet(null, skipKey)
        val notifyTask = Runnable {
            affectedUsers.forEach { userId ->
                Bukkit.getPlayer(userId)?.sendMessage(
                    "${ChatColor.RED}[MoeMusic] Playback data exceeded this Spigot version's network limit.",
                )
            }
        }
        if (Bukkit.isPrimaryThread()) notifyTask.run() else if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, notifyTask)

        if (shouldSkip && plugin.isEnabled) {
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (pendingOversizedTrackSkip.compareAndSet(skipKey, null)) {
                    val skipped = ServerRuntimeCoordinator.playbackController.skipIfCurrentTrackMatches(
                        identity.sourceId,
                        identity.trackId,
                    )
                    if (skipped) {
                        plugin.logger.warning("Skipped oversized MoeMusic track $skipKey.")
                    }
                }
            })
        }
    }

    private inner class SessionBridgeImpl : ServerPacketSessionBridge {
        override fun activate(sender: MoeMusicUser, locale: String): MoeMusicUser {
            val player = Bukkit.getPlayer(sender.id) ?: return sender.also {
                UserSessionRegistry.activate(it, locale)
            }
            return SpigotUsers.activate(player, locale)
        }

        override fun standby(sender: MoeMusicUser, locale: String): MoeMusicUser {
            val player = Bukkit.getPlayer(sender.id) ?: return sender.also {
                UserSessionRegistry.registerStandby(it, locale)
            }
            return SpigotUsers.standby(player, locale)
        }

        override fun handleRegisteredClientLeave(userId: UUID) {
            plugin.handleRegisteredClientLeave(userId)
        }
    }

    private val SessionBridge = SessionBridgeImpl()

    companion object {
        private const val LEGACY_MAX_PAYLOAD_SIZE = 32_766

        private val C2S_IDS = setOf(
            PacketIds.CLIENT_HANDSHAKE,
            PacketIds.CLIENT_STATE_CHANGE,
            PacketIds.SYNC_REQUEST,
            PacketIds.TRACK_SUBMIT,
            PacketIds.IDENTIFIER_SUBMIT,
            PacketIds.SELECTION_SUBMIT,
            PacketIds.SEARCH_REQUEST,
            PacketIds.QUEUE_REQUEST,
            PacketIds.UI_BOOTSTRAP_REQUEST,
            PacketIds.QUEUE_REMOVE_REQUEST,
            PacketIds.PLAYBACK_CONTROL_REQUEST,
            PacketIds.CONTENT_FILTER_ACTION_REQUEST,
        )

        private val DIRECT_RESPONSE_IDS = setOf(
            PacketIds.SERVER_WELCOME,
            PacketIds.TRACK_SUBMIT_RESPONSE,
            PacketIds.IDENTIFIER_SUBMIT_RESPONSE,
            PacketIds.SELECTION_SUBMIT_RESPONSE,
            PacketIds.SYNC_RESPONSE,
            PacketIds.SEARCH_RESPONSE,
            PacketIds.QUEUE_RESPONSE,
            PacketIds.UI_BOOTSTRAP_RESPONSE,
            PacketIds.QUEUE_REMOVE_RESPONSE,
            PacketIds.PLAYBACK_CONTROL_RESPONSE,
            PacketIds.CONTENT_FILTER_ACTION_RESPONSE,
        )

        private val S2C_IDS = setOf(
            PacketIds.TRACK_SUBMIT_RESPONSE,
            PacketIds.IDENTIFIER_SUBMIT_RESPONSE,
            PacketIds.SELECTION_SUBMIT_RESPONSE,
            PacketIds.SYNC_RESPONSE,
            PacketIds.SERVER_WELCOME,
            PacketIds.PLAYBACK_SNAPSHOT_PUSH,
            PacketIds.STATE_UPDATE,
            PacketIds.SEARCH_RESPONSE,
            PacketIds.QUEUE_RESPONSE,
            PacketIds.UI_BOOTSTRAP_RESPONSE,
            PacketIds.QUEUE_REMOVE_RESPONSE,
            PacketIds.PLAYBACK_CONTROL_RESPONSE,
            PacketIds.CONTENT_FILTER_ACTION_RESPONSE,
        )
    }
}
