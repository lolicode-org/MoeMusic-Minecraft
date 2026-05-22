package org.lolicode.moemusic.forge.permission

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.fml.ModList
import org.lolicode.moemusic.platform.permission.AdvancedPermissionChecker
import org.lolicode.moemusic.platform.permission.PermissionResolver

object ForgePermissionBridge {
    private const val LUCKPERMS_MOD_ID: String = "luckperms"

    private val permissionChecker: ExternalStringPermissionChecker? by lazy(::loadPermissionChecker)

    fun install() {
        PermissionResolver.installAdvancedChecker(ForgePermissionChecker)
    }

    private object ForgePermissionChecker : AdvancedPermissionChecker {
        override fun hasPermission(
            source: CommandSourceStack,
            permission: String,
            fallbackLevel: Int,
        ): Boolean {
            val player = source.entity as? ServerPlayer
            return if (player == null) {
                source.hasPermission(fallbackLevel)
            } else {
                hasPermission(player, permission, fallbackLevel)
            }
        }

        override fun hasPermission(
            player: ServerPlayer,
            permission: String,
            fallbackLevel: Int,
        ): Boolean {
            return permissionChecker?.hasPermission(player, permission)
                ?: player.hasPermissions(fallbackLevel)
        }
    }

    private fun loadPermissionChecker(): ExternalStringPermissionChecker? {
        if (!ModList.get().isLoaded(LUCKPERMS_MOD_ID)) return null
        return ForgeLuckPermsPermissionChecker
    }
}

internal fun interface ExternalStringPermissionChecker {
    fun hasPermission(player: ServerPlayer, permission: String): Boolean?
}
