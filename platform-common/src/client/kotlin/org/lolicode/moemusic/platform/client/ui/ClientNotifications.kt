package org.lolicode.moemusic.platform.client.ui

import org.lolicode.moemusic.platform.text.McText

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component

internal object ClientToastIds {
    val openGuiTip: SystemToast.SystemToastId = SystemToast.SystemToastId(10_000L)
    val configSaveConflict: SystemToast.SystemToastId = SystemToast.SystemToastId(10_000L)
    val instanceLocked: SystemToast.SystemToastId = SystemToast.SystemToastId()
}

internal fun showWrappedSystemToast(
    minecraft: Minecraft,
    id: SystemToast.SystemToastId,
    title: Component,
    message: Component,
) {
    val toastManager = minecraft.toasts
    toastManager.getToast(SystemToast::class.java, id)?.forceHide()
    toastManager.addToast(SystemToast.multiline(minecraft, id, title, message))
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
