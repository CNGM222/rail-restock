package dev.feederrestock.client;

import dev.feederrestock.FeederRestockClient;
import dev.feederrestock.logic.HighlightKind;
import dev.feederrestock.runtime.HighlightTarget;
import dev.feederrestock.runtime.RestockController;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Compact companion to the world boxes: the nearest registered containers with
 * the same colour and their cached label, so the outline colours are readable
 * without hovering anything.
 */
public final class HighlightHud implements HudElement {
    private static final Identifier ID = Identifier.of("feederrestock", "container_highlights");
    private static final int MAX_LINES = 5;
    private static final int LINE_HEIGHT = 10;
    private static final int MARGIN = 6;

    public static void register() {
        HudElementRegistry.addLast(ID, new HighlightHud());
    }

    @Override
    public void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;
        RestockController controller = FeederRestockClient.controller();
        if (controller == null) return;
        List<HighlightTarget> targets = controller.highlights();
        if (targets.isEmpty()) return;

        TextRenderer fonts = client.textRenderer;
        int shown = 0;
        for (HighlightTarget target : targets) {
            if (shown >= MAX_LINES) break;
            if (target.kind() == HighlightKind.EMPTY) continue;
            Text line = Text.literal(target.distance() + "m  " + target.label());
            context.drawTextWithShadow(fonts, line, MARGIN, MARGIN + shown * LINE_HEIGHT, target.kind().argb());
            shown++;
        }
        if (shown == 0) {
            context.drawTextWithShadow(fonts, Text.literal("附近没有有料的已登记容器"),
                    MARGIN, MARGIN, HighlightKind.EMPTY.argb());
        }
    }
}
