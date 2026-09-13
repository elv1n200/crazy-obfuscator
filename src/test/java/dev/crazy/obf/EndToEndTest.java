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
     * Condy string hiding: with hideStringsCondy the ldc "secret" becomes a
     * CONSTANT_Dynamic resolved by the injected crazy/C bootstrap. Verifies:
     *   - the plaintext is gone from the (decompressed) class bytes
     *   - the string still materialises to its original value at runtime
     *   - the crazy/C bootstrap class was injected
     */
    @Test
    void condyStringHidingResolvesAtRuntime(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        final String SECRET = "https://secret.example.com/key=ABCDEF";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "crazy_e2e_c/Foo", null, "java/lang/Object", null);
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
        var secret = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "secret", "()Ljava/lang/String;", null, null);
        secret.visitLdcInsn(SECRET);
        secret.visitInsn(Opcodes.ARETURN); secret.visitMaxs(0, 0);
        cw.visitEnd();

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            jos.putNextEntry(new JarEntry("crazy_e2e_c/Foo.class"));
            jos.write(cw.toByteArray());
            jos.closeEntry();
        }

        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("crazy_e2e_c");
        cfg.flattenPackages = false;
        cfg.renameClasses = false; cfg.renameMethods = false;
        cfg.encryptStrings = true;
        cfg.hideStringsCondy = true;
        cfg.seed = 4242L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        // plaintext must be gone from the decompressed class entry
        byte[] cls;
        try (var jf = new java.util.jar.JarFile(outJar.toFile());
             var is = jf.getInputStream(jf.getEntry("crazy_e2e_c/Foo.class"))) {
            cls = is.readAllBytes();
        }
        String raw = new String(cls, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(SECRET), "condy: plaintext must be gone from the class");

        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            Class<?> foo = cl.loadClass("crazy_e2e_c.Foo");
            assertEquals(SECRET, foo.getMethod("secret").invoke(null), "condy must decode at link time");
            assertNotNull(cl.loadClass("crazy.C"), "condy bootstrap class must be injected");
        }
    }

    /**
     * Anti-decompile wrap must be behaviour-neutral: the opaque rethrow handler
     * neither changes a normal return nor swallows a thrown exception.
     */
    @Test
    void antiDecompileIsBehaviorNeutral(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "crazy_e2e_a/Foo", null, "java/lang/Object", null);
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);

        // static int calc(int a,int b){ int s=a+b; s*=2; s-=a; if(s>0) return s; return -s; }
        var calc = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calc", "(II)I", null, null);
        org.objectweb.asm.Label neg = new org.objectweb.asm.Label();
        calc.visitVarInsn(Opcodes.ILOAD, 0);
        calc.visitVarInsn(Opcodes.ILOAD, 1);
        calc.visitInsn(Opcodes.IADD);
        calc.visitVarInsn(Opcodes.ISTORE, 2);
        calc.visitVarInsn(Opcodes.ILOAD, 2);
        calc.visitInsn(Opcodes.ICONST_2);
        calc.visitInsn(Opcodes.IMUL);
        calc.visitVarInsn(Opcodes.ISTORE, 2);
        calc.visitVarInsn(Opcodes.ILOAD, 2);
        calc.visitVarInsn(Opcodes.ILOAD, 0);
        calc.visitInsn(Opcodes.ISUB);
        calc.visitVarInsn(Opcodes.ISTORE, 2);
        calc.visitVarInsn(Opcodes.ILOAD, 2);
        calc.visitJumpInsn(Opcodes.IFLE, neg);
        calc.visitVarInsn(Opcodes.ILOAD, 2);
        calc.visitInsn(Opcodes.IRETURN);
        calc.visitLabel(neg);
        calc.visitVarInsn(Opcodes.ILOAD, 2);
        calc.visitInsn(Opcodes.INEG);
        calc.visitInsn(Opcodes.IRETURN);
        calc.visitMaxs(0, 0);

        // static int boom(){ throw new IllegalStateException("x"); } (6 insns incl. dup/ldc)
        var boom = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "boom", "()I", null, null);
        boom.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        boom.visitInsn(Opcodes.DUP);
        boom.visitLdcInsn("x");
        boom.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
            "(Ljava/lang/String;)V", false);
        boom.visitInsn(Opcodes.ATHROW);
        boom.visitMaxs(0, 0);
        cw.visitEnd();

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            jos.putNextEntry(new JarEntry("crazy_e2e_a/Foo.class"));
            jos.write(cw.toByteArray());
            jos.closeEntry();
        }

        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("crazy_e2e_a");
        cfg.flattenPackages = false;
        cfg.renameClasses = false; cfg.renameMethods = false;
        cfg.antiDecompile = true;
        cfg.antiDecompileChance = 100;
        cfg.seed = 5L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            Class<?> foo = cl.loadClass("crazy_e2e_a.Foo");
            var calcM = foo.getMethod("calc", int.class, int.class);
            assertEquals(13, (int) (Integer) calcM.invoke(null, 3, 5), "calc(3,5): ((3+5)*2)-3=13");
            assertEquals(3,  (int) (Integer) calcM.invoke(null, 1, 1), "calc(1,1): ((1+1)*2)-1=3");
            assertEquals(5,  (int) (Integer) calcM.invoke(null, -5, 0), "calc(-5,0): s=-5<=0 -> -s=5");
            // exception must still propagate (handler rethrows, doesn't swallow)
            var boomM = foo.getMethod("boom");
            var ex = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> boomM.invoke(null));
            assertInstanceOf(IllegalStateException.class, ex.getCause(), "thrown exception must propagate unchanged");
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

        // REFERENCE local written in different blocks, single concrete type
        // (String) — exercises the null-init reference-slot path.
        //   static String make(boolean b){
        //     String r;
        //     if (b) r = "x"; else r = String.valueOf(b);
        //     return r;
        //   }
        var make = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "make", "(Z)Ljava/lang/String;", null, null);
        org.objectweb.asm.Label mElse = new org.objectweb.asm.Label();
        org.objectweb.asm.Label mEnd = new org.objectweb.asm.Label();
        make.visitVarInsn(Opcodes.ILOAD, 0);
        make.visitJumpInsn(Opcodes.IFEQ, mElse);          // !b -> else
        make.visitLdcInsn("x");
        make.visitVarInsn(Opcodes.ASTORE, 1);             // r = "x"
        make.visitJumpInsn(Opcodes.GOTO, mEnd);
        make.visitLabel(mElse);
        make.visitVarInsn(Opcodes.ILOAD, 0);
        make.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
            "(Z)Ljava/lang/String;", false);
        make.visitVarInsn(Opcodes.ASTORE, 1);             // r = String.valueOf(b)
        make.visitLabel(mEnd);
        make.visitVarInsn(Opcodes.ALOAD, 1);
        make.visitInsn(Opcodes.ARETURN);
        make.visitMaxs(0, 0);
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

            var makeM = foo.getMethod("make", boolean.class);
            // ref-local method is (soundly) NOT flattened — must still be left intact + correct
            assertEquals("x",     makeM.invoke(null, true),  "make(true)=\"x\"");
            assertEquals("false", makeM.invoke(null, false), "make(false)=String.valueOf(false)");
        }
    }

    /**
     * Byte injection: with injectJunkAttributes the class/method/field structures
     * get junk {@code attribute_info} blobs the JVM must ignore. Verifies:
     *   - a decoy attribute name is actually present in the raw class bytes
     *   - the class still loads and runs correctly (JVM ignored the junk)
     */
    @Test
    void byteInjectionIsIgnoredByJvm(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "crazy_e2e_b/Foo", null, "java/lang/Object", null);
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
        // a field so the field-attribute path is exercised too
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "TAG", "I", null, 7).visitEnd();
        var val = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "val", "()I", null, null);
        val.visitIntInsn(Opcodes.BIPUSH, 99);
        val.visitInsn(Opcodes.IRETURN); val.visitMaxs(0, 0);
        cw.visitEnd();

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            jos.putNextEntry(new JarEntry("crazy_e2e_b/Foo.class"));
            jos.write(cw.toByteArray());
            jos.closeEntry();
        }

        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("crazy_e2e_b");
        cfg.flattenPackages = false;
        cfg.renameClasses = false; cfg.renameMethods = false; cfg.renameFields = false;
        cfg.encryptStrings = false; cfg.obfuscateNumbers = false; cfg.obfuscateFlow = false;
        cfg.injectJunk = false; cfg.stripMetadata = false;
        cfg.injectJunkAttributes = true;
        cfg.junkAttributeChance = 100;
        cfg.seed = 1234L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        byte[] cls;
        try (var jf = new java.util.jar.JarFile(outJar.toFile());
             var is = jf.getInputStream(jf.getEntry("crazy_e2e_b/Foo.class"))) {
            cls = is.readAllBytes();
        }
        String raw = new String(cls, java.nio.charset.StandardCharsets.ISO_8859_1);
        String[] decoys = {"Scala", "TASTY", "JADX-Debug", "Kotlinc", "SourceID",
                           "DebugId", "CrazyMark", "org.eclipse.jdt", "com.intellij.rt"};
        boolean found = false;
        for (String d : decoys) if (raw.contains(d)) { found = true; break; }
        assertTrue(found, "at least one junk attribute name must be in the class bytes");

        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            Class<?> foo = cl.loadClass("crazy_e2e_b.Foo");
            assertEquals(99, (int) (Integer) foo.getMethod("val").invoke(null),
                "class with injected junk attributes must still load & run");
            assertEquals(7, foo.getField("TAG").getInt(null), "field with junk attribute still readable");
        }
    }

    /**
     * Method/field renaming must actually happen AND stay correct across an
     * interface, an override chain with a {@code super} call, a static method,
     * and inherited field access — the path that ASM's SimpleRemapper key
     * mismatch previously left silently un-applied.
     *
     * Hierarchy (package r):
     *   interface Iface { int apply(int); }
     *   class Base implements Iface { public int bias=100;
     *       public int apply(int x){ return x + bias; }
     *       public static int stat(int y){ return y*10; } }
     *   class Derived extends Base { public int apply(int x){ return super.apply(x)+1; } }
     *   class Runner (excluded) { public static int run(){
     *       Iface i=new Derived(); return i.apply(5) + Base.stat(3) + new Derived().bias; } }
     *   run() == 106 + 30 + 100 == 236, and no member named apply/stat/bias may survive.
     */
    @Test
    void methodAndFieldRenamingActuallyAppliesAndStaysCorrect(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        byte[] iface, base, derived, runner;
        {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "r/Iface", null, "java/lang/Object", null);
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "apply", "(I)I", null, null).visitEnd();
            cw.visitEnd();
            iface = cw.toByteArray();
        }
        {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "r/Base", null, "java/lang/Object", new String[]{"r/Iface"});
            cw.visitField(Opcodes.ACC_PUBLIC, "bias", "I", null, null).visitEnd();
            var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitIntInsn(Opcodes.BIPUSH, 100);
            ctor.visitFieldInsn(Opcodes.PUTFIELD, "r/Base", "bias", "I");
            ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
            var apply = cw.visitMethod(Opcodes.ACC_PUBLIC, "apply", "(I)I", null, null);
            apply.visitVarInsn(Opcodes.ILOAD, 1);
            apply.visitVarInsn(Opcodes.ALOAD, 0);
            apply.visitFieldInsn(Opcodes.GETFIELD, "r/Base", "bias", "I");
            apply.visitInsn(Opcodes.IADD);
            apply.visitInsn(Opcodes.IRETURN); apply.visitMaxs(0, 0);
            var stat = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "stat", "(I)I", null, null);
            stat.visitVarInsn(Opcodes.ILOAD, 0);
            stat.visitIntInsn(Opcodes.BIPUSH, 10);
            stat.visitInsn(Opcodes.IMUL);
            stat.visitInsn(Opcodes.IRETURN); stat.visitMaxs(0, 0);
            cw.visitEnd();
            base = cw.toByteArray();
        }
        {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "r/Derived", null, "r/Base", null);
            var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "r/Base", "<init>", "()V", false);
            ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
            var apply = cw.visitMethod(Opcodes.ACC_PUBLIC, "apply", "(I)I", null, null);
            apply.visitVarInsn(Opcodes.ALOAD, 0);
            apply.visitVarInsn(Opcodes.ILOAD, 1);
            apply.visitMethodInsn(Opcodes.INVOKESPECIAL, "r/Base", "apply", "(I)I", false); // super.apply
            apply.visitInsn(Opcodes.ICONST_1);
            apply.visitInsn(Opcodes.IADD);
            apply.visitInsn(Opcodes.IRETURN); apply.visitMaxs(0, 0);
            cw.visitEnd();
            derived = cw.toByteArray();
        }
        {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "r/Runner", null, "java/lang/Object", null);
            var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
            var run = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
            run.visitTypeInsn(Opcodes.NEW, "r/Derived");
            run.visitInsn(Opcodes.DUP);
            run.visitMethodInsn(Opcodes.INVOKESPECIAL, "r/Derived", "<init>", "()V", false);
            run.visitVarInsn(Opcodes.ASTORE, 0);                 // Iface i = new Derived()
            run.visitVarInsn(Opcodes.ALOAD, 0);
            run.visitInsn(Opcodes.ICONST_5);
            run.visitMethodInsn(Opcodes.INVOKEINTERFACE, "r/Iface", "apply", "(I)I", true); // 106
            run.visitInsn(Opcodes.ICONST_3);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, "r/Base", "stat", "(I)I", false);      // 30
            run.visitInsn(Opcodes.IADD);                          // 136
            run.visitTypeInsn(Opcodes.NEW, "r/Derived");
            run.visitInsn(Opcodes.DUP);
            run.visitMethodInsn(Opcodes.INVOKESPECIAL, "r/Derived", "<init>", "()V", false);
            run.visitFieldInsn(Opcodes.GETFIELD, "r/Derived", "bias", "I"); // inherited field, 100
            run.visitInsn(Opcodes.IADD);                          // 236
            run.visitInsn(Opcodes.IRETURN); run.visitMaxs(0, 0);
            cw.visitEnd();
            runner = cw.toByteArray();
        }

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            for (var e : new Object[][]{{"r/Iface.class", iface}, {"r/Base.class", base},
                                        {"r/Derived.class", derived}, {"r/Runner.class", runner}}) {
                jos.putNextEntry(new JarEntry((String) e[0]));
                jos.write((byte[]) e[1]);
                jos.closeEntry();
            }
        }

        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("r");
        cfg.excludeClasses = new java.util.ArrayList<>(java.util.List.of("r/Runner"));
        cfg.flattenPackages = false;
        cfg.renameClasses = true; cfg.renameMethods = true; cfg.renameFields = true;
        cfg.encryptStrings = false; cfg.obfuscateNumbers = false; cfg.obfuscateFlow = false;
        cfg.injectJunk = false; cfg.stripMetadata = false;
        cfg.seed = 424242L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        // renaming must have actually happened: no original member name survives
        try (var jf = new java.util.jar.JarFile(outJar.toFile())) {
            var it = jf.entries();
            while (it.hasMoreElements()) {
                var je = it.nextElement();
                if (!je.getName().endsWith(".class")) continue;
                byte[] b; try (var is = jf.getInputStream(je)) { b = is.readAllBytes(); }
                ClassNode cn = new ClassNode();
                new ClassReader(b).accept(cn, 0);
                if ("r/Runner".equals(cn.name)) continue; // excluded — keeps its names
                if (cn.name.startsWith("crazy/")) continue;
                if (cn.methods != null) for (var m : cn.methods)
                    assertNotEquals("apply", m.name, "method 'apply' should be renamed in " + cn.name);
                if (cn.methods != null) for (var m : cn.methods)
                    assertNotEquals("stat", m.name, "static 'stat' should be renamed in " + cn.name);
                if (cn.fields != null) for (var f : cn.fields)
                    assertNotEquals("bias", f.name, "field 'bias' should be renamed in " + cn.name);
            }
        }

        // ...and the renamed program must still compute the same answer
        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            Class<?> r = cl.loadClass("r.Runner");
            assertEquals(236, (int) (Integer) r.getMethod("run").invoke(null),
                "renamed hierarchy must still return 106+30+100=236");
        }
    }

    /**
     * invokedynamic field-access hiding must remove the field read/write graph
     * yet stay correct across all four kinds — instance get/set (private self +
     * public cross-class) and static get/set. Verifies the accesses became
     * invokedynamic, the crazy/FIndy bootstrap was injected, and the program
     * still returns the same value.
     *
     *   class Box { public int pub; private int priv; public static int sPub;
     *       void setBoth(int a,int b){ priv=a; pub=b; } int sum(){ return priv+pub; }
     *       static void setS(int v){ sPub=v; } static int getS(){ return sPub; } }
     *   class User { static int use(Box b){ b.pub=7; return b.pub + Box.getS(); } }
     *   Runner.run(): setBoth(3,4)->sum()=7; setS(10)->getS()=10; use(b)=7+10=17; total=34
     */
    @Test
    void invokedynamicFieldHidingResolvesAtRuntime(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        byte[] box, user, runner;
        {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fh/Box", null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PUBLIC, "pub", "I", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PRIVATE, "priv", "I", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sPub", "I", null, null).visitEnd();
            var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
            var sb = cw.visitMethod(Opcodes.ACC_PUBLIC, "setBoth", "(II)V", null, null);
            sb.visitVarInsn(Opcodes.ALOAD, 0); sb.visitVarInsn(Opcodes.ILOAD, 1);
            sb.visitFieldInsn(Opcodes.PUTFIELD, "fh/Box", "priv", "I");         // kind 1, self private
            sb.visitVarInsn(Opcodes.ALOAD, 0); sb.visitVarInsn(Opcodes.ILOAD, 2);
            sb.visitFieldInsn(Opcodes.PUTFIELD, "fh/Box", "pub", "I");          // kind 1, self public
            sb.visitInsn(Opcodes.RETURN); sb.visitMaxs(0, 0);
            var sum = cw.visitMethod(Opcodes.ACC_PUBLIC, "sum", "()I", null, null);
            sum.visitVarInsn(Opcodes.ALOAD, 0); sum.visitFieldInsn(Opcodes.GETFIELD, "fh/Box", "priv", "I"); // kind 0
            sum.visitVarInsn(Opcodes.ALOAD, 0); sum.visitFieldInsn(Opcodes.GETFIELD, "fh/Box", "pub", "I");  // kind 0
            sum.visitInsn(Opcodes.IADD); sum.visitInsn(Opcodes.IRETURN); sum.visitMaxs(0, 0);
            var setS = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setS", "(I)V", null, null);
            setS.visitVarInsn(Opcodes.ILOAD, 0);
            setS.visitFieldInsn(Opcodes.PUTSTATIC, "fh/Box", "sPub", "I");      // kind 3
            setS.visitInsn(Opcodes.RETURN); setS.visitMaxs(0, 0);
            var getS = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getS", "()I", null, null);
            getS.visitFieldInsn(Opcodes.GETSTATIC, "fh/Box", "sPub", "I");      // kind 2
            getS.visitInsn(Opcodes.IRETURN); getS.visitMaxs(0, 0);
            cw.visitEnd(); box = cw.toByteArray();
        }
        {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fh/User", null, "java/lang/Object", null);
            var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
            var use = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "(Lfh/Box;)I", null, null);
            use.visitVarInsn(Opcodes.ALOAD, 0); use.visitIntInsn(Opcodes.BIPUSH, 7);
            use.visitFieldInsn(Opcodes.PUTFIELD, "fh/Box", "pub", "I");         // kind 1, public cross-class
            use.visitVarInsn(Opcodes.ALOAD, 0);
            use.visitFieldInsn(Opcodes.GETFIELD, "fh/Box", "pub", "I");         // kind 0, public cross-class
            use.visitMethodInsn(Opcodes.INVOKESTATIC, "fh/Box", "getS", "()I", false);
            use.visitInsn(Opcodes.IADD); use.visitInsn(Opcodes.IRETURN); use.visitMaxs(0, 0);
            cw.visitEnd(); user = cw.toByteArray();
        }
        {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fh/Runner", null, "java/lang/Object", null);
            var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
            var run = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
            run.visitTypeInsn(Opcodes.NEW, "fh/Box"); run.visitInsn(Opcodes.DUP);
            run.visitMethodInsn(Opcodes.INVOKESPECIAL, "fh/Box", "<init>", "()V", false);
            run.visitVarInsn(Opcodes.ASTORE, 0);
            run.visitVarInsn(Opcodes.ALOAD, 0); run.visitInsn(Opcodes.ICONST_3); run.visitInsn(Opcodes.ICONST_4);
            run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "fh/Box", "setBoth", "(II)V", false);
            run.visitVarInsn(Opcodes.ALOAD, 0);
            run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "fh/Box", "sum", "()I", false);
            run.visitVarInsn(Opcodes.ISTORE, 1);                 // s=7
            run.visitIntInsn(Opcodes.BIPUSH, 10);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, "fh/Box", "setS", "(I)V", false);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, "fh/Box", "getS", "()I", false);
            run.visitVarInsn(Opcodes.ISTORE, 2);                 // t=10
            run.visitVarInsn(Opcodes.ALOAD, 0);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, "fh/User", "use", "(Lfh/Box;)I", false);
            run.visitVarInsn(Opcodes.ISTORE, 3);                 // u=17
            run.visitVarInsn(Opcodes.ILOAD, 1); run.visitVarInsn(Opcodes.ILOAD, 2); run.visitInsn(Opcodes.IADD);
            run.visitVarInsn(Opcodes.ILOAD, 3); run.visitInsn(Opcodes.IADD);
            run.visitInsn(Opcodes.IRETURN); run.visitMaxs(0, 0);
            cw.visitEnd(); runner = cw.toByteArray();
        }

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            for (var e : new Object[][]{{"fh/Box.class", box}, {"fh/User.class", user}, {"fh/Runner.class", runner}}) {
                jos.putNextEntry(new JarEntry((String) e[0])); jos.write((byte[]) e[1]); jos.closeEntry();
            }
        }

        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("fh");
        cfg.flattenPackages = false;
        cfg.renameClasses = false; cfg.renameMethods = false; cfg.renameFields = false;
        cfg.encryptStrings = false; cfg.obfuscateNumbers = false; cfg.obfuscateFlow = false;
        cfg.injectJunk = false; cfg.stripMetadata = false;
        cfg.hideFields = true;
        cfg.seed = 77L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        // the field access graph must be gone: no GET/PUT of pub/priv/sPub remains,
        // and each was replaced by an invokedynamic "f"
        int indy = 0, fieldInsns = 0;
        try (var jf = new java.util.jar.JarFile(outJar.toFile())) {
            var it = jf.entries();
            while (it.hasMoreElements()) {
                var je = it.nextElement();
                if (!je.getName().startsWith("fh/") || !je.getName().endsWith(".class")) continue;
                byte[] b; try (var is = jf.getInputStream(je)) { b = is.readAllBytes(); }
                ClassNode cn = new ClassNode();
                new ClassReader(b).accept(cn, 0);
                for (var m : cn.methods) {
                    if (m.instructions == null) continue;
                    for (var in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                        if (in instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode idn && "f".equals(idn.name)) indy++;
                        if (in instanceof org.objectweb.asm.tree.FieldInsnNode fin
                            && (fin.name.equals("pub") || fin.name.equals("priv") || fin.name.equals("sPub"))) fieldInsns++;
                    }
                }
            }
        }
        assertEquals(0, fieldInsns, "no direct field access to pub/priv/sPub should remain");
        assertEquals(8, indy, "all 8 field accesses should be invokedynamic");

        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            assertNotNull(cl.loadClass("crazy.FIndy"), "field-hiding bootstrap must be injected");
            Class<?> r = cl.loadClass("fh.Runner");
            assertEquals(34, (int) (Integer) r.getMethod("run").invoke(null),
                "indy field hiding must preserve behaviour: 7 + 10 + 17 == 34");
        }
    }

    /**
     * MBA must rewrite int +,-,^,|,& to equivalent identities and stay bit-exact
     * for every input, including negatives and overflow (Integer.MIN/MAX).
     */
    @Test
    void mbaArithmeticIsBitExact(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mba/M", null, "java/lang/Object", null);
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
        int[] ops = {Opcodes.IADD, Opcodes.ISUB, Opcodes.IXOR, Opcodes.IOR, Opcodes.IAND};
        String[] names = {"add", "sub", "xor", "or", "and"};
        for (int i = 0; i < ops.length; i++) {
            var mth = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names[i], "(II)I", null, null);
            mth.visitVarInsn(Opcodes.ILOAD, 0); mth.visitVarInsn(Opcodes.ILOAD, 1);
            mth.visitInsn(ops[i]); mth.visitInsn(Opcodes.IRETURN); mth.visitMaxs(0, 0);
        }
        int[] lops = {Opcodes.LADD, Opcodes.LSUB, Opcodes.LXOR, Opcodes.LOR, Opcodes.LAND};
        String[] lnames = {"ladd", "lsub", "lxor", "lor", "land"};
        for (int i = 0; i < lops.length; i++) {
            var mth = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, lnames[i], "(JJ)J", null, null);
            mth.visitVarInsn(Opcodes.LLOAD, 0); mth.visitVarInsn(Opcodes.LLOAD, 2);
            mth.visitInsn(lops[i]); mth.visitInsn(Opcodes.LRETURN); mth.visitMaxs(0, 0);
        }
        cw.visitEnd();

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            jos.putNextEntry(new JarEntry("mba/M.class")); jos.write(cw.toByteArray()); jos.closeEntry();
        }
        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("mba");
        cfg.flattenPackages = false; cfg.renameClasses = false; cfg.renameMethods = false;
        cfg.encryptStrings = false; cfg.obfuscateNumbers = false; cfg.obfuscateFlow = false;
        cfg.injectJunk = false; cfg.stripMetadata = false;
        cfg.mbaArithmetic = true; cfg.mbaChance = 100; cfg.seed = 9L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        // evidence it ran: every MBA rewrite spills operands, so 'add' gains an
        // ISTORE and 'ladd' gains an LSTORE the trivial originals never had
        boolean sawIStore = false, sawLStore = false;
        try (var jf = new java.util.jar.JarFile(outJar.toFile());
             var is = jf.getInputStream(jf.getEntry("mba/M.class"))) {
            ClassNode cn = new ClassNode();
            new ClassReader(is.readAllBytes()).accept(cn, 0);
            for (var m : cn.methods) {
                if (m.instructions == null) continue;
                for (var in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                    if ("add".equals(m.name)  && in.getOpcode() == Opcodes.ISTORE) sawIStore = true;
                    if ("ladd".equals(m.name) && in.getOpcode() == Opcodes.LSTORE) sawLStore = true;
                }
            }
        }
        assertTrue(sawIStore, "int MBA should spill operands (ISTORE) in add");
        assertTrue(sawLStore, "long MBA should spill operands (LSTORE) in ladd");

        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            Class<?> m = cl.loadClass("mba.M");
            var add = m.getMethod("add", int.class, int.class);
            var sub = m.getMethod("sub", int.class, int.class);
            var xor = m.getMethod("xor", int.class, int.class);
            var or  = m.getMethod("or",  int.class, int.class);
            var and = m.getMethod("and", int.class, int.class);
            int[] vals = {0, 1, -1, 2, -2, 7, -7, 123456, -123456, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x55555555, 0xAAAAAAAA};
            for (int a : vals) for (int b : vals) {
                assertEquals(a + b, (int) (Integer) add.invoke(null, a, b), "add " + a + "," + b);
                assertEquals(a - b, (int) (Integer) sub.invoke(null, a, b), "sub " + a + "," + b);
                assertEquals(a ^ b, (int) (Integer) xor.invoke(null, a, b), "xor " + a + "," + b);
                assertEquals(a | b, (int) (Integer) or.invoke(null,  a, b), "or "  + a + "," + b);
                assertEquals(a & b, (int) (Integer) and.invoke(null, a, b), "and " + a + "," + b);
            }

            var ladd = m.getMethod("ladd", long.class, long.class);
            var lsub = m.getMethod("lsub", long.class, long.class);
            var lxor = m.getMethod("lxor", long.class, long.class);
            var lor  = m.getMethod("lor",  long.class, long.class);
            var land = m.getMethod("land", long.class, long.class);
            long[] lvals = {0L, 1L, -1L, 7L, -7L, 1234567890123L, -1234567890123L,
                            Long.MAX_VALUE, Long.MIN_VALUE, 0x5555555555555555L, 0xAAAAAAAAAAAAAAAAL};
            for (long a : lvals) for (long b : lvals) {
                assertEquals(a + b, (long) (Long) ladd.invoke(null, a, b), "ladd " + a + "," + b);
                assertEquals(a - b, (long) (Long) lsub.invoke(null, a, b), "lsub " + a + "," + b);
                assertEquals(a ^ b, (long) (Long) lxor.invoke(null, a, b), "lxor " + a + "," + b);
                assertEquals(a | b, (long) (Long) lor.invoke(null,  a, b), "lor "  + a + "," + b);
                assertEquals(a & b, (long) (Long) land.invoke(null, a, b), "land " + a + "," + b);
            }
        }
    }

    /**
     * Numeric condy hiding: int/long LDC constants become CONSTANT_Dynamic, so
     * the literal is gone from the pool, yet resolves to the same value at runtime.
     */
    @Test
    void numberCondyHidesConstantsButResolves(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        final int MAGIC_I = 0x1337BEEF;
        final long MAGIC_L = 0x0BADC0DEDEADBEEFL;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "nc/N", null, "java/lang/Object", null);
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
        var gi = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "gi", "()I", null, null);
        gi.visitLdcInsn(MAGIC_I); gi.visitInsn(Opcodes.IRETURN); gi.visitMaxs(0, 0);
        var gl = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "gl", "()J", null, null);
        gl.visitLdcInsn(MAGIC_L); gl.visitInsn(Opcodes.LRETURN); gl.visitMaxs(0, 0);
        cw.visitEnd();

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            jos.putNextEntry(new JarEntry("nc/N.class")); jos.write(cw.toByteArray()); jos.closeEntry();
        }
        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("nc");
        cfg.flattenPackages = false; cfg.renameClasses = false; cfg.renameMethods = false;
        cfg.encryptStrings = false; cfg.obfuscateNumbers = false; cfg.obfuscateFlow = false;
        cfg.injectJunk = false; cfg.stripMetadata = false;
        cfg.hideNumbersCondy = true; cfg.seed = 8L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        // the raw constants must no longer be a plain LDC (they're dynamic now)
        try (var jf = new java.util.jar.JarFile(outJar.toFile());
             var is = jf.getInputStream(jf.getEntry("nc/N.class"))) {
            ClassNode cn = new ClassNode();
            new ClassReader(is.readAllBytes()).accept(cn, 0);
            for (var m : cn.methods) if (m.instructions != null)
                for (var in = m.instructions.getFirst(); in != null; in = in.getNext())
                    if (in instanceof org.objectweb.asm.tree.LdcInsnNode ldc) {
                        assertFalse(ldc.cst instanceof Integer && ((Integer) ldc.cst) == MAGIC_I,
                            "int magic must not survive as a plain LDC");
                        assertFalse(ldc.cst instanceof Long && ((Long) ldc.cst) == MAGIC_L,
                            "long magic must not survive as a plain LDC");
                    }
        }

        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            assertNotNull(cl.loadClass("crazy.NC"), "numeric condy bootstrap must be injected");
            Class<?> n = cl.loadClass("nc.N");
            assertEquals(MAGIC_I, (int) (Integer) n.getMethod("gi").invoke(null), "int condy must resolve");
            assertEquals(MAGIC_L, (long) (Long) n.getMethod("gl").invoke(null), "long condy must resolve");
        }
    }

    /**
     * Opaque predicates must be inserted (dead ATHROW appears) yet never change
     * the result, for every argument including 0, negatives and MIN/MAX.
     */
    @Test
    void opaquePredicatesArePresentAndNeutral(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "op/O", null, "java/lang/Object", null);
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
        // static int twice(int x){ return x*2; }
        var twice = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "twice", "(I)I", null, null);
        twice.visitVarInsn(Opcodes.ILOAD, 0); twice.visitInsn(Opcodes.ICONST_2); twice.visitInsn(Opcodes.IMUL);
        twice.visitInsn(Opcodes.IRETURN); twice.visitMaxs(0, 0);
        cw.visitEnd();

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            jos.putNextEntry(new JarEntry("op/O.class")); jos.write(cw.toByteArray()); jos.closeEntry();
        }
        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("op");
        cfg.flattenPackages = false; cfg.renameClasses = false; cfg.renameMethods = false;
        cfg.encryptStrings = false; cfg.obfuscateNumbers = false; cfg.obfuscateFlow = false;
        cfg.injectJunk = false; cfg.stripMetadata = false;
        cfg.opaquePredicates = true; cfg.opaquePredicateChance = 100; cfg.seed = 3L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        boolean sawAthrow = false;
        try (var jf = new java.util.jar.JarFile(outJar.toFile());
             var is = jf.getInputStream(jf.getEntry("op/O.class"))) {
            ClassNode cn = new ClassNode();
            new ClassReader(is.readAllBytes()).accept(cn, 0);
            for (var mm : cn.methods) if ("twice".equals(mm.name) && mm.instructions != null)
                for (var in = mm.instructions.getFirst(); in != null; in = in.getNext())
                    if (in.getOpcode() == Opcodes.ATHROW) sawAthrow = true;
        }
        assertTrue(sawAthrow, "an opaque-predicate dead branch (ATHROW) should be present");

        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            var twiceM = cl.loadClass("op.O").getMethod("twice", int.class);
            for (int x : new int[]{0, 1, -1, 5, -5, Integer.MAX_VALUE, Integer.MIN_VALUE})
                assertEquals(x * 2, (int) (Integer) twiceM.invoke(null, x), "twice(" + x + ")");
        }
    }

    /**
     * Anti-tamper: an untouched obfuscated jar runs; editing a protected class's
     * bytes afterwards makes it throw when the code initialises (CRC mismatch).
     */
    @Test
    void antiTamperDetectsModifiedClass(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        byte[] a, b;
        {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "at/A", null, "java/lang/Object", null);
            var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
            var run = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
            run.visitMethodInsn(Opcodes.INVOKESTATIC, "at/B", "val", "()I", false);
            run.visitInsn(Opcodes.IRETURN); run.visitMaxs(0, 0);
            cw.visitEnd(); a = cw.toByteArray();
        }
        {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "at/B", null, "java/lang/Object", null);
            var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);
            var val = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "val", "()I", null, null);
            val.visitIntInsn(Opcodes.BIPUSH, 42); val.visitInsn(Opcodes.IRETURN); val.visitMaxs(0, 0);
            cw.visitEnd(); b = cw.toByteArray();
        }

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            jos.putNextEntry(new JarEntry("at/A.class")); jos.write(a); jos.closeEntry();
            jos.putNextEntry(new JarEntry("at/B.class")); jos.write(b); jos.closeEntry();
        }
        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("at");
        cfg.flattenPackages = false; cfg.renameClasses = false; cfg.renameMethods = false;
        cfg.encryptStrings = false; cfg.obfuscateNumbers = false; cfg.obfuscateFlow = false;
        cfg.injectJunk = false; cfg.stripMetadata = false;
        cfg.antiTamper = true; cfg.seed = 55L;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        // untouched -> runs fine (proves compile-time CRC == runtime CRC)
        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            assertNotNull(cl.loadClass("crazy.IT"), "verifier class must be injected");
            assertEquals(42, (int) (Integer) cl.loadClass("at.A").getMethod("run").invoke(null),
                "unmodified anti-tampered jar must run");
        }

        // tamper at/B (add a synthetic field -> valid class, different bytes) and repackage
        Path tampered = tmp.resolve("tampered.jar");
        try (var jf = new java.util.jar.JarFile(outJar.toFile());
             var os = Files.newOutputStream(tampered);
             JarOutputStream jos = new JarOutputStream(os)) {
            var it = jf.entries();
            while (it.hasMoreElements()) {
                var je = it.nextElement();
                byte[] data; try (var is = jf.getInputStream(je)) { data = is.readAllBytes(); }
                if ("at/B.class".equals(je.getName())) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(data).accept(cn, 0);
                    cn.fields.add(new org.objectweb.asm.tree.FieldNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "TAMPER", "I", null, null));
                    ClassWriter w = new ClassWriter(0);
                    cn.accept(w); data = w.toByteArray();
                }
                jos.putNextEntry(new JarEntry(je.getName())); jos.write(data); jos.closeEntry();
            }
        }
        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{tampered.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            Class<?> at = cl.loadClass("at.A");
            var ex = assertThrows(Throwable.class,
                () -> at.getMethod("run").invoke(null),
                "tampered class must be detected at runtime");
            // ExceptionInInitializerError (from <clinit>) or its Error cause
            String chain = ex + " / " + (ex.getCause() == null ? "" : ex.getCause());
            assertTrue(chain.contains("ExceptionInInitializerError") || chain.contains("Error"),
                "tamper detection should surface an Error, got: " + chain);
        }
    }

    /**
     * The whole point of "crazy end level": every runtime-affecting pass on at
     * once (string enc + condy + numbers + flow-2 + flatten + anti-decompile +
     * invokedynamic ref-hiding + byte injection + junk + strip + watermark) and
     * the class must still verify, load and return the exact same values.
     *
     * Renaming/package-flattening are turned back off only so the test can locate
     * and invoke the class by its original names — they are covered separately.
     */
    @Test
    void crazyModeEverythingOnStillRunsCorrectly(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "crazy_e2e_x/Foo", null, "java/lang/Object", null);
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0);

        // static String greet(){ return "https://api.example.com/v1/secret"; }
        final String SECRET = "CRAZY_SECRET_TOKEN_9f8e7d6c";
        var greet = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "greet", "()Ljava/lang/String;", null, null);
        greet.visitLdcInsn(SECRET);
        greet.visitInsn(Opcodes.ARETURN); greet.visitMaxs(0, 0);

        // static int mul(int x,int y){ return x*y; }   (public -> indy can bind it)
        var mul = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mul", "(II)I", null, null);
        mul.visitVarInsn(Opcodes.ILOAD, 0);
        mul.visitVarInsn(Opcodes.ILOAD, 1);
        mul.visitInsn(Opcodes.IMUL);
        mul.visitInsn(Opcodes.IRETURN); mul.visitMaxs(0, 0);

        // static int compute(int a,int b){ int s=a+b; s=mul(s,3); if(s>100) return s-100; return s; }
        var compute = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "compute", "(II)I", null, null);
        org.objectweb.asm.Label small = new org.objectweb.asm.Label();
        compute.visitVarInsn(Opcodes.ILOAD, 0);
        compute.visitVarInsn(Opcodes.ILOAD, 1);
        compute.visitInsn(Opcodes.IADD);
        compute.visitVarInsn(Opcodes.ISTORE, 2);            // s = a+b
        compute.visitVarInsn(Opcodes.ILOAD, 2);
        compute.visitInsn(Opcodes.ICONST_3);
        compute.visitMethodInsn(Opcodes.INVOKESTATIC, "crazy_e2e_x/Foo", "mul", "(II)I", false);
        compute.visitVarInsn(Opcodes.ISTORE, 2);            // s = mul(s,3)
        compute.visitVarInsn(Opcodes.ILOAD, 2);
        compute.visitIntInsn(Opcodes.BIPUSH, 100);
        compute.visitJumpInsn(Opcodes.IF_ICMPLE, small);    // s <= 100 -> small
        compute.visitVarInsn(Opcodes.ILOAD, 2);
        compute.visitIntInsn(Opcodes.BIPUSH, 100);
        compute.visitInsn(Opcodes.ISUB);
        compute.visitInsn(Opcodes.IRETURN);                 // return s-100
        compute.visitLabel(small);
        compute.visitVarInsn(Opcodes.ILOAD, 2);
        compute.visitInsn(Opcodes.IRETURN);                 // return s
        compute.visitMaxs(0, 0);
        cw.visitEnd();

        Path inJar = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(inJar); JarOutputStream jos = new JarOutputStream(os, new Manifest())) {
            jos.putNextEntry(new JarEntry("crazy_e2e_x/Foo.class"));
            jos.write(cw.toByteArray());
            jos.closeEntry();
        }

        Path outJar = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = java.util.List.of("crazy_e2e_x");
        cfg.seed = 20260913L;
        cfg.applyCrazyPreset();                 // <-- the whole aggressive stack
        // keep names/packages so the test can find the class; rename is covered elsewhere
        cfg.renameClasses = cfg.renameMethods = cfg.renameFields = false;
        cfg.flattenPackages = false;
        CrazyObfuscator.run(inJar, outJar, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        // sanity: injected bootstraps present, plaintext secret gone
        byte[] cls;
        try (var jf = new java.util.jar.JarFile(outJar.toFile());
             var is = jf.getInputStream(jf.getEntry("crazy_e2e_x/Foo.class"))) {
            cls = is.readAllBytes();
        }
        String raw = new String(cls, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(SECRET), "crazy mode: string literal must not survive in plaintext");

        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{outJar.toUri().toURL()},
                                                  EndToEndTest.class.getClassLoader())) {
            Class<?> foo = cl.loadClass("crazy_e2e_x.Foo");
            assertEquals(SECRET, foo.getMethod("greet").invoke(null),
                "crazy mode: encrypted+condy string must still decode at runtime");
            var mulM = foo.getMethod("mul", int.class, int.class);
            assertEquals(42, (int) (Integer) mulM.invoke(null, 6, 7), "mul(6,7)=42 through the whole stack");
            var computeM = foo.getMethod("compute", int.class, int.class);
            assertEquals(15, (int) (Integer) computeM.invoke(null, 2, 3),   "compute(2,3): mul(5,3)=15, <=100 -> 15");
            assertEquals(50, (int) (Integer) computeM.invoke(null, 40, 10), "compute(40,10): mul(50,3)=150, >100 -> 50");
        }
    }
}
