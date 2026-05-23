package org.lolicode.moemusic.client.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.lolicode.moemusic.platform.client.ui.NowPlayingHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class MixinGui {
    @Inject(method = "render", at = @At("HEAD"))
    private void moemusic$renderHudFirstLayer(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
        NowPlayingHud.INSTANCE.render(guiGraphics, partialTick);
    }
}
