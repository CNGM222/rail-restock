package dev.feederrestock.client;

import dev.feederrestock.FeederRestockClient;
import dev.feederrestock.logic.HighlightKind;
import dev.feederrestock.runtime.HighlightTarget;
import dev.feederrestock.runtime.RestockController;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Draws a coloured outline around every registered container the controller
 * marked for this frame: green = the cache says it still has needed material,
 * yellow = never surveyed (contents unknown), grey = surveyed and holding nothing
 * the current deficit needs.
 *
 * <p>The box itself is not drawn by this mod. It uses MaLiLib's
 * {@link RenderUtils#renderAreaOutline} — the very routine Litematica uses for its
 * placement boxes — registered on MaLiLib's world-last render pass, so the box
 * uses MaLiLib's debug-line pipeline, camera-relative coordinates and render
 * context instead of a hand-written vertex flush.</p>
 */
public final class ContainerHighlightRenderer implements IRenderer {
    /** Line width in pixels, matching how Litematica draws its placement boxes. */
    private static final float LINE_WIDTH = 2.0f;

    private static final ContainerHighlightRenderer INSTANCE = new ContainerHighlightRenderer();

    private ContainerHighlightRenderer() { }

    public static void register() {
        RenderEventHandler.getInstance().registerWorldLastRenderer(INSTANCE);
    }

    /**
     * Every container of the target list becomes one box; a double chest arrives as
     * a min/max pair covering both halves, so it stays a single outline.
     */
    @Override
    public void onRenderWorldLast(Matrix4f positionMatrix, Matrix4f projectionMatrix) {
        RestockController controller = FeederRestockClient.controller();
        if (controller == null) return;
        List<HighlightTarget> targets = controller.highlights();
        if (targets.isEmpty()) return;
        for (HighlightTarget target : targets) {
            HighlightKind kind = target.kind();
            Color4f color = new Color4f(kind.red(), kind.green(), kind.blue(), 1.0f);
            RenderUtils.renderAreaOutline(target.min(), target.max(), LINE_WIDTH, color, color, color);
        }
    }
}
