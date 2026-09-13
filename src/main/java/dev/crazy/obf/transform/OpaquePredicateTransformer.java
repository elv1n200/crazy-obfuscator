package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.PrintStream;
import java.util.Random;

/**
 * Argument-driven opaque predicates.
 *
 * <p>Guards a method body with a predicate that is provably true for
 * <em>every</em> value of one of the method's own {@code int}-category
 * arguments — but a static analyser has to reason about the bit/arith algebra to
 * prove it, so it can't be constant-folded away the way a {@code field != null}
 * guard can:
 *
 * <pre>
 *   (x | 1) != 0            // an odd number is never zero
 *   (x &amp; ~x) == 0           // x AND NOT-x is always zero
 *   (x * (x + 1) &amp; 1) == 0  // the product of two consecutive ints is even
 * </pre>
 *
 * The always-true branch falls through to the real body; the impossible branch
 * is a dead {@code ACONST_NULL; ATHROW} that never runs but keeps the verifier
 * happy. Because the predicate depends on a real runtime argument (not a
 * constant or a synthetic guard field), it survives naive opaque-predicate
 * removal. All identities hold under two's-complement overflow.
 *
 * <p>Sound gates: concrete method, not {@code <init>}/{@code <clinit>}, has an
 * {@code int/boolean/byte/short/char} parameter, entry stack is empty (so the
 * inserted guard is trivially frame-consistent). Enabled by
 * {@link dev.crazy.obf.config.ObfConfig#opaquePredicates}.
 */
public final class OpaquePredicateTransformer implements Transformer {

    private final PrintStream log;
    private int inserted;

    public OpaquePredicateTransformer(PrintStream log) { this.log = log == null ? System.out : log; }
    public OpaquePredicateTransformer() { this(System.out); }

    @Override public String name() { return "opaque"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().opaquePredicates) return;
        int chance = clamp(ctx.config().opaquePredicateChance, 0, 100);
        if (chance <= 0) return;
        Random rng = new Random(ctx.seed() ^ 0x0FAADECAFL);

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.name.startsWith("crazy/")) continue;
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null || m.instructions.size() == 0) continue;
                if (m.name.charAt(0) == '<') continue;                       // no ctor/clinit
                if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                int slot = firstIntArgSlot(m);
                if (slot < 0) continue;                                      // needs an int-ish arg
                if (rng.nextInt(100) >= chance) continue;

                m.instructions.insert(buildGuard(slot, rng.nextInt(3)));
                inserted++;
            }
        }
        log.println("[crazy] opaque: inserted " + inserted + " argument-driven predicate(s)");
    }

    /** Local slot of the first int-category parameter, or -1 if none. */
    private static int firstIntArgSlot(MethodNode m) {
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1;
        for (Type t : Type.getArgumentTypes(m.desc)) {
            switch (t.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.CHAR, Type.INT -> { return slot; }
                default -> slot += t.getSize();
            }
        }
        return -1;
    }

    private static InsnList buildGuard(int slot, int shape) {
        InsnList l = new InsnList();
        LabelNode real = new LabelNode();
        switch (shape) {
            case 0 -> {                                   // (x | 1) != 0  -> always true
                l.add(new VarInsnNode(Opcodes.ILOAD, slot));
                l.add(new InsnNode(Opcodes.ICONST_1));
                l.add(new InsnNode(Opcodes.IOR));
                l.add(new JumpInsnNode(Opcodes.IFNE, real));
            }
            case 1 -> {                                   // (x & ~x) == 0 -> always true
                l.add(new VarInsnNode(Opcodes.ILOAD, slot));
                l.add(new VarInsnNode(Opcodes.ILOAD, slot));
                l.add(new InsnNode(Opcodes.ICONST_M1));
                l.add(new InsnNode(Opcodes.IXOR));        // ~x
                l.add(new InsnNode(Opcodes.IAND));        // x & ~x
                l.add(new JumpInsnNode(Opcodes.IFEQ, real));
            }
            default -> {                                  // (x*(x+1) & 1) == 0 -> always true
                l.add(new VarInsnNode(Opcodes.ILOAD, slot));
                l.add(new VarInsnNode(Opcodes.ILOAD, slot));
                l.add(new InsnNode(Opcodes.ICONST_1));
                l.add(new InsnNode(Opcodes.IADD));        // x+1
                l.add(new InsnNode(Opcodes.IMUL));        // x*(x+1)
                l.add(new InsnNode(Opcodes.ICONST_1));
                l.add(new InsnNode(Opcodes.IAND));        // & 1
                l.add(new JumpInsnNode(Opcodes.IFEQ, real));
            }
        }
        // impossible branch — never reached, but a valid verifier path
        l.add(new InsnNode(Opcodes.ACONST_NULL));
        l.add(new InsnNode(Opcodes.ATHROW));
        l.add(real);
        return l;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
}
