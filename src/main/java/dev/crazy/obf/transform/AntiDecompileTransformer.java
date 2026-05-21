package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.io.PrintStream;
import java.util.Random;

/**
 * Decompiler-confusion pass (behaviour-neutral).
 *
 * Wraps an eligible method body in a fake {@code try { ... } catch (Throwable t)
 * { throw t; }}. The handler is appended at the very end of the method and does
 * nothing but rethrow the caught throwable, so program behaviour is identical:
 * any exception that would have propagated to the caller still propagates with
 * the same stack trace. Normal control flow never falls into the handler (the
 * body ends in a return/throw), so the steady-state cost is zero.
 *
 * Why it confuses decompilers: the added exception edge spans the whole method
 * and re-enters at a point that high-level decompilers (CFR, Vineflower,
 * Procyon) must fold back into structured {@code try}/{@code catch}. Combined
 * with the existing flow/number passes the resulting graph is irreducible
 * enough that they emit garbage, bail to a goto soup, or refuse the method —
 * while {@code javap} and the JVM verifier are perfectly happy.
 *
 * Honest scope: this hides NOTHING from {@code javap -c} or a bytecode-level
 * reader. It only breaks *structured* decompilation. Pair it with string/number
 * obfuscation for actual secrecy. Opt-in via {@code ObfConfig.antiDecompile}.
 *
 * Soundness gates — only methods that are:
 *   - concrete (not abstract/native), not {@code <init>}/{@code <clinit>}
 *     ({@code <init>} is verifier-hostile: uninitializedThis under a handler)
 *   - have NO existing try/catch (so we never perturb real handler ranges or
 *     their ordering / priority)
 *   - have at least a handful of real instructions (tiny getters aren't worth it)
 */
public final class AntiDecompileTransformer implements Transformer {

    private final PrintStream log;
    private int wrapped;

    public AntiDecompileTransformer(PrintStream log) { this.log = log; }
    public AntiDecompileTransformer() { this(System.out); }

    @Override public String name() { return "antidecompile"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().antiDecompile) return;
        int chance = clamp(ctx.config().antiDecompileChance, 0, 100);
        if (chance <= 0) return;
        Random rng = new Random(ctx.seed() ^ 0xC0DEFACEL);

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (!eligible(m)) continue;
                if (rng.nextInt(100) >= chance) continue;
                wrap(m);
                wrapped++;
            }
        }
        log.println("[crazy] antidecompile: wrapped " + wrapped + " method(s) in opaque handlers");
    }

    private static boolean eligible(MethodNode m) {
        if (m.instructions == null) return false;
        if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        if (m.name.charAt(0) == '<') return false;                 // <init>/<clinit>
        if (m.tryCatchBlocks != null && !m.tryCatchBlocks.isEmpty()) return false;
        return realInsnCount(m) >= 6;
    }

    private static int realInsnCount(MethodNode m) {
        int n = 0;
        for (AbstractInsnNode ins = m.instructions.getFirst(); ins != null; ins = ins.getNext()) {
            int t = ins.getType();
            if (t == AbstractInsnNode.LABEL || t == AbstractInsnNode.LINE || t == AbstractInsnNode.FRAME) continue;
            n++;
        }
        return n;
    }

    /**
     * Layout:
     *   start:   ...original body... (ends in a return/throw, so no fall-through)
     *   end:
     *   handler: athrow            // exception is on the operand stack
     * with try-range [start,end) -> handler catching java/lang/Throwable.
     */
    private void wrap(MethodNode m) {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();

        m.instructions.insert(start);                  // before first real insn

        InsnList tail = new InsnList();
        tail.add(end);
        tail.add(handler);
        tail.add(new InsnNode(Opcodes.ATHROW));         // rethrow caught Throwable
        m.instructions.add(tail);                       // after last real insn

        if (m.tryCatchBlocks == null) m.tryCatchBlocks = new java.util.ArrayList<>();
        m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

        // frames/maxs are recomputed at write time (ClassWriter.COMPUTE_FRAMES)
        m.maxStack = Math.max(m.maxStack, 1);
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
}
