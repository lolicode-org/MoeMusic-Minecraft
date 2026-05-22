package org.lolicode.moemusic.platform.client.ui.config

import org.lolicode.moemusic.platform.text.McText

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lolicode.moemusic.platform.client.ui.GuiGraphics
import org.lolicode.moemusic.platform.client.ui.button

/**
 * Simple fallback screen shown when the optional Cloth Config dependency is not installed.
 */
class MissingClothConfigScreen(
    private val parent: Screen?,
) : Screen(McText.translatable("screen.moemusic.config.unavailable.title")) {

    override fun init() {
        super.init()

        addRenderableWidget(
            button(
                width / 2 - 50,
                height - 40,
                100,
                20,
                McText.translatable(if (parent != null) "gui.back" else "gui.done"),
            ) {
                onClose()
            }
        )
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, delta: Float) {
        val context = GuiGraphics(poseStack, width, height)
        renderBackground(poseStack)
        super.render(poseStack, mouseX, mouseY, delta)
        context.drawCenteredString(font, title, width / 2, 40, 0xFFFFFFFF.toInt())
        context.drawWordWrap(
            font,
            McText.translatable("screen.moemusic.config.unavailable.body"),
            width / 2 - 140,
            74,
            280,
            0xFFC0C0C0.toInt(),
        )
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }
}
