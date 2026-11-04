package dev.railrestock.mixin;

import dev.railrestock.runtime.MaterialGenerationTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Observes completed counts without taking Litematica's single completion listener. */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.materials.MaterialListBase", remap = false)
public abstract class LitematicaMaterialListMixin {
    @Inject(method = "setMaterialListEntries", at = @At("TAIL"), remap = false)
    private void railrestock$countCompleted(List<?> entries, CallbackInfo ci) {
        MaterialGenerationTracker.completed(this);
    }
}
