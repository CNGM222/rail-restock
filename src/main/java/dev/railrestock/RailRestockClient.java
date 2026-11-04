package dev.railrestock;

import dev.railrestock.config.RestockConfig;
import dev.railrestock.runtime.RestockController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RailRestockClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("railrestock");
    private static RestockController controller;
    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        RestockConfig config = RestockConfig.load(LOGGER);
        controller = new RestockController(MinecraftClient.getInstance(), config);
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("railrestock", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.railrestock.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                if (client.currentScreen == null && client.player != null) controller.toggle();
            }
            controller.tick();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> controller.disconnect());
        String version = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("railrestock").map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
        LOGGER.info("Rail Restock {} ready (unobtrusive={}, autoRecount={})",
                version, config.unobtrusiveInteraction, config.autoRecountMaterialList);
    }

    public static RestockController controller() {
        return controller;
    }

    /** Container screens normally swallow gameplay keys, so the toggle is handled here too. */
    public static boolean handleToggleInScreen(KeyInput input) {
        if (toggleKey == null || controller == null || !toggleKey.matchesKey(input)) return false;
        while (toggleKey.wasPressed()) {
            // Drain the duplicate that KeyBinding will report for the same GUI event.
        }
        controller.toggle();
        return true;
    }
}
