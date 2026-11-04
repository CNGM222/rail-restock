package dev.railrestock.mixin;

import dev.railrestock.runtime.InteractionGate;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Arbitrates only while a restock session owns the container. Litematica's own
 * configuration and key state are never modified.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.util.WorldUtils", remap = false)
public abstract class LitematicaEasyPlaceMixin {
    @Inject(method = "easyPlaceOnUseTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void railrestock$pauseHeldPlacement(MinecraftClient client, CallbackInfo ci) {
        if (InteractionGate.isBusy() || client.currentScreen != null) ci.cancel();
    }

    @Inject(method = "handleEasyPlace", at = @At("HEAD"), cancellable = true, remap = false)
    private static void railrestock$consumePlacementActivation(MinecraftClient client, CallbackInfoReturnable<Boolean> cir) {
        // Consumed rather than PASS: PASS would let the same key place normally.
        if (InteractionGate.isBusy() || client.currentScreen != null) cir.setReturnValue(true);
    }
}
