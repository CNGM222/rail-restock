package dev.feederrestock.mixin;

import dev.feederrestock.runtime.InteractionGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pauses the Litematica printer while a container visit owns the inventory.
 *
 * <p>Cancelling its tick is what actually stops printing: Litematica's own Easy
 * Place and the player's use key are gated elsewhere, but the printer runs on its
 * own timer and would otherwise keep placing blocks — and consuming hotbar items —
 * right through a transfer.</p>
 *
 * <p>{@code require = 0} keeps a future printer build without this method from
 * failing at startup; {@code PrinterBridge} then falls back to its config switch.</p>
 */
@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.Printer", remap = false)
public abstract class PrinterPauseMixin {
    @Inject(method = "onGameTick", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void feederrestock$pausePrinterTick(CallbackInfoReturnable<Boolean> cir) {
        if (InteractionGate.isBusy()) cir.setReturnValue(false);
    }
}
