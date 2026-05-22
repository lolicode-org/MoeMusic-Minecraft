package org.lolicode.moemusic.platform.permission

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permission
import net.minecraft.server.permissions.PermissionLevel
import org.lolicode.moemusic.core.permission.PermissionNodes

/**
 * Optional platform hook for advanced permission providers such as fabric-permissions-api.
 *
 * Implementations live in loader-specific modules and are installed into [PermissionResolver]
 * during platform bootstrap.
 */
interface AdvancedPermissionChecker {
    fun hasPermission(source: CommandSourceStack, permission: String, fallbackLevel: PermissionLevel): Boolean

    fun hasPermission(player: ServerPlayer, permission: String, fallbackLevel: PermissionLevel): Boolean
}

/**
 * Shared permission resolution for both Brigadier commands and packet-driven actions.
 *
 * Resolution order:
 * 1. Singleplayer world owner and console command sources are always allowed.
 * 2. If the current platform installed an [AdvancedPermissionChecker], delegate to it.
 * 3. Otherwise fall back to vanilla command permission levels.
 */
object PermissionResolver {

    @Volatile
    private var advancedChecker: AdvancedPermissionChecker? = null

    fun installAdvancedChecker(checker: AdvancedPermissionChecker?) {
        advancedChecker = checker
    }

    internal fun hasPermission(player: ServerPlayer, permission: PermissionNodes.Node): Boolean =
        hasPermission(player, permission.id, permission.defaultLevel())

    internal fun hasPermission(source: CommandSourceStack, permission: PermissionNodes.Node): Boolean =
        hasPermission(source, permission.id, permission.defaultLevel())

    internal fun hasAnyPermission(source: CommandSourceStack, vararg permissions: PermissionNodes.Node): Boolean =
        permissions.any { hasPermission(source, it) }

    fun hasPermission(player: ServerPlayer, permission: String, defaultLevel: Int): Boolean {
        if (player.level().server.isSingleplayerOwner(player.nameAndId())) return true

        val permissionLevel = PermissionLevel.byId(clampLevel(defaultLevel))
        return advancedChecker?.hasPermission(player, permission, permissionLevel)
            ?: player.permissions().hasPermission(Permission.HasCommandLevel(permissionLevel))
    }

    internal fun hasPermission(source: CommandSourceStack, permission: String, defaultLevel: Int): Boolean {
        val player = source.player
        if (player != null && source.server.isSingleplayerOwner(player.nameAndId())) return true
        if (player == null && isConsole(source)) return true

        val permissionLevel = PermissionLevel.byId(clampLevel(defaultLevel))
        return advancedChecker?.hasPermission(source, permission, permissionLevel)
            ?: source.permissions().hasPermission(Permission.HasCommandLevel(permissionLevel))
    }

    private fun clampLevel(level: Int): Int =
        level.coerceIn(PermissionNodes.MIN_DEFAULT_LEVEL, PermissionNodes.MAX_DEFAULT_LEVEL)

    /**
     * Console command sources use the default "Server" name and have no backing entity.
     *
     * This intentionally excludes command blocks and other automated sources.
     */
    private fun isConsole(source: CommandSourceStack): Boolean =
        source.entity == null && source.textName == "Server"
}
