package dev.railrestock.runtime;

import dev.railrestock.logic.PrintSafety;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.ScreenHandler;

/**
 * Live client state for {@link PrintSafety}. Read on the client thread from the
 * interaction gate, so a container that was left open or a stack stuck on the
 * cursor stops printing even if no restock session owns it any more.
 */
public final class InventoryGuard {
    private InventoryGuard() { }

    public static boolean blocksPrinting() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return false;
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == null) return false;
        return PrintSafety.blocks(handler != client.player.playerScreenHandler,
                !handler.getCursorStack().isEmpty());
    }
}
