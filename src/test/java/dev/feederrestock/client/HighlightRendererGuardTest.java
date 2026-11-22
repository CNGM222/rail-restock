package dev.feederrestock.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 0.7.0 highlight was a hand-written vanilla line flush inside a Fabric world
 * render event; in game only the HUD text appeared and no box was drawn. 0.7.1
 * delegates to MaLiLib's {@code RenderUtils.renderAreaOutline} on MaLiLib's
 * world-last pass instead, which is the same routine Litematica uses for its
 * placement boxes. These checks keep that wiring from silently regressing.
 */
class HighlightRendererGuardTest {
    private static final String RENDERER = "dev/feederrestock/client/ContainerHighlightRenderer.class";
    private static final String MALILIB_IRENDERER = "fi/dy/masa/malilib/interfaces/IRenderer";
    private static final String MALILIB_RENDER_UTILS = "fi/dy/masa/malilib/render/RenderUtils";
    private static final String MALILIB_EVENT_HANDLER = "fi/dy/masa/malilib/event/RenderEventHandler";

    @Test
    void rendererHooksIntoMalilibsWorldLastPass() throws IOException {
        ClassNode node = load(RENDERER);
        assertTrue(node.interfaces.contains(MALILIB_IRENDERER),
                "ContainerHighlightRenderer 必须实现 MaLiLib 的 IRenderer");
        MethodNode register = method(node, "register", "()V");
        boolean registersWorldLast = false;
        for (MethodInsnNode call : calls(register)) {
            // getInstance() is typed as IRenderDispatcher, which declares the hook.
            boolean malilibOwner = call.owner.equals(MALILIB_EVENT_HANDLER)
                    || call.owner.equals("fi/dy/masa/malilib/interfaces/IRenderDispatcher");
            if (malilibOwner && call.name.equals("registerWorldLastRenderer")) {
                registersWorldLast = true;
            }
        }
        assertTrue(registersWorldLast, "必须注册到 MaLiLib 的 world-last 渲染回调");
    }

    @Test
    void boxIsDrawnWithMalilibsAreaOutline() throws IOException {
        ClassNode node = load(RENDERER);
        MethodNode render = method(node, "onRenderWorldLast", "(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V");
        assertNotNull(render, "必须实现 onRenderWorldLast(Matrix4f, Matrix4f)");
        boolean drawsAreaOutline = false;
        for (MethodInsnNode call : calls(render)) {
            if (call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(MALILIB_RENDER_UTILS)
                    && call.name.equals("renderAreaOutline")) {
                drawsAreaOutline = true;
            }
        }
        assertTrue(drawsAreaOutline, "方块框必须交给 MaLiLib 的 RenderUtils.renderAreaOutline 绘制");
    }

    @Test
    void noHandWrittenVanillaLineLayerRemains() throws IOException {
        ClassNode node = load(RENDERER);
        for (MethodNode method : node.methods) {
            for (MethodInsnNode call : calls(method)) {
                assertFalse(call.owner.startsWith("net/minecraft/client/render/"),
                        "不要再自己拿原版渲染层拼线：" + call.owner + "." + call.name);
            }
        }
    }

    private static ClassNode load(String resource) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream stream = HighlightRendererGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        }
        return null;
    }

    private static Iterable<MethodInsnNode> calls(MethodNode method) {
        assertNotNull(method);
        java.util.List<MethodInsnNode> result = new java.util.ArrayList<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof MethodInsnNode call) result.add(call);
        }
        return result;
    }
}
