package org.lolicode.moemusic.spigot

import org.bukkit.entity.Player
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.session.UserSessionRegistry
import java.util.UUID

object SpigotUsers {
    fun activate(player: Player, locale: String, protocolVersion: Int = MoeMusicProtocol.VERSION): SpigotUser =
        SpigotUser.snapshot(player, locale).also {
            UserSessionRegistry.activate(it, it.locale, protocolVersion)
        }

    fun standby(player: Player, locale: String, protocolVersion: Int = MoeMusicProtocol.VERSION): SpigotUser =
        SpigotUser.snapshot(player, locale).also {
            UserSessionRegistry.registerStandby(it, it.locale, protocolVersion)
        }

    data class ActivePlayerSession(
        val user: SpigotUser,
        val supportsFraming: Boolean,
    )

    fun active(id: UUID): SpigotUser? = UserSessionRegistry.getActive(id) as? SpigotUser

    fun activePlayerSessions(): List<ActivePlayerSession> =
        UserSessionRegistry.activeSessions().mapNotNull { session ->
            val user = session.user as? SpigotUser ?: return@mapNotNull null
            ActivePlayerSession(user, session.supportsFraming)
        }

    fun allActive(): List<SpigotUser> = UserSessionRegistry.activeUsers().filterIsInstance<SpigotUser>()
}
