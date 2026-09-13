package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.PrintStream;
import java.util.Random;

/**
 * Hides {@code int}/{@code long} constants behind {@code CONSTANT_Dynamic}.
 *
 * <p>An {@code ldc 0xDEADBEEF} becomes an {@code ldc} of a dynamic constant whose
 * injected bootstrap ({@code crazy/NC}) XORs an encrypted value with a per-site
 * key at link time and returns the real number. A decompiler / {@code javap -c}
 * then shows only an opaque dynamic constant — the magic number (a key, a
 * threshold, an opcode id, …) is gone from the constant pool as a plain literal.
 *
 * <p>Composes with {@link NumberTransformer} and {@link MbaTransformer}: those
 * turn a constant into arithmetic, and this then hides the surviving literals.
 * Only real {@code LDC} int/long constants are touched (small
 * {@code ICONST/BIPUSH/SIPUSH} pushes are left alone to avoid bloat). Requires
 * class-file version &ge; 55 (Java 11); older classes are skipped. Resolved once
 * at link time, so steady-state cost is nil. Enabled by
 * {@link dev.crazy.obf.config.ObfConfig#hideNumbersCondy}.
 */
public final class NumberCondyTransformer implements Transformer {

    public static final String NC = "crazy/NC";
    private static final String BSM_I =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;II)I";
    private static final String BSM_J =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;JJ)J";

    private final PrintStream log;
    private int rewrites;

    public NumberCondyTransformer(PrintStream log) { this.log = log == null ? System.out : log; }
    public NumberCondyTransformer() { this(System.out); }

    @Override public String name() { return "numcondy"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().hideNumbersCondy) return;
        Random rng = new Random(ctx.seed() ^ 0x2A2A9C0DE5L);
        Handle bsmI = new Handle(Opcodes.H_INVOKESTATIC, NC, "ni", BSM_I, false);
        Handle bsmJ = new Handle(Opcodes.H_INVOKESTATIC, NC, "nl", BSM_J, false);
        boolean any = false;

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.name.startsWith("crazy/")) continue;
            if ((cn.version & 0xFFFF) < Opcodes.V11) continue;   // condy needs v55+
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                for (AbstractInsnNode ins : m.instructions.toArray()) {
                    if (!(ins instanceof LdcInsnNode ldc)) continue;
                    if (ldc.cst instanceof Integer iv) {
                        int key = rng.nextInt();
                        var cd = new ConstantDynamic("i", "I", bsmI, iv ^ key, key);
                        m.instructions.set(ldc, new LdcInsnNode(cd));
                        rewrites++; any = true;
                    } else if (ldc.cst instanceof Long lv) {
                        long key = rng.nextLong();
                        var cd = new ConstantDynamic("l", "J", bsmJ, lv ^ key, key);
                        m.instructions.set(ldc, new LdcInsnNode(cd));
                        rewrites++; any = true;
                    }
                }
            }
        }

        if (any) injectBootstrap(ctx);
        log.println("[crazy] numcondy: hid " + rewrites + " numeric constant(s) behind condy");
    }

    /**
     * Injects:
     *   public final class crazy.NC {
     *     public static int  ni(Lookup l, String n, Class c, int  enc, int  key){ return enc ^ key; }
     *     public static long nl(Lookup l, String n, Class c, long enc, long key){ return enc ^ key; }
     *   }
     */
    private void injectBootstrap(ObfContext ctx) {
        if (ctx.contents().classes().containsKey(NC)) return;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            NC, null, "java/lang/Object", null);

        var ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);

        // int ni(...): enc ^ key   (enc = local 3, key = local 4)
        var ni = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ni", BSM_I, null, null);
        ni.visitVarInsn(Opcodes.ILOAD, 3);
        ni.visitVarInsn(Opcodes.ILOAD, 4);
        ni.visitInsn(Opcodes.IXOR);
        ni.visitInsn(Opcodes.IRETURN);
        ni.visitMaxs(0, 0);

        // long nl(...): enc ^ key   (enc = local 3 [long], key = local 5 [long])
        var nl = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "nl", BSM_J, null, null);
        nl.visitVarInsn(Opcodes.LLOAD, 3);
        nl.visitVarInsn(Opcodes.LLOAD, 5);
        nl.visitInsn(Opcodes.LXOR);
        nl.visitInsn(Opcodes.LRETURN);
        nl.visitMaxs(0, 0);

        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        ctx.contents().classes().put(cn.name, cn);
    }
}
