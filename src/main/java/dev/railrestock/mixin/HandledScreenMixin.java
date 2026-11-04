package dev.railrestock.mixin;

import dev.railrestock.RailRestockClient;
import dev.railrestock.runtime.RestockController;
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
    private void railrestock$takeOverOnRawPress(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        RestockController controller = RailRestockClient.controller();
        if (controller != null && controller.ownsCurrentScreen()) controller.manualTakeover();
    }

    @Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
            at = @At("HEAD"))
    private void railrestock$giveUserControl(Slot slot, int slotId, int button, SlotActionType type, CallbackInfo ci) {
        RestockController controller = RailRestockClient.controller();
        if (controller != null && controller.ownsCurrentScreen()) controller.manualTakeover();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void railrestock$toggleOrTakeOver(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
        if (RailRestockClient.handleToggleInScreen(input)) {
            cir.setReturnValue(true);
            return;
        }
        RestockController controller = RailRestockClient.controller();
        if (controller != null && controller.ownsCurrentScreen()) controller.manualTakeover();
    }
}
