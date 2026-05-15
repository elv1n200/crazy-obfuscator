package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Polymorphic string-literal encryption.
 *
 * Every encrypted class gets its OWN decoder method whose:
 *   - name is randomized (3-char suffix on CRAZY$d)
 *   - mixing constants (mult, add) are randomized
 *   - decryption formula is one of three equivalent shapes
 *
 * Encryption is symmetric XOR:
 *   c[i] ^= ((salt * mult + i * add) & 0x7FFF)
 *
 * The decoder ALSO XORs with the same expression — so encrypt is its own inverse.
 *
 * Why polymorphism matters: a deobfuscator that knows the ZKM/Allatori pattern
 * looks for a single CRAZY$d shape jar-wide. With per-class constants and shapes,
 * a generic pattern doesn't match — they have to deal with each class.
 */
public final class StringEncryptionTransformer implements Transformer {

    private static final String DECODER_PREFIX = "CRAZY$d";
    private static final String DECODER_DESC   = "(Ljava/lang/String;I)Ljava/lang/String;";

    /** Per-class decoder spec. */
    private static final class Spec {
        final String methodName;
        final int mult;
        final int add;
        final int shape; // 0,1,2: three equivalent decoder bodies
        Spec(String n, int m, int a, int s) { methodName = n; mult = m; add = a; shape = s; }
    }

    private final Map<String, Spec> specs = new HashMap<>();

    @Override public String name() { return "strings"; }

    @Override
    public void apply(ObfContext ctx) {
        int chance = clamp(ctx.config().stringEncryptionChance, 0, 100);
        if (chance <= 0) return;
        Random rng = new Random(ctx.seed() ^ 0xDECAFC0FFEEL);

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.methods == null || cn.methods.isEmpty()) continue;
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0) continue;

            boolean touched = false;
            Spec spec = null;

            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                if ((m.access & Opcodes.ACC_ABSTRACT) != 0) continue;
                if ((m.access & Opcodes.ACC_NATIVE) != 0) continue;

                for (AbstractInsnNode ins : m.instructions.toArray()) {
                    if (!(ins instanceof LdcInsnNode ldc)) continue;
                    if (!(ldc.cst instanceof String s)) continue;
                    if (s.isEmpty()) continue;
                    if (ctx.exclusions().shouldPreserveString(s)) continue;
                    if (looksLikeReference(s)) continue;
                    if (rng.nextInt(100) >= chance) continue;

                    if (spec == null) spec = mintSpec(cn.name, rng);

                    int salt = rng.nextInt(0x7FFE) + 1;
                    String enc = transform(s, salt, spec.mult, spec.add);

                    InsnList replacement = new InsnList();
                    replacement.add(new LdcInsnNode(enc));
                    replacement.add(intConst(salt));
                    replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, spec.methodName, DECODER_DESC, false));
                    m.instructions.insert(ins, replacement);
                    m.instructions.remove(ins);
                    touched = true;
                }
            }

            if (touched && spec != null) {
                injectDecoder(cn, spec);
                specs.put(cn.name, spec);
            }
        }
    }

    private Spec mintSpec(String classKey, Random rng) {
        // odd multipliers only so the (salt*mult+i*add) function is more diverse
        int mult = (rng.nextInt(127) | 1) + 2;  // 3..255 odd
        int add  = (rng.nextInt(127) | 1) + 2;
        int shape = rng.nextInt(3);
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 3; i++) suffix.append((char)('a' + rng.nextInt(26)));
        return new Spec(DECODER_PREFIX + suffix, mult, add, shape);
    }

    private void injectDecoder(ClassNode cn, Spec spec) {
        if (cn.methods != null) {
            for (MethodNode m : cn.methods) {
                if (spec.methodName.equals(m.name) && DECODER_DESC.equals(m.desc)) return;
            }
        }
        MethodNode mn = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            spec.methodName, DECODER_DESC, null, null);

        // Three equivalent shapes — same arithmetic, different opcode sequence.
        // All produce: c[i] = (char)(c[i] ^ ((salt*mult + i*add) & 0x7FFF))
        switch (spec.shape) {
            case 0 -> emitShapeMulAdd(mn, spec);
            case 1 -> emitShapeAddMul(mn, spec);
            default -> emitShapeRolling(mn, spec);
        }

        if (cn.methods == null) cn.methods = new java.util.ArrayList<>();
        cn.methods.add(mn);
    }

    /** Shape 0: c[i] ^= ((salt*mult + i*add) & 0x7FFF) */
    private void emitShapeMulAdd(MethodNode mn, Spec spec) {
        // s -> chars in slot 2
        mn.visitVarInsn(Opcodes.ALOAD, 0);
        mn.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
        mn.visitVarInsn(Opcodes.ASTORE, 2);

        mn.visitInsn(Opcodes.ICONST_0);
        mn.visitVarInsn(Opcodes.ISTORE, 3);

        Label loop = new Label(), end = new Label();
        mn.visitLabel(loop);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitInsn(Opcodes.ARRAYLENGTH);
        mn.visitJumpInsn(Opcodes.IF_ICMPGE, end);

        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitVarInsn(Opcodes.ILOAD, 3);

        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        mn.visitInsn(Opcodes.CALOAD);

        // salt*mult + i*add
        mn.visitVarInsn(Opcodes.ILOAD, 1);
        pushIntConst(mn, spec.mult);
        mn.visitInsn(Opcodes.IMUL);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        pushIntConst(mn, spec.add);
        mn.visitInsn(Opcodes.IMUL);
        mn.visitInsn(Opcodes.IADD);
        mn.visitLdcInsn(0x7FFF);
        mn.visitInsn(Opcodes.IAND);

        mn.visitInsn(Opcodes.IXOR);
        mn.visitInsn(Opcodes.I2C);
        mn.visitInsn(Opcodes.CASTORE);

        mn.visitIincInsn(3, 1);
        mn.visitJumpInsn(Opcodes.GOTO, loop);

        mn.visitLabel(end);
        emitReturnNewString(mn);
    }

    /** Shape 1: tmp = i*add; tmp += salt*mult; mask&XOR */
    private void emitShapeAddMul(MethodNode mn, Spec spec) {
        mn.visitVarInsn(Opcodes.ALOAD, 0);
        mn.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
        mn.visitVarInsn(Opcodes.ASTORE, 2);

        mn.visitInsn(Opcodes.ICONST_0);
        mn.visitVarInsn(Opcodes.ISTORE, 3);

        Label loop = new Label(), end = new Label();
        mn.visitLabel(loop);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitInsn(Opcodes.ARRAYLENGTH);
        mn.visitJumpInsn(Opcodes.IF_ICMPGE, end);

        // tmp = i*add + salt*mult
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        pushIntConst(mn, spec.add);
        mn.visitInsn(Opcodes.IMUL);
        mn.visitVarInsn(Opcodes.ILOAD, 1);
        pushIntConst(mn, spec.mult);
        mn.visitInsn(Opcodes.IMUL);
        mn.visitInsn(Opcodes.IADD);
        mn.visitLdcInsn(0x7FFF);
        mn.visitInsn(Opcodes.IAND);
        mn.visitVarInsn(Opcodes.ISTORE, 4);

        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        mn.visitInsn(Opcodes.CALOAD);
        mn.visitVarInsn(Opcodes.ILOAD, 4);
        mn.visitInsn(Opcodes.IXOR);
        mn.visitInsn(Opcodes.I2C);
        mn.visitInsn(Opcodes.CASTORE);

        mn.visitIincInsn(3, 1);
        mn.visitJumpInsn(Opcodes.GOTO, loop);

        mn.visitLabel(end);
        emitReturnNewString(mn);
    }

    /** Shape 2: rolling — k starts at salt*mult, advances by `add` per char. Mathematically the same. */
    private void emitShapeRolling(MethodNode mn, Spec spec) {
        mn.visitVarInsn(Opcodes.ALOAD, 0);
        mn.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
        mn.visitVarInsn(Opcodes.ASTORE, 2);

        // int k = salt * mult
        mn.visitVarInsn(Opcodes.ILOAD, 1);
        pushIntConst(mn, spec.mult);
        mn.visitInsn(Opcodes.IMUL);
        mn.visitVarInsn(Opcodes.ISTORE, 4);

        mn.visitInsn(Opcodes.ICONST_0);
        mn.visitVarInsn(Opcodes.ISTORE, 3);

        Label loop = new Label(), end = new Label();
        mn.visitLabel(loop);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitInsn(Opcodes.ARRAYLENGTH);
        mn.visitJumpInsn(Opcodes.IF_ICMPGE, end);

        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        mn.visitInsn(Opcodes.CALOAD);
        mn.visitVarInsn(Opcodes.ILOAD, 4);
        mn.visitLdcInsn(0x7FFF);
        mn.visitInsn(Opcodes.IAND);
        mn.visitInsn(Opcodes.IXOR);
        mn.visitInsn(Opcodes.I2C);
        mn.visitInsn(Opcodes.CASTORE);

        // k += add
        mn.visitVarInsn(Opcodes.ILOAD, 4);
        pushIntConst(mn, spec.add);
        mn.visitInsn(Opcodes.IADD);
        mn.visitVarInsn(Opcodes.ISTORE, 4);

        mn.visitIincInsn(3, 1);
        mn.visitJumpInsn(Opcodes.GOTO, loop);

        mn.visitLabel(end);
        emitReturnNewString(mn);
    }

    private void emitReturnNewString(MethodNode mn) {
        mn.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        mn.visitInsn(Opcodes.DUP);
        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
        mn.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "intern", "()Ljava/lang/String;", false);
        mn.visitInsn(Opcodes.ARETURN);
        mn.visitMaxs(0, 0);
    }

    private static void pushIntConst(MethodNode mn, int v) {
        if (v >= -1 && v <= 5) mn.visitInsn(Opcodes.ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mn.visitIntInsn(Opcodes.BIPUSH, v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mn.visitIntInsn(Opcodes.SIPUSH, v);
        else mn.visitLdcInsn(v);
    }

    /** Symmetric transform — XOR with the same key sequence used by the decoder. */
    private static String transform(String s, int salt, int mult, int add) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) {
            int k = (salt * mult + i * add) & 0x7FFF;
            c[i] = (char) (c[i] ^ k);
        }
        return new String(c);
    }

    private static AbstractInsnNode intConst(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v);
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE)  return new IntInsnNode(Opcodes.BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, v);
        return new LdcInsnNode(v);
    }

    private static boolean looksLikeReference(String s) {
        if (s.length() > 4096) return true;
        if (s.startsWith("META-INF/")) return true;
        if (s.endsWith(".class") || s.endsWith(".json") || s.endsWith(".png") || s.endsWith(".ogg") || s.endsWith(".mcmeta")) return true;
        if (s.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) return true;
        // Kotlin callable-reference signature strings (baked into bytecode by
        // `X::prop` / `X::fun`). These must stay PLAIN so the name pass can
        // remap the class names inside them to match the renamed metadata.
        // Form: "getSelectedTheme()Lcop/module/...;" or "invoke(Lcop/X;)V".
        if (isMemberSignature(s)) return true;
        return false;
    }

    /** True if the string looks like a JVM member signature (has (...) and an object type). */
    static boolean isMemberSignature(String s) {
        int lp = s.indexOf('('), rp = s.indexOf(')');
        if (lp < 0 || rp < lp) return false;
        // must contain at least one object type reference with a package path
        int l = s.indexOf('L');
        return l >= 0 && s.indexOf(';', l) > l && s.indexOf('/') >= 0;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
}
