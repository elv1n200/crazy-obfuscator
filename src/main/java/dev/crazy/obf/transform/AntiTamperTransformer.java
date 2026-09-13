package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Runtime self-integrity check (anti-tamper).
 *
 * <p>Computes the CRC32 of every protected class's <em>final</em> bytecode and
 * bakes the expected values into an injected verifier {@code crazy/IT}. A call to
 * {@code crazy/IT.v()} is planted at the top of each protected class's
 * {@code <clinit>}, so the first time any of your code initialises, the verifier
 * re-reads each class from its own class loader, re-computes the CRC, and throws
 * if a byte was changed — defeating trivial "edit one instruction in the jar"
 * patches (license checks, feature gates, …).
 *
 * <p>No circularity: {@code crazy/IT} holds the CRCs and is itself never checked,
 * and every CRC is taken after all guards are inserted, so this must run <b>last</b>
 * (after byte injection). The verifier is idempotent and skips silently if a
 * class resource can't be read, so it never false-positives on an unusual class
 * loader — but that also means it is <b>not</b> a security boundary, and it is
 * incompatible with load-time bytecode transformers that change class bytes
 * (Java agents; note Fabric/Mixin transform at define time, not via the class
 * <em>resource</em>, which this reads). Opt-in, and deliberately NOT part of the
 * {@code --crazy} preset. Enabled by {@link dev.crazy.obf.config.ObfConfig#antiTamper}.
 */
public final class AntiTamperTransformer implements Transformer {

    public static final String IT = "crazy/IT";

    private final PrintStream log;

    public AntiTamperTransformer(PrintStream log) { this.log = log == null ? System.out : log; }
    public AntiTamperTransformer() { this(System.out); }

    @Override public String name() { return "antitamper"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().antiTamper) return;

        // Protected set: our touchable, non-helper classes.
        List<ClassNode> protectedClasses = new ArrayList<>();
        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.name.startsWith("crazy/")) continue;
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0) continue;   // interfaces: <clinit> only for constants
            protectedClasses.add(cn);
        }
        if (protectedClasses.isEmpty()) {
            log.println("[crazy] antitamper: no eligible classes, skipped");
            return;
        }

        // 1. plant the verifier call in each protected class's <clinit>.
        for (ClassNode cn : protectedClasses) injectClinitCall(cn);

        // 2. now that every class is final, CRC each one (exact JarWriter bytes).
        Map<String, Integer> crcs = new LinkedHashMap<>();
        for (ClassNode cn : protectedClasses) {
            crcs.put(cn.name + ".class", crc32(cn, ctx));
        }

        // 3. inject crazy/IT holding those CRCs.
        injectVerifier(ctx, crcs);
        log.println("[crazy] antitamper: protecting " + crcs.size() + " class(es) via crazy/IT");
    }

    /** Prepend {@code crazy/IT.v()} to the class's <clinit> (created if absent). */
    private void injectClinitCall(ClassNode cn) {
        MethodNode clinit = null;
        if (cn.methods != null) {
            for (MethodNode m : cn.methods) if ("<clinit>".equals(m.name) && "()V".equals(m.desc)) { clinit = m; break; }
        }
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            if (cn.methods == null) cn.methods = new ArrayList<>();
            cn.methods.add(clinit);
        }
        InsnList call = new InsnList();
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, IT, "v", "()V", false));
        clinit.instructions.insert(call);
    }

    /** Serialise a ClassNode to bytes the same way {@link dev.crazy.obf.io.JarWriter} will. */
    private static int crc32(ClassNode cn, ObfContext ctx) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String t1, String t2) {
                try { return super.getCommonSuperClass(t1, t2); }
                catch (Throwable t) { return ctx.commonSuperClass(t1, t2); }
            }
        };
        cn.accept(cw);
        CRC32 crc = new CRC32();
        crc.update(cw.toByteArray());
        return (int) crc.getValue();
    }

    /**
     * Injects:
     *   public final class crazy.IT {
     *     private static boolean done;
     *     static void c(String name, int expect) throws Throwable {
     *       InputStream in = IT.class.getClassLoader().getResourceAsStream(name);
     *       if (in == null) return;               // unusual loader -> don't false-positive
     *       byte[] b = in.readAllBytes(); in.close();
     *       CRC32 crc = new CRC32(); crc.update(b);
     *       if ((int) crc.getValue() != expect) throw new Error(name);
     *     }
     *     public static void v() {
     *       if (done) return; done = true;
     *       c("a/b.class", 123); c(...); ...
     *     }
     *   }
     */
    private void injectVerifier(ObfContext ctx, Map<String, Integer> crcs) {
        if (ctx.contents().classes().containsKey(IT)) return;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            IT, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "done", "Z", null, null).visitEnd();

        var ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);

        // static void c(String name, int expect) throws Throwable
        var c = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "c",
            "(Ljava/lang/String;I)V", null, new String[]{"java/lang/Throwable"});
        // InputStream in = IT.class.getClassLoader().getResourceAsStream(name)
        c.visitLdcInsn(org.objectweb.asm.Type.getObjectType(IT));
        c.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "getResourceAsStream",
            "(Ljava/lang/String;)Ljava/io/InputStream;", false);
        c.visitVarInsn(Opcodes.ASTORE, 2);                       // in
        Label notNull = new Label();
        c.visitVarInsn(Opcodes.ALOAD, 2);
        c.visitJumpInsn(Opcodes.IFNONNULL, notNull);
        c.visitInsn(Opcodes.RETURN);                             // null -> skip
        c.visitLabel(notNull);
        // byte[] b = in.readAllBytes()
        c.visitVarInsn(Opcodes.ALOAD, 2);
        c.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "readAllBytes", "()[B", false);
        c.visitVarInsn(Opcodes.ASTORE, 3);                       // b
        c.visitVarInsn(Opcodes.ALOAD, 2);
        c.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "close", "()V", false);
        // CRC32 crc = new CRC32(); crc.update(b)
        c.visitTypeInsn(Opcodes.NEW, "java/util/zip/CRC32");
        c.visitInsn(Opcodes.DUP);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/zip/CRC32", "<init>", "()V", false);
        c.visitVarInsn(Opcodes.ASTORE, 4);                       // crc
        c.visitVarInsn(Opcodes.ALOAD, 4);
        c.visitVarInsn(Opcodes.ALOAD, 3);
        c.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/zip/CRC32", "update", "([B)V", false);
        // if ((int) crc.getValue() != expect) throw new Error(name)
        c.visitVarInsn(Opcodes.ALOAD, 4);
        c.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/zip/CRC32", "getValue", "()J", false);
        c.visitInsn(Opcodes.L2I);
        c.visitVarInsn(Opcodes.ILOAD, 1);
        Label ok = new Label();
        c.visitJumpInsn(Opcodes.IF_ICMPEQ, ok);
        c.visitTypeInsn(Opcodes.NEW, "java/lang/Error");
        c.visitInsn(Opcodes.DUP);
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Error", "<init>", "(Ljava/lang/String;)V", false);
        c.visitInsn(Opcodes.ATHROW);
        c.visitLabel(ok);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(0, 0);

        // public static void v()
        var v = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "v", "()V",
            null, new String[]{"java/lang/Throwable"});
        Label go = new Label();
        v.visitFieldInsn(Opcodes.GETSTATIC, IT, "done", "Z");
        v.visitJumpInsn(Opcodes.IFEQ, go);
        v.visitInsn(Opcodes.RETURN);
        v.visitLabel(go);
        v.visitInsn(Opcodes.ICONST_1);
        v.visitFieldInsn(Opcodes.PUTSTATIC, IT, "done", "Z");
        for (Map.Entry<String, Integer> e : crcs.entrySet()) {
            v.visitLdcInsn(e.getKey());
            v.visitLdcInsn(e.getValue());
            v.visitMethodInsn(Opcodes.INVOKESTATIC, IT, "c", "(Ljava/lang/String;I)V", false);
        }
        v.visitInsn(Opcodes.RETURN);
        v.visitMaxs(0, 0);

        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        ctx.contents().classes().put(cn.name, cn);
    }
}
