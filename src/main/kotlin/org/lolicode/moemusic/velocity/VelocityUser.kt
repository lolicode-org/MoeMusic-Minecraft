package org.lolicode.moemusic.velocity

import com.velocitypowered.api.proxy.Player
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.session.UserSessionRegistry
import java.util.UUID

class VelocityUser(
    override val id: UUID,
    override val displayName: String,
    override val locale: String,
) : MoeMusicUser() {
    override fun hasPermission(permission: String, defaultLevel: Int): Boolean =
        MoeMusicVelocityPlugin.instance.hasPermission(id, permission, defaultLevel)

    fun player(): Player? = MoeMusicVelocityPlugin.instance.proxy.getPlayer(id).orElse(null)

    fun withLocale(locale: String): VelocityUser = VelocityUser(id, displayName, locale)

    companion object {
        fun snapshot(player: Player, locale: String? = null): VelocityUser {
            val resolvedLocale = Localization.resolveLocale(
                locale ?: UserSessionRegistry.localeFor(player.uniqueId) ?: player.effectiveLocale.toMinecraftLocale(),
            )
            val existing = UserSessionRegistry.session(player.uniqueId)?.user as? VelocityUser
            return existing?.withLocale(resolvedLocale)
                ?: VelocityUser(player.uniqueId, player.username, resolvedLocale)
        }

        private fun java.util.Locale?.toMinecraftLocale(): String =
            this?.toLanguageTag()?.replace('-', '_')?.lowercase() ?: Localization.defaultLocale()
    }
}

