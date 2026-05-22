package org.lolicode.moemusic.platform.client.ui.config

import org.lolicode.moemusic.platform.text.McText

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Simple fallback screen shown when the optional Cloth Config dependency is not installed.
 */
class MissingClothConfigScreen(
    private val parent: Screen?,
) : Screen(McText.translatable("screen.moemusic.config.unavailable.title")) {

    override fun init() {
        super.init()

        addRenderableWidget(
            Button.builder(McText.translatable(if (parent != null) "gui.back" else "gui.done")) {
                onClose()
            }.pos(width / 2 - 50, height - 40).size(100, 20).build()
        )
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
        context.centeredText(font, title, width / 2, 40, 0xFFFFFFFF.toInt())
        context.textWithWordWrap(
            font,
            McText.translatable("screen.moemusic.config.unavailable.body"),
            width / 2 - 140,
            74,
            280,
            0xFFC0C0C0.toInt(),
        )
    }

    override fun onClose() {
        minecraft.setScreen(parent)
    }
}
