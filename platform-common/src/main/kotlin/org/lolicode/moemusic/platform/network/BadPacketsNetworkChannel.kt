package org.lolicode.moemusic.platform.network

import io.netty.buffer.Unpooled
import lol.bai.badpackets.api.PacketSender
import lol.bai.badpackets.api.play.PlayPackets
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.Identifier
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.transport.FramedPayloadCodec
import org.lolicode.moemusic.core.transport.NetworkChannel
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.PacketRegistry
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.platform.player.MinecraftUser
import org.lolicode.moemusic.platform.player.MinecraftUserRegistry
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [NetworkChannel] implementation backed by bad packets.
 *
 * - **S→C sending**: uses [PacketSender.s2c] (single player) or iterates [MinecraftUserRegistry.allActive]
 *   (broadcast).
 * - **C→S receiving**: registered via [PlayPackets.registerServerReceiver]; decoded bytes are
 *   dispatched to [PacketRegistry].
 *
 * Active playback participation still flows through [MinecraftUserRegistry]. Standby clients remain
 * session-registered for locale-aware direct responses, but only active participants receive
 * playback broadcasts, count toward votes, and block auto-pause.
 *
 * Payload format: raw Wire-encoded protobuf bytes, wrapped in a [FriendlyByteBuf].
 */
class BadPacketsNetworkChannel(
    private val packetRegistry: PacketRegistry,
) : NetworkChannel {

    private val logger = LoggerFactory.getLogger(BadPacketsNetworkChannel::class.java)

    // -------------------------------------------------------------------------
    // Registration (called once during init from NetworkSetup)
    // -------------------------------------------------------------------------

    /** Register all C→S and S→C channels, then attach C→S receivers. */
    internal fun register() {
        // Declare S→C channels on the server side so the server can send them.
        // Per Bad Packets javadoc: registerClientChannel must be called on ALL sides.
        val s2cIds = listOf(
            PacketIds.TRACK_SUBMIT_RESPONSE,
            PacketIds.IDENTIFIER_SUBMIT_RESPONSE,
            PacketIds.SELECTION_SUBMIT_RESPONSE,
            PacketIds.SELECTION_PAGE_RESPONSE,
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
        for (id in s2cIds) {
            PlayPackets.registerClientChannel(id.toIdentifier())
        }

        // Declare C→S channels on the server side and register receivers.
        // Per Bad Packets Javadoc: registerServerChannel must be called on ALL sides.
        val c2sIds = listOf(
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
        for (packetId in c2sIds) {
            PlayPackets.registerServerChannel(packetId.toIdentifier())
            PlayPackets.registerServerReceiver(packetId.toIdentifier()) { ctx, buf ->
                try {
                    val player = ctx.player()
                    val session = UserSessionRegistry.session(player.uuid)
                    if (packetId != PacketIds.CLIENT_HANDSHAKE && session == null) {
                        logger.debug(
                            "Dropping packet {} from player {} before MoeMusic handshake.",
                            packetId,
                            player.uuid,
                        )
                    } else if (buf.readableBytes() > FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES) {
                        logger.debug(
                            "Dropping oversized C2S packet {} from player {} (size={})",
                            packetId,
                            player.uuid,
                            buf.readableBytes(),
                        )
                    } else {
                        val bytes = buf.readAvailableBytes()
                        val sender = session?.user as? MinecraftUser ?: MinecraftUserRegistry.snapshot(player)
                        packetRegistry.dispatch(packetId, bytes, sender)
                    }

                } catch (e: Exception) {
                    logger.error("Error handling inbound packet {}", packetId, e)
                }
            }

        }
    }

    // -------------------------------------------------------------------------
    // NetworkChannel implementation
    // -------------------------------------------------------------------------

    override fun sendToServer(packetId: PacketId, payload: ByteArray) {
        if (payload.size > FramedPayloadCodec.MAX_LEGACY_C2S_PAYLOAD_BYTES) {
            logger.warn("Dropping oversized C2S packet {} (size={})", packetId, payload.size)
            return
        }
        PacketSender.c2s().send(packetId.toIdentifier(), FriendlyByteBuf(Unpooled.wrappedBuffer(payload)))
    }

    override fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray) {
        val entity = (user as? MinecraftUser)?.entity() ?: return
        if (!canSendDirectly(user, packetId)) {
            logger.warn(
                "Dropping packet {} to inactive client session {} because it is not standby-safe.",
                packetId,
                user.displayName,
            )
            return
        }
        val framingEnabled = UserSessionRegistry.supportsFraming(user.id)
        if (!framingEnabled && payload.size > FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES) {
            logger.warn("Dropping oversized legacy S2C packet {} (size={})", packetId, payload.size)
            return
        }

        val identifier = packetId.toIdentifier()
        if (!framingEnabled) {
            try {
                PacketSender.s2c(entity).send(identifier, FriendlyByteBuf(Unpooled.wrappedBuffer(payload)))
            } catch (e: Exception) {
                logger.error("Failed to send packet {} to {}: {}", packetId, user.displayName, e.message, e)
            }
            return
        }

        if (payload.size <= FramedPayloadCodec.CHUNK_PAYLOAD_SIZE) {
            val frame = try {
                FramedPayloadCodec.encodeSingle(payload)
            } catch (e: Exception) {
                logger.error(
                    "Failed to encode framed packet {} (size={}) for client {}: {}",
                    packetId,
                    payload.size,
                    user.displayName,
                    e.message,
                )
                return
            }
            try {
                PacketSender.s2c(entity).send(identifier, FriendlyByteBuf(Unpooled.wrappedBuffer(frame)))
            } catch (e: Exception) {
                logger.error("Failed to send packet {} to {}: {}", packetId, user.displayName, e.message, e)
            }
            return
        }

        val frames = try {
            FramedPayloadCodec.encode(payload)
        } catch (e: Exception) {
            logger.error(
                "Failed to encode framed packet {} (size={}) for client {}: {}",
                packetId,
                payload.size,
                user.displayName,
                e.message,
            )
            return
        }
        val sender = try {
            PacketSender.s2c(entity)
        } catch (e: Exception) {
            logger.error("Failed to send packet {} to {}: {}", packetId, user.displayName, e.message, e)
            return
        }
        for (frame in frames) {
            try {
                sender.send(identifier, FriendlyByteBuf(Unpooled.wrappedBuffer(frame)))
            } catch (e: Exception) {
                logger.error("Failed to send packet {} to {}: {}", packetId, user.displayName, e.message, e)
            }
        }
    }

    override fun sendToAllClients(packetId: PacketId, payload: ByteArray) {
        val activeSessions = MinecraftUserRegistry.activePlayerSessions()
        if (activeSessions.isEmpty()) return
        val identifier = packetId.toIdentifier()
        val (modernSessions, legacySessions) = activeSessions.partition { it.supportsFraming }

        if (modernSessions.isNotEmpty()) {
            val singleFrame: ByteArray?
            val frames: List<ByteArray>?
            if (payload.size <= FramedPayloadCodec.CHUNK_PAYLOAD_SIZE) {
                singleFrame = try {
                    FramedPayloadCodec.encodeSingle(payload)
                } catch (e: Exception) {
                    logger.error(
                        "Failed to encode framed broadcast packet {} (size={}): {}",
                        packetId,
                        payload.size,
                        e.message,
                    )
                    null
                }
                frames = null
            } else {
                singleFrame = null
                frames = try {
                    FramedPayloadCodec.encode(payload)
                } catch (e: Exception) {
                    logger.error(
                        "Failed to encode framed broadcast packet {} (size={}): {}",
                        packetId,
                        payload.size,
                        e.message,
                    )
                    null
                }
            }
            if (singleFrame != null || frames != null) {
                for (session in modernSessions) {
                    try {
                        val sender = PacketSender.s2c(session.user.entity())
                        if (singleFrame != null) {
                            sender.send(identifier, FriendlyByteBuf(Unpooled.wrappedBuffer(singleFrame)))
                        } else {
                            for (frame in requireNotNull(frames)) {
                                sender.send(identifier, FriendlyByteBuf(Unpooled.wrappedBuffer(frame)))
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to send broadcast packet {} to {}: {}", packetId, session.user.displayName, e.message, e)
                    }
                }
            }
        }

        if (legacySessions.isNotEmpty()) {
            if (payload.size > FramedPayloadCodec.MAX_LEGACY_S2C_PAYLOAD_BYTES) {
                logger.warn("Dropping oversized legacy S2C broadcast packet {} (size={})", packetId, payload.size)
            } else {
                for (session in legacySessions) {
                    try {
                        val sender = PacketSender.s2c(session.user.entity())
                        sender.send(identifier, FriendlyByteBuf(Unpooled.wrappedBuffer(payload)))
                    } catch (e: Exception) {
                        logger.error("Failed to send broadcast packet {} to {}: {}", packetId, session.user.displayName, e.message, e)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun canSendDirectly(user: MoeMusicUser, packetId: PacketId): Boolean =
        UserSessionRegistry.getActive(user.id) != null || allowsStandbyOrUnregisteredDirectSend(packetId)

    private val identifierCache = ConcurrentHashMap<PacketId, Identifier>()
    private fun PacketId.toIdentifier(): Identifier =
        identifierCache.computeIfAbsent(this) { Identifier.fromNamespaceAndPath(it.namespace, it.path) }

    private fun FriendlyByteBuf.readAvailableBytes(): ByteArray {
        val bytes = ByteArray(readableBytes())
        readBytes(bytes)
        return bytes
    }

    internal companion object {

        private val STANDBY_OR_UNREGISTERED_DIRECT_PACKET_IDS = setOf(
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

        internal fun allowsInboundPacket(packetId: PacketId, userId: UUID): Boolean =
            packetId == PacketIds.CLIENT_HANDSHAKE || UserSessionRegistry.session(userId) != null

        internal fun allowsStandbyOrUnregisteredDirectSend(packetId: PacketId): Boolean =
            packetId in STANDBY_OR_UNREGISTERED_DIRECT_PACKET_IDS
    }
}
