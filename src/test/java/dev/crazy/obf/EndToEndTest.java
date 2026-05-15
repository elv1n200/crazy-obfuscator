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
}
