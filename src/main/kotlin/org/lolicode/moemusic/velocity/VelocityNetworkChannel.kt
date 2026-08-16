package org.lolicode.moemusic.velocity

import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent.ForwardResult
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class VelocityNetworkChannel(
    private val plugin: MoeMusicVelocityPlugin,
) : NetworkChannel {
    private val registry = PacketRegistry()
    private val identifiers = PacketIds.ALL.associateWith {
        MinecraftChannelIdentifier.from(it.toChannelKey())
    }
    private val byIdentifier = identifiers.entries.associate { (packetId, identifier) -> identifier to packetId }
    private val pendingOversizedTrackSkip = AtomicReference<String?>()
    private val unregisteredChannelWarnings = ConcurrentHashMap.newKeySet<String>()
    private val maxPayloadSize: Int = System.getProperty(MAX_PAYLOAD_PROPERTY)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: DEFAULT_MAX_PAYLOAD_SIZE

    fun register() {
        require(maxPayloadSize >= FramedPayloadCodec.MAX_CHUNK_FRAME_BYTES) {
            "Velocity max plugin-message payload size $maxPayloadSize is below required v3 chunk frame size " +
                FramedPayloadCodec.MAX_CHUNK_FRAME_BYTES
        }

        plugin.proxy.channelRegistrar.register(*identifiers.values.toTypedArray())
        ServerPacketHandlers(this, SessionBridge()).registerAll(registry)
        plugin.logger.info("Registered ${identifiers.size} MoeMusic Velocity plugin-message channels.")
    }

    fun unregister() {
        plugin.proxy.channelRegistrar.unregister(*identifiers.values.toTypedArray())
    }

    fun forgetPlayer(userId: UUID) {
        unregisteredChannelWarnings.removeIf { it.startsWith("$userId:") }
    }

    fun handle(event: PluginMessageEvent) {
        try {
            val packetId = byIdentifier[event.identifier] ?: return

            // MoeMusic packets are terminated at the proxy. Forwarding a client-originated packet to
            // a backend would permit a backend or another proxy plugin to reinterpret its sender.
            event.result = ForwardResult.handled()
            val player = event.source as? Player ?: return
            if (packetId !in C2S_IDS) return
            val session = UserSessionRegistry.session(player.uniqueId)
            if (packetId != PacketIds.CLIENT_HANDSHAKE && session == null) {
                plugin.logger.debug(
                    "Dropping packet {} from {} before the MoeMusic handshake.",
                    packetId,
                    player.uniqueId,
                )
                return
            }

            val inboundLimit = minOf(maxPayloadSize, FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES)
            if (event.data.size > inboundLimit) {
                plugin.logger.warn(
                    "Dropping oversized C2S packet {} from {} (size={}, limit={})",
                    packetId,
                    player.uniqueId,
                    event.data.size,
                    inboundLimit,
                )
                return
            }

            val sender = session?.user as? VelocityUser
                ?: VelocityUsers.active(player.uniqueId)
                ?: VelocityUser.snapshot(
                    player,
                    Localization.resolveLocale(UserSessionRegistry.localeFor(player.uniqueId)),
                )
            registry.dispatch(packetId, event.data, sender)
        } catch (e: Exception) {
            plugin.logger.error("Error handling inbound plugin message on channel {}", event.identifier.id, e)
        }
    }

    override fun sendToServer(packetId: PacketId, payload: ByteArray) = Unit

    override fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray) {
        if (UserSessionRegistry.getActive(user.id) == null && packetId !in DIRECT_RESPONSE_IDS) return
        val player = plugin.proxy.getPlayer(user.id).orElse(null) ?: return
        val identifier = identifiers[packetId] ?: return
        if (UserSessionRegistry.supportsFraming(user.id)) {
            if (payload.size <= FramedPayloadCodec.CHUNK_PAYLOAD_SIZE) {
                val frame = try {
                    FramedPayloadCodec.encodeSingle(payload)
                } catch (e: Exception) {
                    plugin.logger.error(
                        "Failed to encode framed packet $packetId (size=${payload.size}) for client ${user.displayName}: ${e.message}",
                    )
                    return
                }
                send(player, identifier, packetId, frame)
                return
            }
            val frames = try {
                FramedPayloadCodec.encode(payload)
            } catch (e: Exception) {
                plugin.logger.error(
                    "Failed to encode framed packet $packetId (size=${payload.size}) for client ${user.displayName}: ${e.message}",
                )
                return
            }
            if (frames.any { it.size > maxPayloadSize }) {
                plugin.logger.error(
                    "Dropping MoeMusic packet $packetId: framed payload chunk exceeds Velocity's configured " +
                        "$maxPayloadSize-byte plugin-message limit.",
                )
                return
            }
            frames.forEach { frame -> send(player, identifier, packetId, frame) }
            return
        }
        when (val result = VelocityPayloadPolicy.fit(
            packetId,
            payload,
            minOf(maxPayloadSize, FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES),
        )) {
            is VelocityPayloadPolicy.Result.Send -> {
                logLyricsStripped(packetId, result)
                send(player, identifier, packetId, result.payload)
            }

            is VelocityPayloadPolicy.Result.Oversized ->
                handleOversized(packetId, result, listOf(user.id))
        }
    }

    override fun sendToAllClients(packetId: PacketId, payload: ByteArray) {
        val activeSessions = VelocityUsers.activePlayerSessions()
        if (activeSessions.isEmpty()) return
        val (modernSessions, legacySessions) = activeSessions.partition { it.supportsFraming }

        if (modernSessions.isNotEmpty()) {
            val singleFrame: ByteArray?
            val frames: List<ByteArray>?
            if (payload.size <= FramedPayloadCodec.CHUNK_PAYLOAD_SIZE) {
                singleFrame = try {
                    FramedPayloadCodec.encodeSingle(payload)
                } catch (e: Exception) {
                    plugin.logger.error(
                        "Failed to encode framed broadcast packet $packetId (size=${payload.size}): ${e.message}",
                    )
                    null
                }
                frames = null
            } else {
                singleFrame = null
                frames = try {
                    FramedPayloadCodec.encode(payload)
                } catch (e: Exception) {
                    plugin.logger.error(
                        "Failed to encode framed broadcast packet $packetId (size=${payload.size}): ${e.message}",
                    )
                    null
                }
            }
            if (singleFrame != null || frames != null) {
                if (frames?.any { it.size > maxPayloadSize } == true) {
                    plugin.logger.error(
                        "Dropping MoeMusic packet $packetId: framed payload chunk exceeds Velocity's configured " +
                            "$maxPayloadSize-byte plugin-message limit.",
                    )
                } else {
                    val identifier = identifiers[packetId]
                    if (identifier != null) {
                        modernSessions.forEach { session ->
                            val player = plugin.proxy.getPlayer(session.user.id).orElse(null) ?: return@forEach
                            if (singleFrame != null) {
                                send(player, identifier, packetId, singleFrame)
                            } else {
                                requireNotNull(frames).forEach { frame ->
                                    send(player, identifier, packetId, frame)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (legacySessions.isNotEmpty()) {
            when (val result = VelocityPayloadPolicy.fit(
                packetId,
                payload,
                minOf(maxPayloadSize, FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES),
            )) {
                is VelocityPayloadPolicy.Result.Send -> {
                    logLyricsStripped(packetId, result)
                    legacySessions.forEach { session -> send(session.user.id, packetId, result.payload) }
                }

                is VelocityPayloadPolicy.Result.Oversized ->
                    handleOversized(packetId, result, legacySessions.map { it.user.id })
            }
        }
    }

    private fun send(userId: UUID, packetId: PacketId, payload: ByteArray) {
        val player = plugin.proxy.getPlayer(userId).orElse(null) ?: return
        val identifier = identifiers[packetId] ?: return
        send(player, identifier, packetId, payload)
    }

    private fun send(
        player: Player,
        identifier: MinecraftChannelIdentifier,
        packetId: PacketId,
        payload: ByteArray,
    ) {
        runCatching {
            if (!player.sendPluginMessage(identifier, payload) &&
                unregisteredChannelWarnings.add("${player.uniqueId}:${packetId.toChannelKey()}")
            ) {
                plugin.logger.warn(
                    "Client ${player.username} did not register ${packetId.toChannelKey()}; " +
                        "dropping the MoeMusic packet. Update the MoeMusic client.",
                )
            }
        }.onFailure { error ->
            plugin.logger.error("Failed to send MoeMusic packet $packetId to ${player.uniqueId}.", error)
        }
    }

    private fun logLyricsStripped(packetId: PacketId, result: VelocityPayloadPolicy.Result.Send) {
        if (result.strippedLyrics) {
            plugin.logger.warn(
                "Stripped lyrics from $packetId to fit Velocity's $maxPayloadSize-byte plugin-message limit.",
            )
        }
    }

    private fun handleOversized(
        packetId: PacketId,
        result: VelocityPayloadPolicy.Result.Oversized,
        affectedUsers: Collection<UUID>,
    ) {
        plugin.logger.error(
            "Dropping MoeMusic packet $packetId: ${result.payloadSize} bytes exceeds Velocity's " +
                "$maxPayloadSize-byte plugin-message limit after optional-field reduction.",
        )

        val identity = result.trackIdentity
        val skipKey = identity?.let { "${it.sourceId}:${it.trackId}" }
        val shouldSkip = skipKey != null && pendingOversizedTrackSkip.compareAndSet(null, skipKey)
        plugin.runOnProxyThread {
            affectedUsers.forEach { userId ->
                plugin.proxy.getPlayer(userId).ifPresent {
                    VelocityChat.message(
                        it,
                        "§c[MoeMusic] Playback data exceeded Velocity's network limit.",
                    )
                }
            }
        }

        if (!shouldSkip) return
        val trackIdentity = requireNotNull(identity)
        val key = requireNotNull(skipKey)
        plugin.schedule {
            if (pendingOversizedTrackSkip.compareAndSet(key, null)) {
                val skipped = ServerRuntimeCoordinator.playbackController.skipIfCurrentTrackMatches(
                    trackIdentity.sourceId,
                    trackIdentity.trackId,
                )
                if (skipped) plugin.logger.warn("Skipped oversized MoeMusic track $key.")
            }
        }
    }

    private inner class SessionBridge : ServerPacketSessionBridge {
        override fun activate(sender: MoeMusicUser, locale: String, protocolVersion: Int): MoeMusicUser {
            val player = plugin.proxy.getPlayer(sender.id).orElse(null) ?: return sender.also {
                UserSessionRegistry.activate(it, locale, protocolVersion)
            }
            return VelocityUsers.activate(player, locale, protocolVersion)
        }

        override fun standby(sender: MoeMusicUser, locale: String, protocolVersion: Int): MoeMusicUser {
            val player = plugin.proxy.getPlayer(sender.id).orElse(null) ?: return sender.also {
                UserSessionRegistry.registerStandby(it, locale, protocolVersion)
            }
            return VelocityUsers.standby(player, locale, protocolVersion)
        }

        override fun handleRegisteredClientLeave(userId: UUID) {
            plugin.handleRegisteredClientLeave(userId)
        }

        override fun notifyOutdatedClient(user: MoeMusicUser, clientProtocolVersion: Int) {
            val message = LocalizedText.key("action.moemusic.protocol.outdated_client", clientProtocolVersion)
            plugin.runOnProxyThread {
                plugin.proxy.getPlayer(user.id).ifPresent { player ->
                    VelocityChat.message(
                        player,
                        VelocityChatFormatting.prefixed(user.locale, message, VelocityChatFormatting.Tone.NEUTRAL),
                    )
                }
            }
        }
    }

    companion object {
        private const val MAX_PAYLOAD_PROPERTY = "velocity.max-plugin-message-payload-size"
        private const val DEFAULT_MAX_PAYLOAD_SIZE = 32_767

        private val C2S_IDS = setOf(
            PacketIds.CLIENT_HANDSHAKE,
            PacketIds.CLIENT_STATE_CHANGE,
            PacketIds.SYNC_REQUEST,
            PacketIds.TRACK_SUBMIT,
            PacketIds.IDENTIFIER_SUBMIT,
            PacketIds.SELECTION_SUBMIT,
            PacketIds.SELECTION_PAGE_REQUEST,
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
            PacketIds.SELECTION_PAGE_RESPONSE,
            PacketIds.SYNC_RESPONSE,
            PacketIds.SEARCH_RESPONSE,
            PacketIds.QUEUE_RESPONSE,
            PacketIds.UI_BOOTSTRAP_RESPONSE,
            PacketIds.QUEUE_REMOVE_RESPONSE,
            PacketIds.PLAYBACK_CONTROL_RESPONSE,
            PacketIds.CONTENT_FILTER_ACTION_RESPONSE,
        )
    }
}
