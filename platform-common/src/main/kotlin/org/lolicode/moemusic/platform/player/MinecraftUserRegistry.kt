package org.lolicode.moemusic.platform.player

import net.minecraft.server.level.ServerPlayer
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Minecraft-facing adapter around [UserSessionRegistry].
 */
object MinecraftUserRegistry {

    private val logger = LoggerFactory.getLogger(MinecraftUserRegistry::class.java)

    fun onJoin(entity: ServerPlayer, locale: String = Localization.resolveLocale(null)): MinecraftUser {
        val user = upsert(entity, locale, UserSessionRegistry.Participation.ACTIVE)
        logger.debug("MinecraftUserRegistry: {} joined ({}) locale={}", user.displayName, user.id, user.locale)
        return user
    }

    fun onStandby(entity: ServerPlayer, locale: String = Localization.resolveLocale(null)): MinecraftUser {
        val user = upsert(entity, locale, UserSessionRegistry.Participation.STANDBY)
        logger.debug("MinecraftUserRegistry: {} standby ({}) locale={}", user.displayName, user.id, user.locale)
        return user
    }

    private fun upsert(
        entity: ServerPlayer,
        locale: String,
        participation: UserSessionRegistry.Participation,
    ): MinecraftUser {
        val user = snapshot(entity, locale)
        UserSessionRegistry.upsert(user, locale, participation)
        return user
    }

    fun snapshot(
        entity: ServerPlayer,
        locale: String = Localization.resolveLocale(UserSessionRegistry.localeFor(entity.uuid)),
    ): MinecraftUser {
        val existing = UserSessionRegistry.session(entity.uuid)?.user as? MinecraftUser
        return existing?.withLocale(locale) ?: MinecraftUser(entity, locale)
    }

    fun onLeave(uuid: UUID) {
        val user = UserSessionRegistry.disconnect(uuid)?.user as? MinecraftUser ?: return
        logger.debug("MinecraftUserRegistry: {} left ({})", user.displayName, user.id)
    }

    fun getActive(uuid: UUID): MinecraftUser? = UserSessionRegistry.getActive(uuid) as? MinecraftUser

    fun allActive(): Collection<MinecraftUser> =
        UserSessionRegistry.activeUsers().filterIsInstance<MinecraftUser>()
}
