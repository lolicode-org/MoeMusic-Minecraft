package org.lolicode.moemusic.forge.permission

import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.util.Tristate
import net.minecraft.server.level.ServerPlayer

/**
 * Optional LuckPerms-backed permission bridge for all Forge-side permission checks.
 *
 * Forge's built-in permission API is node-registration oriented, while MoeMusic plugins
 * expose arbitrary permission strings. Query LuckPerms directly when present and otherwise
 * fall back to vanilla permission levels.
 */
internal object ForgeLuckPermsPermissionChecker : ExternalStringPermissionChecker {
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
