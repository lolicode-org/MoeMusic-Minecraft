package org.lolicode.moemusic.client.mixin;

import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes Bad Packets' narrow vanilla channel-register helper to MoeMusic. */
@Mixin(targets = "lol.bai.badpackets.impl.handler.AbstractPacketHandler")
public interface MixinBadPacketsAbstractPacketHandler {
    @Invoker("sendVanillaChannelRegisterPacket")
    void moemusicSendVanillaChannelRegisterPacket(Set<?> channels);
}
