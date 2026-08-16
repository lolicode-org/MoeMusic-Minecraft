package org.lolicode.moemusic.platform.permission

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import org.lolicode.moemusic.core.permission.PermissionNodes

/**
 * Optional platform hook for advanced permission providers such as fabric-permissions-api.
 *
 * Implementations live in loader-specific modules and are installed into [PermissionResolver]
 * during platform bootstrap.
 */
interface AdvancedPermissionChecker {
    fun hasPermission(source: CommandSourceStack, permission: String, fallbackLevel: Int): Boolean

    fun hasPermission(player: ServerPlayer, permission: String, fallbackLevel: Int): Boolean
}

/**
 * Shared permission resolution for both Brigadier commands and packet-driven actions.
 *
 * Resolution order:
 * 1. Singleplayer world owner (for levels <= 4) and console command sources are allowed.
 * 2. If the current platform installed an [AdvancedPermissionChecker], delegate to it.
 * 3. Otherwise fall back to vanilla command permission levels (levels 0-4; level 5 is denied).
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
        val clampedLevel = clampLevel(defaultLevel)
        if (clampedLevel <= PermissionNodes.MAX_VANILLA_LEVEL && player.server.isSingleplayerOwner(player.gameProfile)) {
            return true
        }

        val checker = advancedChecker
        if (checker != null) {
            return checker.hasPermission(player, permission, clampedLevel)
        }

        if (clampedLevel > PermissionNodes.MAX_VANILLA_LEVEL) {
            return false
        }
        return player.hasPermissions(clampedLevel)
    }

    internal fun hasPermission(source: CommandSourceStack, permission: String, defaultLevel: Int): Boolean {
        val player = runCatching { source.playerOrException }.getOrNull()
        if (player != null) return hasPermission(player, permission, defaultLevel)
        if (isConsole(source)) return true

        val clampedLevel = clampLevel(defaultLevel)
        val checker = advancedChecker
        if (checker != null) {
            return checker.hasPermission(source, permission, clampedLevel)
        }

        if (clampedLevel > PermissionNodes.MAX_VANILLA_LEVEL) {
            return false
        }
        return source.hasPermission(clampedLevel)
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
