package dev.feederrestock;

import dev.feederrestock.client.ContainerHighlightRenderer;
import dev.feederrestock.client.RestockConfigScreen;
import dev.feederrestock.config.RestockConfig;
import dev.feederrestock.runtime.RestockController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FeederRestockClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("feederrestock");
    private static RestockController controller;
    private static KeyBinding toggleKey;
    private static KeyBinding settingsKey;
    private static RestockConfig config;
    private static boolean settingsHinted;

    @Override
    public void onInitializeClient() {
        config = RestockConfig.load(LOGGER);
        controller = new RestockController(MinecraftClient.getInstance(), config);
        ContainerHighlightRenderer.register();
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("feederrestock", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.feederrestock.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.feederrestock.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7, category));
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                if (client.currentScreen == null && client.player != null) {
                    controller.toggle();
                    if (controller.isEnabled() && !settingsHinted) {
                        settingsHinted = true;
                        client.player.sendMessage(Text.translatable("chat.feederrestock.settingsHint"), false);
                    }
                }
            }
            while (settingsKey.wasPressed()) {
                if (client.currentScreen == null && client.player != null) {
                    client.setScreen(new RestockConfigScreen(null, config));
                }
            }
            controller.tick();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> controller.disconnect());
        String version = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("feederrestock").map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
        LOGGER.info("Feeder Restock {} ready (unobtrusive={}, autoRecount={})",
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
