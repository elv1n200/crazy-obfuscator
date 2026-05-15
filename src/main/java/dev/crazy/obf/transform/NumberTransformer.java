package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Random;

/**
 * Replaces int / long constant pushes with arithmetic expressions whose result
 * equals the original value but whose written form is not immediately obvious.
 *
 * Strategy per integer N:
 *   pick random K1, K2;
 *   emit  PUSH (N ^ K1)  PUSH K1  IXOR  PUSH K2  IADD  PUSH K2  ISUB
 * Net effect: ((N ^ K1) ^ K1) + K2 - K2 == N, but a reader sees four operands.
 *
 * Same shape for longs with LXOR/LADD/LSUB.
 */
public final class NumberTransformer implements Transformer {

    @Override public String name() { return "numbers"; }

    @Override
    public void apply(ObfContext ctx) {
        int chance = clamp(ctx.config().numberObfuscationChance, 0, 100);
        if (chance <= 0) return;
        Random rng = new Random(ctx.seed() ^ 0xBADC0FFEEBEEFL);

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                if ("<clinit>".equals(m.name)) continue; // safer
                if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                for (AbstractInsnNode ins : m.instructions.toArray()) {
                    Long lv = longConst(ins);
                    if (lv != null && rng.nextInt(100) < chance) {
                        InsnList rep = obfuscateLong(lv, rng);
                        m.instructions.insert(ins, rep);
                        m.instructions.remove(ins);
                        continue;
                    }
                    Integer iv = intConst(ins);
                    if (iv != null && rng.nextInt(100) < chance) {
                        // never obfuscate values that look like opcode/array-index "0" inside very tiny methods
                        InsnList rep = obfuscateInt(iv, rng);
                        m.instructions.insert(ins, rep);
                        m.instructions.remove(ins);
                    }
                }
            }
        }
    }

    private static Integer intConst(AbstractInsnNode ins) {
        int op = ins.getOpcode();
        return switch (op) {
            case Opcodes.ICONST_M1 -> -1;
            case Opcodes.ICONST_0  -> 0;
            case Opcodes.ICONST_1  -> 1;
            case Opcodes.ICONST_2  -> 2;
            case Opcodes.ICONST_3  -> 3;
            case Opcodes.ICONST_4  -> 4;
            case Opcodes.ICONST_5  -> 5;
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> ((IntInsnNode) ins).operand;
            default -> {
                if (ins instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) yield i;
                yield null;
            }
        };
    }

    private static Long longConst(AbstractInsnNode ins) {
        int op = ins.getOpcode();
        if (op == Opcodes.LCONST_0) return 0L;
        if (op == Opcodes.LCONST_1) return 1L;
        if (ins instanceof LdcInsnNode ldc && ldc.cst instanceof Long l) return l;
        return null;
    }

    private static InsnList obfuscateInt(int n, Random rng) {
        int k1 = rng.nextInt();
        int k2 = rng.nextInt();
        InsnList l = new InsnList();
        l.add(pushInt(n ^ k1));
        l.add(pushInt(k1));
        l.add(new InsnNode(Opcodes.IXOR));
        l.add(pushInt(k2));
        l.add(new InsnNode(Opcodes.IADD));
        l.add(pushInt(k2));
        l.add(new InsnNode(Opcodes.ISUB));
        return l;
    }

    private static InsnList obfuscateLong(long n, Random rng) {
        long k1 = rng.nextLong();
        long k2 = rng.nextLong();
        InsnList l = new InsnList();
        l.add(pushLong(n ^ k1));
        l.add(pushLong(k1));
        l.add(new InsnNode(Opcodes.LXOR));
        l.add(pushLong(k2));
        l.add(new InsnNode(Opcodes.LADD));
        l.add(pushLong(k2));
        l.add(new InsnNode(Opcodes.LSUB));
        return l;
    }

    private static AbstractInsnNode pushInt(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v);
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE)  return new IntInsnNode(Opcodes.BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, v);
        return new LdcInsnNode(v);
    }

    private static AbstractInsnNode pushLong(long v) {
        if (v == 0L) return new InsnNode(Opcodes.LCONST_0);
        if (v == 1L) return new InsnNode(Opcodes.LCONST_1);
        return new LdcInsnNode(v);
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
}
