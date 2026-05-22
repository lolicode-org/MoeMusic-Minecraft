package org.lolicode.moemusic.platform.player

import net.minecraft.server.level.ServerPlayer
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.platform.permission.PermissionResolver
import java.util.UUID

/**
 * Concrete [MoeMusicUser] wrapping a Minecraft [ServerPlayer].
 *
 * The [ServerPlayer] reference is kept private so no Minecraft type leaks into `:api` or `:core`.
 *
 * Permission resolution is delegated to [PermissionResolver], which grants the singleplayer
 * owner unconditionally, consults an injected loader-specific permission provider when one is
 * installed, and otherwise falls back to vanilla command permission levels.
 */
class MinecraftUser(
    private val entity: ServerPlayer,
    override val locale: String = Localization.resolveLocale(null),
) : MoeMusicUser() {

    override val displayName: String get() = entity.gameProfile.name

    override val id: UUID get() = entity.uuid

    override fun hasPermission(permission: String, defaultLevel: Int): Boolean =
        PermissionResolver.hasPermission(entity, permission, defaultLevel)

    internal fun withLocale(locale: String): MinecraftUser = MinecraftUser(entity, locale)

    /** The underlying Minecraft entity, exposed internally so platform code can use it. */
    internal fun entity(): ServerPlayer = entity
}
