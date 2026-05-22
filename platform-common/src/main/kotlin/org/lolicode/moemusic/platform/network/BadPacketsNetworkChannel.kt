package org.lolicode.moemusic.platform.network

import io.netty.buffer.Unpooled
import lol.bai.badpackets.api.PacketSender
import lol.bai.badpackets.api.play.PlayPackets
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.transport.NetworkChannel
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.PacketRegistry
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.platform.player.MinecraftUser
import org.lolicode.moemusic.platform.player.MinecraftUserRegistry
import org.slf4j.LoggerFactory

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
            PacketIds.SYNC_RESPONSE,
            PacketIds.SERVER_HANDSHAKE,
            PacketIds.PLAY_TRACK,
            PacketIds.SYNC_STATE,
            PacketIds.STATE_UPDATE,
            PacketIds.SEARCH_RESPONSE,
            PacketIds.QUEUE_RESPONSE,
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
            PacketIds.SEARCH_REQUEST,
            PacketIds.QUEUE_REQUEST,
            PacketIds.QUEUE_REMOVE_REQUEST,
            PacketIds.PLAYBACK_CONTROL_REQUEST,
            PacketIds.CONTENT_FILTER_ACTION_REQUEST,
        )
        for (packetId in c2sIds) {
            PlayPackets.registerServerChannel(packetId.toIdentifier())
            PlayPackets.registerServerReceiver(packetId.toIdentifier()) { ctx, buf ->
                val bytes = buf.readAvailableBytes()
                val sender = MinecraftUserRegistry.getActive(ctx.player().uuid)
                    ?: MinecraftUser(
                        ctx.player(),
                        Localization.resolveLocale(UserSessionRegistry.localeFor(ctx.player().uuid)),
                    ) // fall back for pre-active handshake/standby clients that are outside MinecraftUserRegistry
                packetRegistry.dispatch(packetId, bytes, sender)
            }
        }
    }

    // -------------------------------------------------------------------------
    // NetworkChannel implementation
    // -------------------------------------------------------------------------

    override fun sendToServer(packetId: PacketId, payload: ByteArray) {
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
        PacketSender.s2c(entity).send(packetId.toIdentifier(), FriendlyByteBuf(Unpooled.wrappedBuffer(payload)))
    }

    override fun sendToAllClients(packetId: PacketId, payload: ByteArray) {
        val identifier = packetId.toIdentifier()
        for (user in MinecraftUserRegistry.allActive()) {
            PacketSender.s2c(user.entity()).send(identifier, FriendlyByteBuf(Unpooled.wrappedBuffer(payload)))
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun canSendDirectly(user: MoeMusicUser, packetId: PacketId): Boolean =
        UserSessionRegistry.getActive(user.id) != null || allowsStandbyOrUnregisteredDirectSend(packetId)

    private fun PacketId.toIdentifier(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(namespace, path)

    private fun FriendlyByteBuf.readAvailableBytes(): ByteArray {
        val bytes = ByteArray(readableBytes())
        readBytes(bytes)
        return bytes
    }

    internal companion object {

        private val STANDBY_OR_UNREGISTERED_DIRECT_PACKET_IDS = setOf(
            PacketIds.SERVER_HANDSHAKE,
            PacketIds.TRACK_SUBMIT_RESPONSE,
            PacketIds.IDENTIFIER_SUBMIT_RESPONSE,
            PacketIds.SELECTION_SUBMIT_RESPONSE,
            PacketIds.SYNC_RESPONSE,
            PacketIds.SEARCH_RESPONSE,
            PacketIds.QUEUE_RESPONSE,
            PacketIds.QUEUE_REMOVE_RESPONSE,
            PacketIds.PLAYBACK_CONTROL_RESPONSE,
            PacketIds.CONTENT_FILTER_ACTION_RESPONSE,
        )

        internal fun allowsStandbyOrUnregisteredDirectSend(packetId: PacketId): Boolean =
            packetId in STANDBY_OR_UNREGISTERED_DIRECT_PACKET_IDS
    }
}
