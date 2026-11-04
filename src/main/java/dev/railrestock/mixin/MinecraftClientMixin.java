package dev.railrestock.mixin;

import dev.railrestock.RailRestockClient;
import dev.railrestock.runtime.InteractionGate;
import dev.railrestock.runtime.RestockController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void railrestock$pauseItemUse(CallbackInfo ci) {
        if (InteractionGate.isBusy()) ci.cancel();
    }

    /** 1.21.11 returns whether the attack was handled, so the callback must say so too. */
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void railrestock$pauseAttack(CallbackInfoReturnable<Boolean> cir) {
        if (InteractionGate.isBusy()) cir.setReturnValue(false);
    }

    /**
     * Suppresses only the local screen of a container this mod opened itself.
     * Cancelling here leaves currentScreen null, so the mouse is never unlocked
     * and no keybinding state is cleared.
     */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void railrestock$hideOwnContainerScreen(Screen screen, CallbackInfo ci) {
        RestockController controller = RailRestockClient.controller();
        if (controller != null && controller.interceptScreenChange(screen)) ci.cancel();
    }
}
