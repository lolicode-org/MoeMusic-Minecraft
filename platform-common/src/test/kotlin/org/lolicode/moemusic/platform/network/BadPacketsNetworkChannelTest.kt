package org.lolicode.moemusic.platform.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.lolicode.moemusic.core.protocol.PacketIds

class BadPacketsNetworkChannelTest {

    @Test
    fun `standby-safe direct packets may target standby or pre-registered sessions`() {
        assertTrue(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.SERVER_WELCOME))
        assertTrue(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.SYNC_RESPONSE))
        assertTrue(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.SEARCH_RESPONSE))
        assertTrue(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.TRACK_SUBMIT_RESPONSE))
        assertTrue(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.PLAYBACK_CONTROL_RESPONSE))
    }

    @Test
    fun `playback and broadcast packets still require handshake registration`() {
        assertFalse(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.PLAYBACK_SNAPSHOT_UPDATE))
        assertFalse(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.PLAY_TRACK))
        assertFalse(BadPacketsNetworkChannel.allowsStandbyOrUnregisteredDirectSend(PacketIds.STATE_UPDATE))
    }
}
