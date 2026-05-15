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

    /**
     * Control-flow flattening must preserve semantics. sum(n) = n*(n+1)/2 via
     * a loop with a back-edge + conditional — exactly the structure flattening
     * rewrites into a dispatcher. If the dispatcher/state wiring is wrong the
     * verifier rejects it or the result is incorrect.
     */
    @Test
    void controlFlowFlatteningPreservesSemantics(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "crazy_e2e_f/Foo", null, "java/lang/Object", null);

        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);

        // No-local-write, multi-block method (the only shape flattening accepts):
        //   static int pick(int a, int b){
        //     if (a > 0) return a + b;
        //     if (b > 0) return a - b;
        //     return 0;
        //   }
        var pick = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(II)I", null, null);
        org.objectweb.asm.Label l1 = new org.objectweb.asm.Label();
        org.objectweb.asm.Label l2 = new org.objectweb.asm.Label();
        pick.visitVarInsn(Opcodes.ILOAD, 0);
        pick.visitJumpInsn(Opcodes.IFLE, l1);                 // a <= 0 -> L1
        pick.visitVarInsn(Opcodes.ILOAD, 0);
        pick.visitVarInsn(Opcodes.ILOAD, 1);
        pick.visitInsn(Opcodes.IADD);
        pick.visitInsn(Opcodes.IRETURN);
        pick.visitLabel(l1);
        pick.visitVarInsn(Opcodes.ILOAD, 1);
        pick.visitJumpInsn(Opcodes.IFLE, l2);                 // b <= 0 -> L2
        pick.visitVarInsn(Opcodes.ILOAD, 0);
        pick.visitVarInsn(Opcodes.ILOAD, 1);
        pick.visitInsn(Opcodes.ISUB);
        pick.visitInsn(Opcodes.IRETURN);
        pick.visitLabel(l2);
        pick.visitInsn(Opcodes.ICONST_0);
        pick.visitInsn(Opcodes.IRETURN);
        pick.visitMaxs(0, 0);

        // WITH local writes + a loop (the generalized path): exercises
        // prologue pre-init of written int locals.
        //   static int sum(int n){ int s=0,i=1; while(i<=n){ s+=i; i++; } return s; }
        var sum = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sum", "(I)I", null, null);
        org.objectweb.asm.Label head = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        sum.visitInsn(Opcodes.ICONST_0);
        sum.visitVarInsn(Opcodes.ISTORE, 1);   // s
        sum.visitInsn(Opcodes.ICONST_1);
        sum.visitVarInsn(Opcodes.ISTORE, 2);   // i
        sum.visitLabel(head);
        sum.visitVarInsn(Opcodes.ILOAD, 2);
        sum.visitVarInsn(Opcodes.ILOAD, 0);
        sum.visitJumpInsn(Opcodes.IF_ICMPGT, end);
        sum.visitVarInsn(Opcodes.ILOAD, 1);
        sum.visitVarInsn(Opcodes.ILOAD, 2);
        sum.visitInsn(Opcodes.IADD);
        sum.visitVarInsn(Opcodes.ISTORE, 1);
        sum.visitIincInsn(2, 1);
        sum.visitJumpInsn(Opcodes.GOTO, head);
        sum.visitLabel(end);
        sum.visitVarInsn(Opcodes.ILOAD, 1);
        sum.visitInsn(Opcodes.IRETURN);
        sum.visitMaxs(0, 0);
        cw.visitEnd();

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            jos.putNextEntry(new JarEntry("crazy_e2e_f/Foo.class"));
            jos.write(cw.toByteArray());
            jos.closeEntry();
        }

        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("crazy_e2e_f");
        cfg.flattenPackages = false;
        cfg.renameClasses = false;
        cfg.renameMethods = false;
        cfg.flattenControlFlow = true;
        cfg.flattenChance = 100;
        cfg.seed = 7L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            Class<?> foo = cl.loadClass("crazy_e2e_f.Foo");
            var mh = foo.getMethod("pick", int.class, int.class);
            assertEquals(8,  (int) (Integer) mh.invoke(null, 5, 3),  "pick(5,3)=a+b=8");
            assertEquals(-5, (int) (Integer) mh.invoke(null, -1, 4), "pick(-1,4)=a-b=-5");
            assertEquals(0,  (int) (Integer) mh.invoke(null, -2, -3), "pick(-2,-3)=0");

            var sumM = foo.getMethod("sum", int.class);
            assertEquals(55,   (int) (Integer) sumM.invoke(null, 10),  "sum(10)=55 after flattening w/ locals");
            assertEquals(0,    (int) (Integer) sumM.invoke(null, 0),   "sum(0)=0");
            assertEquals(5050, (int) (Integer) sumM.invoke(null, 100), "sum(100)=5050");
        }
    }
}
