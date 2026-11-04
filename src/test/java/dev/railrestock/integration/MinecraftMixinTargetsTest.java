package dev.railrestock.integration;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies injection selectors against the actual 1.21.11 classes on the test
 * classpath. A wrong descriptor string compiles fine but fails at game start, so
 * this is the only automated guard for it.
 */
class MinecraftMixinTargetsTest {
    @Test
    void minecraftClientTargetsExist() throws IOException {
        verify("MinecraftClientMixin", "net/minecraft/client/MinecraftClient");
    }

    @Test
    void handledScreenTargetsExist() throws IOException {
        verify("HandledScreenMixin", "net/minecraft/client/gui/screen/ingame/HandledScreen");
    }

    @Test
    void networkHandlerTargetsExist() throws IOException {
        verify("ClientPlayNetworkHandlerMixin", "net/minecraft/client/network/ClientPlayNetworkHandler");
    }

    private void verify(String mixin, String targetName) throws IOException {
        ClassNode source = read("dev/railrestock/mixin/" + mixin);
        ClassNode target = read(targetName);
        int checked = 0;
        for (MethodNode callback : source.methods) {
            List<AnnotationNode> annotations = new ArrayList<>();
            if (callback.visibleAnnotations != null) annotations.addAll(callback.visibleAnnotations);
            if (callback.invisibleAnnotations != null) annotations.addAll(callback.invisibleAnnotations);
            for (AnnotationNode annotation : annotations) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                Object selectors = null;
                for (int i = 0; i < annotation.values.size(); i += 2) {
                    if (annotation.values.get(i).equals("method")) selectors = annotation.values.get(i + 1);
                }
                assertInstanceOf(List.class, selectors);
                for (Object value : (List<?>) selectors) {
                    String selector = value.toString();
                    int descriptorStart = selector.indexOf('(');
                    String name = descriptorStart >= 0 ? selector.substring(0, descriptorStart) : selector;
                    String descriptor = descriptorStart >= 0 ? selector.substring(descriptorStart) : null;
                    List<MethodNode> matches = target.methods.stream()
                            .filter(m -> m.name.equals(name) && (descriptor == null || m.desc.equals(descriptor)))
                            .toList();
                    assertEquals(1, matches.size(), mixin + " target: " + selector);
                    MethodNode actual = matches.getFirst();
                    Type[] expectedArgs = Type.getArgumentTypes(actual.desc);
                    Type[] callbackArgs = Type.getArgumentTypes(callback.desc);
                    assertEquals(expectedArgs.length + 1, callbackArgs.length, selector);
                    for (int i = 0; i < expectedArgs.length; i++) {
                        assertEquals(expectedArgs[i], callbackArgs[i], selector + " argument " + i);
                    }
                    boolean returnsVoid = Type.getReturnType(actual.desc).equals(Type.VOID_TYPE);
                    String info = returnsVoid
                            ? "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
                            : "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
                    assertEquals(info, callbackArgs[callbackArgs.length - 1].getDescriptor(), selector);
                    assertEquals(Type.VOID_TYPE, Type.getReturnType(callback.desc));
                    assertEquals(actual.access & Opcodes.ACC_STATIC, callback.access & Opcodes.ACC_STATIC, selector);
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "No injection checked for " + mixin);
    }

    private ClassNode read(String internalName) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(stream, internalName);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
