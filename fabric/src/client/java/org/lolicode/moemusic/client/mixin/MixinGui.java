package org.lolicode.moemusic.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.lolicode.moemusic.platform.client.ui.NowPlayingHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class MixinGui {
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V"
            )
    )
    private void moemusic$renderHudBeforeChat(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        poseStack.pushPose();
        poseStack.translate(0.0D, 48.0D - Minecraft.getInstance().getWindow().getGuiScaledHeight(), 0.0D);
        NowPlayingHud.INSTANCE.render(poseStack, partialTick);
        poseStack.popPose();
    }
}
