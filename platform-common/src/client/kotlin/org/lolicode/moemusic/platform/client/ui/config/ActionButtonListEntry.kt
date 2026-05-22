package org.lolicode.moemusic.platform.client.ui.config

import com.mojang.blaze3d.vertex.PoseStack
import me.shedaniel.clothconfig2.gui.entries.TooltipListEntry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lolicode.moemusic.platform.client.ui.GuiGraphics
import org.lolicode.moemusic.platform.client.ui.button
import java.util.*
import java.util.function.Consumer

@Suppress("UnstableApiUsage", "DEPRECATION")
internal class ActionButtonListEntry(fieldName: Component?, buttonText: Component, action: Consumer<Screen?>) :
    TooltipListEntry<Void?>(fieldName, { Optional.empty<Array<Component>>() }) {
    private val buttonWidget: Button = button(0, 0, 100, 20, buttonText) { action.accept(configScreen) }
    private val widgets: MutableList<Button?> = mutableListOf(buttonWidget)
    private var focused: GuiEventListener? = null
    private var dragging = false

    override fun getValue(): Void? {
        return null
    }

    override fun getDefaultValue(): Optional<Void?> {
        @Suppress("UNCHECKED_CAST")
        return Optional.empty<Void>() as Optional<Void?>
    }

    override fun render(
        poseStack: PoseStack,
        index: Int,
        y: Int,
        x: Int,
        entryWidth: Int,
        entryHeight: Int,
        mouseX: Int,
        mouseY: Int,
        isHovered: Boolean,
        delta: Float
    ) {
        super.render(poseStack, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta)
        val graphics = GuiGraphics(poseStack, Minecraft.getInstance().window.guiScaledWidth, Minecraft.getInstance().window.guiScaledHeight)
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        val displayedFieldName = getDisplayedFieldName()
        val buttonWidth = 150

        buttonWidget.active = isEditable
        buttonWidget.y = y
        buttonWidget.setWidth(buttonWidth)

        if (minecraft.font.isBidirectional) {
            graphics.drawString(
                minecraft.font,
                displayedFieldName.visualOrderText,
                window.guiScaledWidth - x - minecraft.font.width(displayedFieldName),
                y + 6,
                -0x1
            )
            buttonWidget.x = x
        } else {
            graphics.drawString(
                minecraft.font,
                displayedFieldName.visualOrderText,
                x,
                y + 6,
                preferredTextColor
            )
            buttonWidget.x = x + entryWidth - buttonWidth
        }

        buttonWidget.render(poseStack, mouseX, mouseY, delta)
    }

    override fun children(): MutableList<out GuiEventListener?> {
        return widgets
    }

    override fun narratables(): MutableList<out NarratableEntry?> {
        return widgets
    }

    override fun getFocused(): GuiEventListener? {
        return focused
    }

    override fun setFocused(focused: GuiEventListener?) {
        this.focused = focused
        if ((focused === buttonWidget) != buttonWidget.isFocused) {
            buttonWidget.changeFocus(true)
        }
    }

    override fun isDragging(): Boolean {
        return dragging
    }

    override fun setDragging(dragging: Boolean) {
        this.dragging = dragging
    }
}
