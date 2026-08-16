package org.lolicode.moemusic.velocity

import com.velocitypowered.api.proxy.Player
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.session.UserSessionRegistry
import java.util.UUID

object VelocityUsers {
    fun activate(player: Player, locale: String, protocolVersion: Int = MoeMusicProtocol.VERSION): VelocityUser =
        VelocityUser.snapshot(player, locale).also {
            UserSessionRegistry.activate(it, it.locale, protocolVersion)
        }

    fun standby(player: Player, locale: String, protocolVersion: Int = MoeMusicProtocol.VERSION): VelocityUser =
        VelocityUser.snapshot(player, locale).also {
            UserSessionRegistry.registerStandby(it, it.locale, protocolVersion)
        }

    data class ActivePlayerSession(
        val user: VelocityUser,
        val supportsFraming: Boolean,
    )

    fun active(id: UUID): VelocityUser? = UserSessionRegistry.getActive(id) as? VelocityUser

    fun activePlayerSessions(): List<ActivePlayerSession> =
        UserSessionRegistry.activeSessions().mapNotNull { session ->
            val user = session.user as? VelocityUser ?: return@mapNotNull null
            ActivePlayerSession(user, session.supportsFraming)
        }

    fun allActive(): List<VelocityUser> = UserSessionRegistry.activeUsers().filterIsInstance<VelocityUser>()
}

