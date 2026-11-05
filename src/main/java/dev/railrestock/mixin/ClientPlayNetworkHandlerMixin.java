package dev.railrestock.mixin;

import dev.railrestock.runtime.InventorySyncTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records full server inventory snapshots, our only authoritative acknowledgment. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onInventory", at = @At("TAIL"))
    private void railrestock$afterAuthoritativeContents(InventoryS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && (packet.syncId() == 0
                || packet.syncId() == client.player.currentScreenHandler.syncId)) {
            InventorySyncTracker.fullUpdate(packet.syncId(), packet.contents());
        }
    }
}
