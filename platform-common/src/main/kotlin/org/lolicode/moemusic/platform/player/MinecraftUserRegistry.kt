package org.lolicode.moemusic.platform.player

import net.minecraft.server.level.ServerPlayer
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Minecraft-facing adapter around [UserSessionRegistry].
 */
object MinecraftUserRegistry {

    private val logger = LoggerFactory.getLogger(MinecraftUserRegistry::class.java)

    fun onJoin(
        entity: ServerPlayer,
        locale: String = Localization.resolveLocale(null),
        protocolVersion: Int = MoeMusicProtocol.VERSION,
    ): MinecraftUser {
        val user = upsert(entity, locale, UserSessionRegistry.Participation.ACTIVE, protocolVersion)
        logger.debug("MinecraftUserRegistry: {} joined ({}) locale={} protocol={}", user.displayName, user.id, user.locale, protocolVersion)
        return user
    }

    fun onStandby(
        entity: ServerPlayer,
        locale: String = Localization.resolveLocale(null),
        protocolVersion: Int = MoeMusicProtocol.VERSION,
    ): MinecraftUser {
        val user = upsert(entity, locale, UserSessionRegistry.Participation.STANDBY, protocolVersion)
        logger.debug("MinecraftUserRegistry: {} standby ({}) locale={} protocol={}", user.displayName, user.id, user.locale, protocolVersion)
        return user
    }

    private fun upsert(
        entity: ServerPlayer,
        locale: String,
        participation: UserSessionRegistry.Participation,
        protocolVersion: Int = MoeMusicProtocol.VERSION,
    ): MinecraftUser {
        val user = snapshot(entity, locale)
        UserSessionRegistry.upsert(user, locale, participation, protocolVersion)
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

    data class ActivePlayerSession(
        val user: MinecraftUser,
        val supportsFraming: Boolean,
    )

    fun getActive(uuid: UUID): MinecraftUser? = UserSessionRegistry.getActive(uuid) as? MinecraftUser

    fun activePlayerSessions(): List<ActivePlayerSession> =
        UserSessionRegistry.activeSessions().mapNotNull { session ->
            val mcUser = session.user as? MinecraftUser ?: return@mapNotNull null
            ActivePlayerSession(mcUser, session.supportsFraming)
        }

    fun allActive(): Collection<MinecraftUser> =
        UserSessionRegistry.activeUsers().filterIsInstance<MinecraftUser>()
}
