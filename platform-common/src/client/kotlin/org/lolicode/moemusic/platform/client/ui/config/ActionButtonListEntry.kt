package org.lolicode.moemusic.platform.client.ui.config

import me.shedaniel.clothconfig2.gui.entries.TooltipListEntry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.Optional
import java.util.function.Consumer

@Suppress("UnstableApiUsage", "DEPRECATION")
internal class ActionButtonListEntry(fieldName: Component?, buttonText: Component, action: Consumer<Screen>) :
    TooltipListEntry<Void?>(fieldName, null) {
    private val buttonWidget: Button = Button.builder(buttonText) {
        configScreen?.let(action::accept)
    }.bounds(0, 0, 100, 20).build()
    private val widgets: MutableList<Button> = mutableListOf(buttonWidget)
    private var focused: GuiEventListener? = null
    private var dragging = false

    override fun getValue(): Void? = null

    @Suppress("UNCHECKED_CAST")
    override fun getDefaultValue(): Optional<Void?> = Optional.empty<Void?>() as Optional<Void?>

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        index: Int,
        y: Int,
        x: Int,
        entryWidth: Int,
        entryHeight: Int,
        mouseX: Int,
        mouseY: Int,
        isHovered: Boolean,
        delta: Float,
    ) {
        super.extractRenderState(graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta)
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        val displayedFieldName = displayedFieldName
        val buttonWidth = 150

        buttonWidget.active = isEditable
        buttonWidget.setY(y)
        buttonWidget.setWidth(buttonWidth)

        if (minecraft.font.isBidirectional) {
            graphics.text(
                minecraft.font,
                displayedFieldName,
                window.guiScaledWidth - x - minecraft.font.width(displayedFieldName),
                y + 6,
                0xFFFFFFFF.toInt(),
            )
            buttonWidget.setX(x)
        } else {
            graphics.text(
                minecraft.font,
                displayedFieldName,
                x,
                y + 6,
                preferredTextColor,
            )
            buttonWidget.setX(x + entryWidth - buttonWidth)
        }

        buttonWidget.extractRenderState(graphics, mouseX, mouseY, delta)
    }

    override fun children(): MutableList<out GuiEventListener> = widgets

    override fun narratables(): MutableList<out NarratableEntry> = widgets

    override fun narrationPriority(): NarratableEntry.NarrationPriority = buttonWidget.narrationPriority()

    override fun updateNarration(output: NarrationElementOutput) {
        buttonWidget.updateNarration(output)
    }

    override fun getFocused(): GuiEventListener? = focused

    override fun setFocused(focused: GuiEventListener?) {
        this.focused = focused
        buttonWidget.isFocused = focused === buttonWidget
    }

    override fun isDragging(): Boolean = dragging

    override fun setDragging(dragging: Boolean) {
        this.dragging = dragging
    }
}
