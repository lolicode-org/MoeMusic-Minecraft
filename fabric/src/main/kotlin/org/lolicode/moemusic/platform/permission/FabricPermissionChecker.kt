package org.lolicode.moemusic.platform.permission

import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.PermissionLevel

private const val FABRIC_PERMISSIONS_API_MOD_ID: String = "fabric-permissions-api-v0"

internal fun installFabricPermissionChecker() {
    val checker = if (FabricLoader.getInstance().isModLoaded(FABRIC_PERMISSIONS_API_MOD_ID)) {
        FabricPermissionChecker
    } else {
        null
    }
    PermissionResolver.installAdvancedChecker(checker)
}

private object FabricPermissionChecker : AdvancedPermissionChecker {
    override fun hasPermission(
        source: CommandSourceStack,
        permission: String,
        fallbackLevel: PermissionLevel,
    ): Boolean = Permissions.check(source, permission, fallbackLevel)

    override fun hasPermission(
        player: ServerPlayer,
        permission: String,
        fallbackLevel: PermissionLevel,
    ): Boolean = Permissions.check(player, permission, fallbackLevel)
}
