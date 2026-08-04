package org.lolicode.moemusic.spigot

import org.bukkit.entity.Player
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.session.UserSessionRegistry
import java.util.UUID

class SpigotUser(
    override val id: UUID,
    override val displayName: String,
    override val locale: String,
) : MoeMusicUser() {
    override fun hasPermission(permission: String, defaultLevel: Int): Boolean =
        MoeMusicPlugin.instance.hasPermission(id, permission, defaultLevel)

    fun player(): Player? = MoeMusicPlugin.instance.server.getPlayer(id)

    fun withLocale(locale: String): SpigotUser = SpigotUser(id, displayName, locale)

    companion object {
        fun snapshot(player: Player, locale: String? = null): SpigotUser {
            val resolvedLocale = Localization.resolveLocale(
                locale ?: UserSessionRegistry.localeFor(player.uniqueId) ?: player.locale,
            )
            val existing = UserSessionRegistry.session(player.uniqueId)?.user as? SpigotUser
            return existing?.withLocale(resolvedLocale)
                ?: SpigotUser(player.uniqueId, player.name, resolvedLocale)
        }
    }
}
