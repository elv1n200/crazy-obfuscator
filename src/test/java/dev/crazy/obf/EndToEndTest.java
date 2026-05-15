package dev.crazy.obf;

import dev.crazy.obf.config.ObfConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds a minimal in-memory jar, runs the full pipeline, then loads & invokes
 * the result with a URLClassLoader. Verifies that:
 *   - the obfuscated class is still loadable by the JVM verifier
 *   - the encrypted string returns its original value when used at runtime
 *   - the number transform produces the original integer
 */
public class EndToEndTest {

    @Test
    void roundTripExecutesCorrectly(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // 1. generate a class:
        //    public class crazy_e2e_t.Foo { public static String greet() { return "hi"; } public static int forty() { return 40; } }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "crazy_e2e_t/Foo", null, "java/lang/Object", null);

        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);

        var greet = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "greet", "()Ljava/lang/String;", null, null);
        greet.visitLdcInsn("hi");
        greet.visitInsn(Opcodes.ARETURN);
        greet.visitMaxs(0, 0);

        var forty = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "forty", "()I", null, null);
        forty.visitIntInsn(Opcodes.BIPUSH, 40);
        forty.visitInsn(Opcodes.IRETURN);
        forty.visitMaxs(0, 0);
        cw.visitEnd();

        byte[] foo = cw.toByteArray();

        // 2. pack into a jar
        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            jos.putNextEntry(new JarEntry("crazy_e2e_t/Foo.class"));
            jos.write(foo);
            jos.closeEntry();
        }

        // 3. obfuscate
        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("crazy_e2e_t");
        cfg.flattenPackages = false; // keep original package so we can find the class
        cfg.renameClasses = false;
        cfg.renameMethods = false;
        cfg.seed = 12345L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        // 4. load & invoke
        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            Class<?> foo2 = cl.loadClass("crazy_e2e_t.Foo");
            String greetResult = (String) foo2.getMethod("greet").invoke(null);
            int fortyResult = (Integer) foo2.getMethod("forty").invoke(null);
            assertEquals("hi", greetResult);
            assertEquals(40, fortyResult);
        }
    }

    /**
     * Verifies invokedynamic reference hiding actually resolves and runs:
     * Foo.entry() calls Foo.helper(int) internally; with hideReferences the
     * internal INVOKESTATIC becomes invokedynamic bound to the injected
     * crazy/Indy bootstrap. If the bootstrap (and its XOR decode) is wrong the
     * class won't link or entry() won't return 21.
     */
    @Test
    void invokedynamicRefHidingResolvesAtRuntime(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "crazy_e2e_i/Foo", null, "java/lang/Object", null);

        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);

        var helper = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "helper", "(I)I", null, null);
        helper.visitVarInsn(Opcodes.ILOAD, 0);
        helper.visitInsn(Opcodes.ICONST_3);
        helper.visitInsn(Opcodes.IMUL);
        helper.visitInsn(Opcodes.IRETURN);
        helper.visitMaxs(0, 0);

        var entry = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "entry", "()I", null, null);
        entry.visitIntInsn(Opcodes.BIPUSH, 7);
        entry.visitMethodInsn(Opcodes.INVOKESTATIC, "crazy_e2e_i/Foo", "helper", "(I)I", false);
        entry.visitInsn(Opcodes.IRETURN);
        entry.visitMaxs(0, 0);
        cw.visitEnd();

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            jos.putNextEntry(new JarEntry("crazy_e2e_i/Foo.class"));
            jos.write(cw.toByteArray());
            jos.closeEntry();
        }

        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("crazy_e2e_i");
        cfg.flattenPackages = false;
        cfg.renameClasses = false;
        cfg.renameMethods = false;
        cfg.hideReferences = true;
        cfg.seed = 99L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            Class<?> foo = cl.loadClass("crazy_e2e_i.Foo");
            int r = (Integer) foo.getMethod("entry").invoke(null);
            assertEquals(21, r, "indy-resolved Foo.helper(7) should be 21");
            // sanity: the bootstrap class was actually injected
            assertNotNull(cl.loadClass("crazy.Indy"));
        }
    }
}
