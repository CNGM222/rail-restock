package dev.feederrestock.mixin;

import dev.feederrestock.FeederRestockClient;
import dev.feederrestock.runtime.RestockController;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only relevant when the container screen is visible (unobtrusive mode disabled). */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void feederrestock$takeOverOnRawPress(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        RestockController controller = FeederRestockClient.controller();
        if (controller != null && controller.ownsCurrentScreen()) controller.manualTakeover();
    }

    @Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
            at = @At("HEAD"))
    private void feederrestock$giveUserControl(Slot slot, int slotId, int button, SlotActionType type, CallbackInfo ci) {
        RestockController controller = FeederRestockClient.controller();
        if (controller != null && controller.ownsCurrentScreen()) controller.manualTakeover();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void feederrestock$toggleOrTakeOver(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
        if (FeederRestockClient.handleToggleInScreen(input)) {
            cir.setReturnValue(true);
            return;
        }
        RestockController controller = FeederRestockClient.controller();
        if (controller != null && controller.ownsCurrentScreen()) controller.manualTakeover();
    }
}
