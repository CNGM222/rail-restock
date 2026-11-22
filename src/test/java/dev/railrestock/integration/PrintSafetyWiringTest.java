package dev.railrestock.integration;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The safety net only works while it is wired in. A refactor that drops the
 * container/cursor check from {@code InteractionGate} would silently restore the
 * "prints a whole area with the wrong block" failure, so the wiring is pinned here.
 */
class PrintSafetyWiringTest {
    private static final String GATE = "dev/railrestock/runtime/InteractionGate";
    private static final String GUARD = "dev/railrestock/runtime/InventoryGuard";

    @Test
    void interactionGateConsultsTheInventoryGuard() throws IOException {
        MethodNode isBusy = method(read(GATE), "isBusy");
        List<String> calls = calls(isBusy);
        assertTrue(calls.contains("dev/railrestock/runtime/InventoryGuard.blocksPrinting()Z"),
                "InteractionGate.isBusy 必须同时检查容器/鼠标状态：" + calls);
    }

    @Test
    void inventoryGuardUsesThePurePolicyAndRealClientState() throws IOException {
        ClassNode node = read(GUARD);
        MethodNode blocks = method(node, "blocksPrinting");
        assertTrue(calls(blocks).contains("dev/railrestock/logic/PrintSafety.blocks(ZZ)Z"),
                "InventoryGuard 必须走统一的 PrintSafety 判断");
        List<String> fields = new ArrayList<>();
        for (AbstractInsnNode instruction : blocks.instructions) {
            if (instruction instanceof FieldInsnNode field) {
                fields.add(field.name);
            }
        }
        assertTrue(fields.contains("playerScreenHandler"), "必须比较当前界面处理器：" + fields);
        assertTrue(calls(blocks).contains("net/minecraft/screen/ScreenHandler.getCursorStack()Lnet/minecraft/item/ItemStack;"),
                "必须检查鼠标上的物品：" + calls(blocks));
    }

    private static MethodNode method(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name)) return method;
        }
        throw new AssertionError("缺少方法 " + name);
    }

    private static List<String> calls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                result.add(call.owner + "." + call.name + call.desc);
            }
        }
        return result;
    }

    private static ClassNode read(String internalName) throws IOException {
        try (InputStream stream = PrintSafetyWiringTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(stream, internalName);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }
}
