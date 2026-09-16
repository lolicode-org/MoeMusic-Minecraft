package org.lolicode.moemusic.platform.permission

import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner
import net.fabricmc.fabric.api.util.TriState
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permission
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.world.entity.Entity
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private const val FABRIC_PERMISSION_API_V1_MOD_ID: String = "fabric-permission-api-v1"
private const val FABRIC_PERMISSIONS_API_V0_MOD_ID: String = "fabric-permissions-api-v0"

internal fun installFabricPermissionChecker() {
    val hasV1 = FabricLoader.getInstance().isModLoaded(FABRIC_PERMISSION_API_V1_MOD_ID)
    val hasV0 = FabricLoader.getInstance().isModLoaded(FABRIC_PERMISSIONS_API_V0_MOD_ID)

    val checker = if (hasV1 || hasV0) {
        FabricPermissionChecker(hasV1 = hasV1, hasV0 = hasV0)
    } else {
        null
    }
    PermissionResolver.installAdvancedChecker(checker)
}

private class FabricPermissionChecker(
    private val hasV1: Boolean,
    private val hasV0: Boolean,
) : AdvancedPermissionChecker {
    override fun hasPermission(
        source: CommandSourceStack,
        permission: String,
        fallbackLevel: PermissionLevel?,
    ): Boolean {
        if (hasV1) {
            val v1Result = FabricPermissionV1Bridge.hasPermission(source, permission)
            if (v1Result != TriState.DEFAULT) {
                return v1Result.get()
            }
        }

        if (hasV0) {
            val v0Result = FabricPermissionV0Bridge.hasPermission(source, permission)
            if (v0Result != TriState.DEFAULT) {
                return v0Result.get()
            }
        }

        return fallbackLevel != null && source.permissions().hasPermission(Permission.HasCommandLevel(fallbackLevel))
    }

    override fun hasPermission(
        player: ServerPlayer,
        permission: String,
        fallbackLevel: PermissionLevel?,
    ): Boolean {
        if (hasV1) {
            val v1Result = FabricPermissionV1Bridge.hasPermission(player, permission)
            if (v1Result != TriState.DEFAULT) {
                return v1Result.get()
            }
        }

        if (hasV0) {
            val v0Result = FabricPermissionV0Bridge.hasPermission(player, permission)
            if (v0Result != TriState.DEFAULT) {
                return v0Result.get()
            }
        }

        return fallbackLevel != null && player.permissions().hasPermission(Permission.HasCommandLevel(fallbackLevel))
    }
}

private object FabricPermissionV1Bridge {
    private val identifierCache = ConcurrentHashMap<String, Identifier>()
    private val invalidCache = ConcurrentHashMap.newKeySet<String>()
    private val logger = LoggerFactory.getLogger("FabricPermissionChecker")

    private fun toPermissionIdentifier(permission: String): Identifier? {
        if (invalidCache.contains(permission)) return null
        return identifierCache.getOrPut(permission) {
            val lower = permission.lowercase()
            val namespace = lower.substringBefore('.', "moemusic")
            val path = lower.substringAfter('.', lower)
            val id = Identifier.tryParse("$namespace:$path")
            if (id == null) {
                invalidCache.add(permission)
                logger.warn("Permission node '{}' contains invalid characters and cannot be evaluated by fabric-permission-api-v1.", permission)
                return null
            }
            id
        }
    }

    fun hasPermission(source: CommandSourceStack, permission: String): TriState {
        val owner = source as? PermissionContextOwner ?: return TriState.DEFAULT
        val id = toPermissionIdentifier(permission) ?: return TriState.DEFAULT
        return owner.checkPermission(id)
    }

    fun hasPermission(player: ServerPlayer, permission: String): TriState {
        val owner = player as? PermissionContextOwner ?: return TriState.DEFAULT
        val id = toPermissionIdentifier(permission) ?: return TriState.DEFAULT
        return owner.checkPermission(id)
    }
}

private object FabricPermissionV0Bridge {
    fun hasPermission(source: CommandSourceStack, permission: String): TriState {
        return Permissions.getPermissionValue(source, permission)
    }

    fun hasPermission(player: ServerPlayer, permission: String): TriState {
        return Permissions.getPermissionValue(player as Entity, permission)
    }
}
