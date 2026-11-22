package dev.feederrestock.client;

import dev.feederrestock.FeederRestockClient;
import dev.feederrestock.runtime.HighlightTarget;
import dev.feederrestock.runtime.RestockController;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.shape.VoxelShapes;

import java.util.List;

/**
 * Draws a coloured outline around every registered container the controller
 * marked for this frame: green = the cache says it still has needed material,
 * yellow = never surveyed (contents unknown), grey = surveyed and holding nothing
 * the current deficit needs.
 *
 * <p>The box is drawn with the vanilla line layer at the debug-render stage, so it
 * is depth-tested like hitboxes and needs no shader or texture of its own.</p>
 */
public final class ContainerHighlightRenderer {
    private ContainerHighlightRenderer() { }

    public static void register() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(ContainerHighlightRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        RestockController controller = FeederRestockClient.controller();
        if (controller == null) return;
        List<HighlightTarget> targets = controller.highlights();
        if (targets.isEmpty()) return;
        VertexConsumerProvider consumers = context.consumers();
        CameraRenderState camera = context.worldState().cameraRenderState;
        if (consumers == null || camera == null || camera.pos == null) return;

        // The world renderer works in camera space, so shift the whole scene once.
        MatrixStack matrices = new MatrixStack();
        matrices.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        VertexConsumer lines = consumers.getBuffer(RenderLayers.lines());
        for (HighlightTarget target : targets) {
            int color = ColorHelper.fromFloats(target.kind().red(), target.kind().green(),
                    target.kind().blue(), 1.0f);
            VertexRendering.drawOutline(matrices, lines, VoxelShapes.cuboid(target.box()),
                    0.0, 0.0, 0.0, color, 2.0f);
        }
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            // Draw now instead of waiting for the world renderer to flush this layer.
            immediate.draw(RenderLayers.lines());
        }
    }
}
