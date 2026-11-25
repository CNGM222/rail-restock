package dev.feederrestock.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone container registration must stay a fallback: when mapart-feeder is
 * installed its K key and its list are the single source of truth. These checks
 * keep the gate and the crosshair-based toggle from being deleted by accident.
 */
class MarkingGuardTest {
    private static final String CLIENT = "dev/feederrestock/FeederRestockClient.class";
    private static final String CONTROLLER = "dev/feederrestock/runtime/RestockController.class";
    private static final String FEEDER_MARKS = "dev/feederrestock/integration/FeederMarks";
    private static final String LOCAL_MARKS = "dev/feederrestock/integration/LocalMarks";
    private static final String CONTAINER_SCANNER = "dev/feederrestock/runtime/ContainerScanner";

    @Test
    void markingKeyIsOnlyBoundWhenMapartFeederIsAbsent() throws IOException {
        MethodNode init = method(load(CLIENT), "onInitializeClient", "()V");
        boolean gatesOnInstalled = false;
        boolean bindsMarkKey = false;
        for (MethodInsnNode call : calls(init)) {
            if (call.owner.equals(FEEDER_MARKS) && call.name.equals("isInstalled")) gatesOnInstalled = true;
            if (call.owner.endsWith("KeyBindingHelper") && call.name.equals("registerKeyBinding")) {
                bindsMarkKey = true;
            }
        }
        assertTrue(gatesOnInstalled, "必须用 FeederMarks.isInstalled() 判断是否接管 K 键");
        assertTrue(bindsMarkKey, "没有 mapart-feeder 时要注册自己的登记按键");
        assertTrue(containsString(init, "key.feederrestock.mark_container"),
                "登记按键必须带自己的翻译键");
    }

    @Test
    void markContainerUsesTheCrosshairAndTheLocalRegistry() throws IOException {
        MethodNode mark = method(load(CONTROLLER), "markContainer", "()V");
        boolean readsCrosshair = false;
        boolean checksContainerBlock = false;
        boolean togglesLocal = false;
        boolean saves = false;
        for (MethodInsnNode call : calls(mark)) {
            if (call.name.equals("getBlockState")) readsCrosshair = true;
            if (call.owner.equals(CONTAINER_SCANNER) && call.name.equals("isContainerBlock")) checksContainerBlock = true;
            if (call.owner.equals(LOCAL_MARKS) && call.name.equals("toggle")) togglesLocal = true;
            if (call.owner.equals(LOCAL_MARKS) && call.name.equals("save")) saves = true;
        }
        assertTrue(readsCrosshair, "登记必须读准星/方块状态");
        assertTrue(checksContainerBlock, "只允许登记箱子/木桶/潜影盒");
        assertTrue(togglesLocal, "登记必须写入本 Mod 自己的清单");
        assertTrue(saves, "登记后必须写盘，重启游戏仍然有效");
    }

    private static ClassNode load(String resource) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream stream = MarkingGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        }
        return fail(node.name + "." + name + descriptor + " 不存在");
    }

    private static Iterable<MethodInsnNode> calls(MethodNode method) {
        java.util.List<MethodInsnNode> result = new java.util.ArrayList<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof MethodInsnNode call) result.add(call);
        }
        return result;
    }

    private static boolean containsString(MethodNode method, String text) {
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LdcInsnNode ldc && text.equals(ldc.cst)) return true;
        }
        return false;
    }
}
