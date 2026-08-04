package org.lolicode.moemusic.spigot

import org.bukkit.entity.Player
import org.lolicode.moemusic.core.session.UserSessionRegistry
import java.util.UUID

object SpigotUsers {
    fun activate(player: Player, locale: String): SpigotUser =
        SpigotUser.snapshot(player, locale).also {
            UserSessionRegistry.activate(it, it.locale)
        }

    fun standby(player: Player, locale: String): SpigotUser =
        SpigotUser.snapshot(player, locale).also {
            UserSessionRegistry.registerStandby(it, it.locale)
        }

    fun active(id: UUID): SpigotUser? = UserSessionRegistry.getActive(id) as? SpigotUser

    fun allActive(): List<SpigotUser> = UserSessionRegistry.activeUsers().filterIsInstance<SpigotUser>()
}
