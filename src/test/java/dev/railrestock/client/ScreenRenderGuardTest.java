package dev.railrestock.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.21.11 renders a screen's background before {@code render}: {@code Screen.renderWithTooltip}
 * calls {@code renderBackground} and then {@code render}, and the blur state allows
 * one blur per frame. A custom {@code render} that calls {@code renderBackground}
 * itself therefore crashes with "Can only blur once per frame", which is exactly
 * what happened in 0.5.0. This guards the screen against that regression.
 */
class ScreenRenderGuardTest {
    @Test
    void renderDoesNotDrawTheBackgroundItself() throws IOException {
        MethodNode render = renderMethod();
        for (int index = 0; index < render.instructions.size(); index++) {
            AbstractInsnNode instruction = render.instructions.get(index);
            if (!(instruction instanceof MethodInsnNode call)) continue;
            assertNotEquals("renderBackground", call.name,
                    "render() must not call renderBackground on 1.21.11: the frame is already blurred");
        }
    }

    @Test
    void renderStillDrawsTheTitleAndTheWidgets() throws IOException {
        MethodNode render = renderMethod();
        boolean drawsTitle = false;
        boolean callsSuper = false;
        InsnList instructions = render.instructions;
        for (int index = 0; index < instructions.size(); index++) {
            if (!(instructions.get(index) instanceof MethodInsnNode call)) continue;
            if (call.name.startsWith("drawCenteredText")) drawsTitle = true;
            if (call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("render")
                    && call.owner.equals("net/minecraft/client/gui/screen/Screen")) {
                callsSuper = true;
            }
        }
        assertTrue(drawsTitle, "设置界面必须自己画标题");
        assertTrue(callsSuper, "必须调用 super.render 才会绘制按钮与滑条");
    }

    private MethodNode renderMethod() throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("dev/railrestock/client/RestockConfigScreen.class")) {
            assertNotNull(stream, "RestockConfigScreen.class");
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals("render") && method.desc.equals(
                    "(Lnet/minecraft/client/gui/DrawContext;IIF)V")) {
                return method;
            }
        }
        return fail("RestockConfigScreen 缺少 render(DrawContext,int,int,float)");
    }
}
