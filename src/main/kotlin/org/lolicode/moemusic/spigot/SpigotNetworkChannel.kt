package org.lolicode.moemusic.spigot

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.Messenger
import org.bukkit.plugin.messaging.PluginMessageListener
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.network.ServerPacketHandlers
import org.lolicode.moemusic.core.network.ServerPacketSessionBridge
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.PacketRegistry
import org.lolicode.moemusic.core.runtime.ServerRuntimeCoordinator
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.transport.FramedPayloadCodec
import org.lolicode.moemusic.core.transport.NetworkChannel
import java.util.UUID
import java.util.logging.Level
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class SpigotNetworkChannel(
    private val plugin: MoeMusicPlugin,
) : NetworkChannel, PluginMessageListener {
    private val registry = PacketRegistry()
    private val byChannel = PacketIds.ALL.associateBy(PacketId::toChannelKey)
    private val pendingOversizedTrackSkip = AtomicReference<String?>()
    private val unregisteredChannelWarnings = ConcurrentHashMap.newKeySet<String>()
    private val maxPayloadSize: Int = runCatching {
        Messenger::class.java.getField("MAX_MESSAGE_SIZE").getInt(null)
    }.getOrElse {
        plugin.logger.warning("Could not read Spigot's plugin-message limit; using the 1.18.2 limit.")
        FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES
    }


    fun register() {
        require(maxPayloadSize >= FramedPayloadCodec.MAX_CHUNK_FRAME_BYTES) {
            "Spigot plugin-message limit $maxPayloadSize is below required v3 chunk frame size " +
                FramedPayloadCodec.MAX_CHUNK_FRAME_BYTES
        }

        val messenger = plugin.server.messenger
        S2C_IDS.forEach { messenger.registerOutgoingPluginChannel(plugin, it.toChannelKey()) }
        C2S_IDS.forEach { messenger.registerIncomingPluginChannel(plugin, it.toChannelKey(), this) }
        ServerPacketHandlers(this, SessionBridge).registerAll(registry)
    }

    fun unregister() {
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin)
    }

    fun forgetPlayer(userId: UUID) {
        unregisteredChannelWarnings.removeIf { it.startsWith("$userId:") }
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        try {
            val packetId = byChannel[channel] ?: return
            if (packetId != PacketIds.CLIENT_HANDSHAKE && UserSessionRegistry.session(player.uniqueId) == null) {
                if (plugin.logger.isLoggable(Level.FINE)) {
                    plugin.logger.fine(
                        "Dropping packet $packetId from ${player.uniqueId} before the MoeMusic handshake.",
                    )
                }
                return
            }
            val inboundLimit = minOf(maxPayloadSize, FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES)
            if (message.size > inboundLimit) {
                plugin.logger.warning(
                    "Dropping oversized C2S packet $packetId from ${player.uniqueId} (size=${message.size}, limit=$inboundLimit)",
                )
                return
            }

            val sender = SpigotUsers.active(player.uniqueId)
                ?: SpigotUser.snapshot(player, Localization.resolveLocale(UserSessionRegistry.localeFor(player.uniqueId)))
            registry.dispatch(packetId, message, sender)
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Error handling inbound plugin message on channel $channel", e)
        }
    }

    override fun sendToServer(packetId: PacketId, payload: ByteArray) = Unit

    override fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray) {
        if (UserSessionRegistry.getActive(user.id) == null && packetId !in DIRECT_RESPONSE_IDS) return
        if (UserSessionRegistry.supportsFraming(user.id)) {
            val frames = try {
                FramedPayloadCodec.encode(payload)
            } catch (e: Exception) {
                plugin.logger.severe(
                    "Failed to encode framed packet $packetId (size=${payload.size}) for client ${user.displayName}: ${e.message}",
                )
                return
            }
            if (frames.any { it.size > maxPayloadSize }) {
                plugin.logger.severe(
                    "Dropping MoeMusic packet $packetId: framed payload chunk exceeds Spigot's " +
                        "$maxPayloadSize-byte plugin-message limit.",
                )
                return
            }
            frames.forEach { frame -> send(user.id, packetId, frame) }
            return
        }
        when (val result = SpigotPayloadPolicy.fit(
            packetId,
            payload,
            minOf(maxPayloadSize, FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES),
        )) {
            is SpigotPayloadPolicy.Result.Send -> {
                logLyricsStripped(packetId, result)
                send(user.id, packetId, result.payload)
            }
            is SpigotPayloadPolicy.Result.Oversized -> handleOversized(packetId, result, listOf(user.id))
        }
    }

    override fun sendToAllClients(packetId: PacketId, payload: ByteArray) {
        val users = SpigotUsers.allActive()
        val (modernUsers, legacyUsers) = users.partition { UserSessionRegistry.supportsFraming(it.id) }

        if (modernUsers.isNotEmpty()) {
            val frames = try {
                FramedPayloadCodec.encode(payload)
            } catch (e: Exception) {
                plugin.logger.severe(
                    "Failed to encode framed broadcast packet $packetId (size=${payload.size}): ${e.message}",
                )
                null
            }
            if (frames != null) {
                if (frames.any { it.size > maxPayloadSize }) {
                    // Note: This drops the broadcast without triggering the Oversized track skip fallback.
                    // However, FramedPayloadCodec strictly bounds chunk frames to ~30 KB. This branch will 
                    // only ever be hit if a custom Spigot server forces maxPayloadSize below ~30 KB, 
                    // in which case the plugin would be fundamentally unsupportable anyway.
                    plugin.logger.severe(
                        "Dropping MoeMusic packet $packetId: framed payload chunk exceeds Spigot's " +
                            "$maxPayloadSize-byte plugin-message limit.",
                    )
                } else {
                    modernUsers.forEach { user ->
                        frames.forEach { frame -> send(user.id, packetId, frame) }
                    }
                }
            }
        }

        if (legacyUsers.isNotEmpty()) {
            when (val result = SpigotPayloadPolicy.fit(
                packetId,
                payload,
                minOf(maxPayloadSize, FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES),
            )) {
                is SpigotPayloadPolicy.Result.Send -> {
                    logLyricsStripped(packetId, result)
                    legacyUsers.forEach { send(it.id, packetId, result.payload) }
                }
                is SpigotPayloadPolicy.Result.Oversized -> handleOversized(packetId, result, legacyUsers.map { it.id })
            }
        }
    }

    private fun send(userId: UUID, packetId: PacketId, payload: ByteArray) {
        val task = Runnable {
            runCatching {
                Bukkit.getPlayer(userId)?.takeIf(Player::isOnline)?.let { player ->
                    val channel = packetId.toChannelKey()
                    if (channel in player.listeningPluginChannels) {
                        player.sendPluginMessage(plugin, channel, payload)
                    } else if (unregisteredChannelWarnings.add("$userId:$channel")) {
                        plugin.logger.warning(
                            "Client ${player.name} did not register $channel; dropping the MoeMusic packet. " +
                                "Update the MoeMusic client for this Minecraft version.",
                        )
                    }
                }
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
        override fun activate(sender: MoeMusicUser, locale: String, protocolVersion: Int): MoeMusicUser {
            val player = Bukkit.getPlayer(sender.id) ?: return sender.also {
                UserSessionRegistry.activate(it, locale, protocolVersion)
            }
            return SpigotUsers.activate(player, locale, protocolVersion)
        }

        override fun standby(sender: MoeMusicUser, locale: String, protocolVersion: Int): MoeMusicUser {
            val player = Bukkit.getPlayer(sender.id) ?: return sender.also {
                UserSessionRegistry.registerStandby(it, locale, protocolVersion)
            }
            return SpigotUsers.standby(player, locale, protocolVersion)
        }

        override fun handleRegisteredClientLeave(userId: UUID) {
            plugin.handleRegisteredClientLeave(userId)
        }

        override fun notifyOutdatedClient(user: MoeMusicUser, clientProtocolVersion: Int) {
            val message = LocalizedText.key("action.moemusic.protocol.outdated_client", clientProtocolVersion)
            val text = Localization.render(user.locale, message)
            val task = Runnable {
                Bukkit.getPlayer(user.id)?.sendMessage("§7[MoeMusic] §f$text")
            }
            if (Bukkit.isPrimaryThread()) task.run() else if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, task)
        }
    }

    private val SessionBridge = SessionBridgeImpl()

    companion object {

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
