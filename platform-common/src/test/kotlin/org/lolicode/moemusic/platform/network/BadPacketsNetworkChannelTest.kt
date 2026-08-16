package org.lolicode.moemusic.platform.network

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.session.UserSessionRegistry

private val nonHandshakeClientPacketIds = listOf(
    PacketIds.CLIENT_STATE_CHANGE,
    PacketIds.SYNC_REQUEST,
    PacketIds.TRACK_SUBMIT,
    PacketIds.IDENTIFIER_SUBMIT,
    PacketIds.SELECTION_SUBMIT,
    PacketIds.SEARCH_REQUEST,
    PacketIds.QUEUE_REQUEST,
    PacketIds.UI_BOOTSTRAP_REQUEST,
    PacketIds.QUEUE_REMOVE_REQUEST,
    PacketIds.QUEUE_CLEAR_REQUEST,
    PacketIds.PLAYBACK_CONTROL_REQUEST,
    PacketIds.CONTENT_FILTER_ACTION_REQUEST,
)

class BadPacketsNetworkChannelTest {

    @BeforeTest
    fun resetSessionsBeforeTest() {
        UserSessionRegistry.clear()
    }

    @AfterTest
    fun resetSessionsAfterTest() {
        UserSessionRegistry.clear()
    }

    @Test
    fun `standby-safe direct packets may target standby or pre-registered sessions`() {
        assertTrue(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.SERVER_WELCOME))
        assertTrue(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.SYNC_RESPONSE))
        assertTrue(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.SEARCH_RESPONSE))
        assertTrue(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.TRACK_SUBMIT_RESPONSE))
        assertTrue(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.QUEUE_CLEAR_RESPONSE))
        assertTrue(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.PLAYBACK_CONTROL_RESPONSE))
    }

    @Test
    fun `playback and broadcast packets still require handshake registration`() {
        assertFalse(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.PLAYBACK_SNAPSHOT_PUSH))
        assertFalse(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.STATE_UPDATE))
    }

    @Test
    fun `only the handshake channel is accepted before peer registration`() {
        val userId = UUID.randomUUID()

        assertTrue(BadPacketsNetworkChannel.allowsInboundPacket(PacketIds.CLIENT_HANDSHAKE, userId))
        assertTrue(nonHandshakeClientPacketIds.all { packetId ->
            !BadPacketsNetworkChannel.allowsInboundPacket(packetId, userId)
        })
    }

    @Test
    fun `all client channels are accepted after a standby handshake`() {
        val userId = UUID.randomUUID()
        UserSessionRegistry.registerStandby(testUser(userId))

        assertTrue(nonHandshakeClientPacketIds.all { packetId ->
            BadPacketsNetworkChannel.allowsInboundPacket(packetId, userId)
        })
    }

    private fun testUser(id: UUID): MoeMusicUser = object : MoeMusicUser() {
        override val displayName: String = "Test Player"
        override val id: UUID = id
        override val locale: String = "en_us"

        override fun hasPermission(permission: String, defaultLevel: Int): Boolean = false
    }
}
