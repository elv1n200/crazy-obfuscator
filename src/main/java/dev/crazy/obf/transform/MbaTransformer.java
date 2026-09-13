package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.PrintStream;
import java.util.Random;

/**
 * Mixed Boolean-Arithmetic (MBA) obfuscation of integer <em>and long</em>
 * operations.
 *
 * <p>Where {@link NumberTransformer} hides constants, this hides the arithmetic
 * itself: each eligible {@code int}/{@code long} {@code + - ^ | &amp;} op is
 * replaced with an algebraically-equivalent bit/arith identity that computes the
 * exact same value (bit-for-bit under two's-complement wraparound). The pass is
 * <b>polymorphic</b> — every op picks at random among several equivalent
 * identities, so a de-MBA tool can't match one fixed pattern jar-wide:
 *
 * <pre>
 *   a + b  ->  (a^b)+((a&amp;b)&lt;&lt;1)   |  (a|b)+(a&amp;b)     |  2*(a|b)-(a^b)
 *   a - b  ->  (a^b)-((~a&amp;b)&lt;&lt;1)  |  (a^~b)+((a&amp;~b)&lt;&lt;1)+1
 *   a ^ b  ->  (a|b)-(a&amp;b)        |  (a&amp;~b)|(~a&amp;b)
 *   a | b  ->  (a&amp;b)+(a^b)        |  ~(~a&amp;~b)
 *   a &amp; b  ->  ~(~a|~b)           |  a-(a&amp;~b)
 * </pre>
 *
 * <p>Soundness: operands are spilled into fresh local slots (above the method's
 * current {@code maxLocals}, so they never alias a live local), and every
 * replacement is a straight-line sequence of non-throwing instructions — no new
 * control flow, no exception edge, identical result. Safe even inside a
 * {@code try}. Enabled by {@link dev.crazy.obf.config.ObfConfig#mbaArithmetic}.
 */
public final class MbaTransformer implements Transformer {

    private final PrintStream log;
    private int rewrites;

    public MbaTransformer(PrintStream log) { this.log = log == null ? System.out : log; }
    public MbaTransformer() { this(System.out); }

    @Override public String name() { return "mba"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().mbaArithmetic) return;
        int chance = clamp(ctx.config().mbaChance, 0, 100);
        if (chance <= 0) return;
        Random rng = new Random(ctx.seed() ^ 0x3BA0BFA5CA1AB1EL);

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.name.startsWith("crazy/")) continue;
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null || m.instructions.size() == 0) continue;
                if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                // Scratch slots above everything in use: two int slots (b0,b0+1)
                // reused as two long slots (b0,b0+2) — each op sequence is
                // self-contained so the overlap in time never conflicts.
                int b0 = m.maxLocals;
                boolean used = false;

                for (AbstractInsnNode ins : m.instructions.toArray()) {
                    if (ins.getType() != AbstractInsnNode.INSN) continue;
                    int op = ins.getOpcode();
                    boolean isInt  = op == Opcodes.IADD || op == Opcodes.ISUB || op == Opcodes.IXOR
                                  || op == Opcodes.IOR  || op == Opcodes.IAND;
                    boolean isLong = op == Opcodes.LADD || op == Opcodes.LSUB || op == Opcodes.LXOR
                                  || op == Opcodes.LOR  || op == Opcodes.LAND;
                    if (!isInt && !isLong) continue;
                    if (rng.nextInt(100) >= chance) continue;

                    InsnList rep = isInt ? intMba(op, b0, b0 + 1, rng)
                                         : longMba(op, b0, b0 + 2, rng);
                    m.instructions.insert(ins, rep);
                    m.instructions.remove(ins);
                    rewrites++;
                    used = true;
                }
                if (used) m.maxLocals = Math.max(m.maxLocals, b0 + 4);
            }
        }
        log.println("[crazy] mba: rewrote " + rewrites + " int/long op(s)");
    }

    // ===================== int =====================

    private static InsnList intMba(int op, int a, int b, Random rng) {
        InsnList l = new InsnList();
        l.add(new VarInsnNode(Opcodes.ISTORE, b));   // pop b
        l.add(new VarInsnNode(Opcodes.ISTORE, a));   // pop a
        switch (op) {
            case Opcodes.IADD -> {
                switch (rng.nextInt(3)) {
                    case 0 -> { iXor(l,a,b); iAnd(l,a,b); i1shl(l); l.add(new InsnNode(Opcodes.IADD)); }      // (a^b)+((a&b)<<1)
                    case 1 -> { iOr(l,a,b); iAnd(l,a,b); l.add(new InsnNode(Opcodes.IADD)); }                  // (a|b)+(a&b)
                    default -> { iOr(l,a,b); l.add(new InsnNode(Opcodes.ICONST_1)); l.add(new InsnNode(Opcodes.ISHL));
                                 iXor(l,a,b); l.add(new InsnNode(Opcodes.ISUB)); }                             // 2*(a|b)-(a^b)
                }
            }
            case Opcodes.ISUB -> {
                if (rng.nextBoolean()) { iXor(l,a,b); iNot(l,a); iLoad(l,b); l.add(new InsnNode(Opcodes.IAND)); i1shl(l); l.add(new InsnNode(Opcodes.ISUB)); } // (a^b)-((~a&b)<<1)
                else { iLoad(l,a); iNot(l,b); l.add(new InsnNode(Opcodes.IXOR)); iLoad(l,a); iNot(l,b); l.add(new InsnNode(Opcodes.IAND)); i1shl(l); l.add(new InsnNode(Opcodes.IADD)); l.add(new InsnNode(Opcodes.ICONST_1)); l.add(new InsnNode(Opcodes.IADD)); } // (a^~b)+((a&~b)<<1)+1
            }
            case Opcodes.IXOR -> {
                if (rng.nextBoolean()) { iOr(l,a,b); iAnd(l,a,b); l.add(new InsnNode(Opcodes.ISUB)); }         // (a|b)-(a&b)
                else { iLoad(l,a); iNot(l,b); l.add(new InsnNode(Opcodes.IAND)); iNot(l,a); iLoad(l,b); l.add(new InsnNode(Opcodes.IAND)); l.add(new InsnNode(Opcodes.IOR)); } // (a&~b)|(~a&b)
            }
            case Opcodes.IOR -> {
                if (rng.nextBoolean()) { iAnd(l,a,b); iXor(l,a,b); l.add(new InsnNode(Opcodes.IADD)); }        // (a&b)+(a^b)
                else { iNot(l,a); iNot(l,b); l.add(new InsnNode(Opcodes.IAND)); l.add(new InsnNode(Opcodes.ICONST_M1)); l.add(new InsnNode(Opcodes.IXOR)); } // ~(~a&~b)
            }
            case Opcodes.IAND -> {
                if (rng.nextBoolean()) { iNot(l,a); iNot(l,b); l.add(new InsnNode(Opcodes.IOR)); l.add(new InsnNode(Opcodes.ICONST_M1)); l.add(new InsnNode(Opcodes.IXOR)); } // ~(~a|~b)
                else { iLoad(l,a); iLoad(l,a); iNot(l,b); l.add(new InsnNode(Opcodes.IAND)); l.add(new InsnNode(Opcodes.ISUB)); } // a-(a&~b)
            }
        }
        return l;
    }

    private static void iLoad(InsnList l, int s) { l.add(new VarInsnNode(Opcodes.ILOAD, s)); }
    private static void iNot(InsnList l, int s)  { iLoad(l, s); l.add(new InsnNode(Opcodes.ICONST_M1)); l.add(new InsnNode(Opcodes.IXOR)); }
    private static void iXor(InsnList l, int a, int b) { iLoad(l,a); iLoad(l,b); l.add(new InsnNode(Opcodes.IXOR)); }
    private static void iAnd(InsnList l, int a, int b) { iLoad(l,a); iLoad(l,b); l.add(new InsnNode(Opcodes.IAND)); }
    private static void iOr(InsnList l, int a, int b)  { iLoad(l,a); iLoad(l,b); l.add(new InsnNode(Opcodes.IOR)); }
    private static void i1shl(InsnList l) { l.add(new InsnNode(Opcodes.ICONST_1)); l.add(new InsnNode(Opcodes.ISHL)); }

    // ===================== long =====================

    private static InsnList longMba(int op, int a, int b, Random rng) {
        InsnList l = new InsnList();
        l.add(new VarInsnNode(Opcodes.LSTORE, b));   // pop b (2-wide)
        l.add(new VarInsnNode(Opcodes.LSTORE, a));   // pop a
        switch (op) {
            case Opcodes.LADD -> {
                switch (rng.nextInt(3)) {
                    case 0 -> { lXor(l,a,b); lAnd(l,a,b); l1shl(l); l.add(new InsnNode(Opcodes.LADD)); }       // (a^b)+((a&b)<<1)
                    case 1 -> { lOr(l,a,b); lAnd(l,a,b); l.add(new InsnNode(Opcodes.LADD)); }                  // (a|b)+(a&b)
                    default -> { lOr(l,a,b); l.add(new InsnNode(Opcodes.ICONST_1)); l.add(new InsnNode(Opcodes.LSHL));
                                 lXor(l,a,b); l.add(new InsnNode(Opcodes.LSUB)); }                             // 2*(a|b)-(a^b)
                }
            }
            case Opcodes.LSUB -> {
                if (rng.nextBoolean()) { lXor(l,a,b); lNot(l,a); lLoad(l,b); l.add(new InsnNode(Opcodes.LAND)); l1shl(l); l.add(new InsnNode(Opcodes.LSUB)); } // (a^b)-((~a&b)<<1)
                else { lLoad(l,a); lNot(l,b); l.add(new InsnNode(Opcodes.LXOR)); lLoad(l,a); lNot(l,b); l.add(new InsnNode(Opcodes.LAND)); l1shl(l); l.add(new InsnNode(Opcodes.LADD)); l.add(new InsnNode(Opcodes.LCONST_1)); l.add(new InsnNode(Opcodes.LADD)); } // (a^~b)+((a&~b)<<1)+1
            }
            case Opcodes.LXOR -> {
                if (rng.nextBoolean()) { lOr(l,a,b); lAnd(l,a,b); l.add(new InsnNode(Opcodes.LSUB)); }         // (a|b)-(a&b)
                else { lLoad(l,a); lNot(l,b); l.add(new InsnNode(Opcodes.LAND)); lNot(l,a); lLoad(l,b); l.add(new InsnNode(Opcodes.LAND)); l.add(new InsnNode(Opcodes.LOR)); } // (a&~b)|(~a&b)
            }
            case Opcodes.LOR -> {
                if (rng.nextBoolean()) { lAnd(l,a,b); lXor(l,a,b); l.add(new InsnNode(Opcodes.LADD)); }        // (a&b)+(a^b)
                else { lNot(l,a); lNot(l,b); l.add(new InsnNode(Opcodes.LAND)); lm1(l); l.add(new InsnNode(Opcodes.LXOR)); } // ~(~a&~b)
            }
            case Opcodes.LAND -> {
                if (rng.nextBoolean()) { lNot(l,a); lNot(l,b); l.add(new InsnNode(Opcodes.LOR)); lm1(l); l.add(new InsnNode(Opcodes.LXOR)); } // ~(~a|~b)
                else { lLoad(l,a); lLoad(l,a); lNot(l,b); l.add(new InsnNode(Opcodes.LAND)); l.add(new InsnNode(Opcodes.LSUB)); } // a-(a&~b)
            }
        }
        return l;
    }

    private static void lLoad(InsnList l, int s) { l.add(new VarInsnNode(Opcodes.LLOAD, s)); }
    private static void lm1(InsnList l)          { l.add(new LdcInsnNode(-1L)); }
    private static void lNot(InsnList l, int s)  { lLoad(l, s); lm1(l); l.add(new InsnNode(Opcodes.LXOR)); }
    private static void lXor(InsnList l, int a, int b) { lLoad(l,a); lLoad(l,b); l.add(new InsnNode(Opcodes.LXOR)); }
    private static void lAnd(InsnList l, int a, int b) { lLoad(l,a); lLoad(l,b); l.add(new InsnNode(Opcodes.LAND)); }
    private static void lOr(InsnList l, int a, int b)  { lLoad(l,a); lLoad(l,b); l.add(new InsnNode(Opcodes.LOR)); }
    private static void l1shl(InsnList l) { l.add(new InsnNode(Opcodes.ICONST_1)); l.add(new InsnNode(Opcodes.LSHL)); }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
}
