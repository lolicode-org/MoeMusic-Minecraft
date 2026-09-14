package org.lolicode.moemusic.platform.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Overlay
import net.minecraft.client.gui.screens.Screen

internal val Minecraft.screen: Screen?
    get() = gui.screen()

internal fun Minecraft.setScreen(screen: Screen?) {
    gui.setScreen(screen)
}

internal val Minecraft.overlay: Overlay?
    get() = gui.overlay()
