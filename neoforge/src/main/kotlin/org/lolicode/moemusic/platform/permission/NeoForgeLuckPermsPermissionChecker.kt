package org.lolicode.moemusic.platform.permission

import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.util.Tristate
import net.minecraft.server.level.ServerPlayer

/**
 * Optional LuckPerms-backed permission bridge for all NeoForge-side permission checks.
 *
 * NeoForge's built-in PermissionAPI requires nodes to be pre-registered during startup and
 * does not fit MoeMusic's plugin-facing arbitrary permission-string checks, so NeoForge uses
 * a simpler policy: query LuckPerms directly when present, otherwise fall back to vanilla.
 */
internal object NeoForgeLuckPermsPermissionChecker : ExternalStringPermissionChecker {
    override fun hasPermission(player: ServerPlayer, permission: String): Boolean? {
        val api = runCatching { LuckPermsProvider.get() }.getOrNull() ?: return null
        val user = api.userManager.getUser(player.uuid) ?: return null
        return when (user.cachedData.permissionData.checkPermission(permission)) {
            Tristate.TRUE -> true
            Tristate.FALSE -> false
            Tristate.UNDEFINED -> null
        }
    }
}
