package org.lolicode.moemusic.platform.client.ui

import org.lolicode.moemusic.platform.text.McText

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component

internal object ClientToastIds {
    val openGuiTip: SystemToast.SystemToastIds = SystemToast.SystemToastIds.UNSECURE_SERVER_WARNING
    val configSaveConflict: SystemToast.SystemToastIds = SystemToast.SystemToastIds.UNSECURE_SERVER_WARNING
    val instanceLocked: SystemToast.SystemToastIds = SystemToast.SystemToastIds.PERIODIC_NOTIFICATION
}

internal fun showWrappedSystemToast(
    minecraft: Minecraft,
    id: SystemToast.SystemToastIds,
    title: Component,
    message: Component,
) {
    minecraft.toasts.addToast(SystemToast.multiline(minecraft, id, title, message))
}

internal fun showPersistentRuntimeWarning(
    minecraft: Minecraft,
    title: Component,
    message: Component,
) {
    minecraft.player?.sendSystemMessage(
        McText.literal("§e[MoeMusic] ")
            .append(title)
            .append(McText.literal(": "))
            .append(message)
    )
}
