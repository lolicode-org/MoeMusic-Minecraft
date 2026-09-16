package org.lolicode.moemusic.spigot

import org.bukkit.entity.Player
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.session.UserSessionRegistry
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

object SpigotUsers {
    private val logger = Logger.getLogger("MoeMusic")

    fun activate(player: Player, locale: String, protocolVersion: Int = MoeMusicProtocol.VERSION): SpigotUser =
        SpigotUser.snapshot(player, locale).also {
            UserSessionRegistry.activate(it, it.locale, protocolVersion)
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("SpigotUsers: ${it.displayName} joined (${it.id}) locale=${it.locale} protocol=$protocolVersion")
            }
        }

    fun standby(player: Player, locale: String, protocolVersion: Int = MoeMusicProtocol.VERSION): SpigotUser =
        SpigotUser.snapshot(player, locale).also {
            UserSessionRegistry.registerStandby(it, it.locale, protocolVersion)
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("SpigotUsers: ${it.displayName} standby (${it.id}) locale=${it.locale} protocol=$protocolVersion")
            }
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
