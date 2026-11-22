package dev.feederrestock.integration;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The printer pause is a {@code @Pseudo} mixin, so a wrong target or a required
 * injection would only show up as an in-game failure. These checks pin the parts
 * that can be verified without the printer on the classpath: the target class,
 * the injected method, and the fact that a missing method must not crash startup.
 */
class PrinterMixinGuardTest {
    private static final String PRINTER = "me.aleksilassila.litematica.printer.Printer";

    @Test
    void targetsThePrinterMainClass() throws IOException {
        ClassNode node = read("dev/feederrestock/mixin/PrinterPauseMixin");
        AnnotationNode mixin = find(node.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        if (mixin == null) mixin = find(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertNotNull(mixin, "PrinterPauseMixin 缺少 @Mixin");
        Object targets = value(mixin, "targets");
        assertInstanceOf(List.class, targets);
        assertEquals(List.of(PRINTER), targets);
        // The printer's own class name is not obfuscated, so no name mapping may be
        // applied to it: the mixin must stay remap = false.
        assertEquals(Boolean.FALSE, value(mixin, "remap"), "打印机类名不做混淆映射");
        assertTrue(hasAnnotation(node.visibleAnnotations, node.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/Pseudo;"), "@Pseudo 让没装打印机时不会失败");
    }

    @Test
    void cancelsThePrinterTickWithoutRequiringIt() throws IOException {
        ClassNode node = read("dev/feederrestock/mixin/PrinterPauseMixin");
        MethodNode callback = null;
        AnnotationNode inject = null;
        for (MethodNode method : node.methods) {
            AnnotationNode found = find(method.visibleAnnotations,
                    "Lorg/spongepowered/asm/mixin/injection/Inject;");
            if (found == null) found = find(method.invisibleAnnotations,
                    "Lorg/spongepowered/asm/mixin/injection/Inject;");
            if (found != null) {
                callback = method;
                inject = found;
                break;
            }
        }
        assertNotNull(inject, "缺少 @Inject");
        assertEquals(List.of("onGameTick"), value(inject, "method"));
        assertEquals(Boolean.TRUE, value(inject, "cancellable"));
        assertEquals(0, value(inject, "require"), "打印机升级后缺少该方法时不能崩游戏");
        assertEquals(Boolean.FALSE, value(inject, "remap"));
        // Printer#onGameTick returns boolean, so the callback must be returnable.
        Type[] arguments = Type.getArgumentTypes(callback.desc);
        assertEquals("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;",
                arguments[arguments.length - 1].getDescriptor());
        assertEquals(Type.VOID_TYPE, Type.getReturnType(callback.desc));
        // Printer#onGameTick is an instance method, so the callback must not be static.
        assertEquals(0, callback.access & Opcodes.ACC_STATIC);
    }

    private static boolean hasAnnotation(List<AnnotationNode> visible, List<AnnotationNode> invisible,
                                         String descriptor) {
        return find(visible, descriptor) != null || find(invisible, descriptor) != null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static AnnotationNode find(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.equals(descriptor)) return annotation;
        }
        return null;
    }

    private static ClassNode read(String internalName) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream stream = PrinterMixinGuardTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(stream, internalName);
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }
}
