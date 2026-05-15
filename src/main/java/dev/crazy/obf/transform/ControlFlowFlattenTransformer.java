package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import java.util.HashSet;
import java.util.Set;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Control-flow flattening via a dispatcher loop.
 *
 * A method's basic blocks are turned into cases of a
 * {@code while(true) switch($s) { ... }} so a decompiler can no longer
 * reconstruct the original if/loop structure — every block jumps back to a
 * central dispatcher with the next state id.
 *
 * Correctness over coverage. Flattening bytecode is verifier-hostile, so a
 * method is flattened ONLY if it is provably safe; otherwise it is left
 * untouched:
 *   - no try/catch, no MONITORENTER/EXIT, no JSR/RET, no TABLE/LOOKUPSWITCH
 *   - not &lt;init&gt;/&lt;clinit&gt;, not abstract/native
 *   - size within [minInsn, maxInsn]
 *   - the bytecode analyses cleanly (no unreachable code)
 *   - EVERY jump target sits at a basic-block boundary whose operand stack is
 *     empty (so a block can start there). If any target is mid-expression /
 *     non-empty-stack, the whole method is skipped.
 *
 * State ids are a random per-method permutation so the switch order leaks
 * nothing. The injected {@code $s} local is appended after the method's
 * locals; JarWriter's COMPUTE_FRAMES recomputes the stack map.
 */
public final class ControlFlowFlattenTransformer implements Transformer {

    private final PrintStream log;
    private int flattened, skipped;

    public ControlFlowFlattenTransformer(PrintStream log) { this.log = log; }
    public ControlFlowFlattenTransformer() { this(System.out); }

    @Override public String name() { return "flatten"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().flattenControlFlow) return;
        int chance = clamp(ctx.config().flattenChance, 0, 100);
        if (chance <= 0) return;
        Random rng = new Random(ctx.seed() ^ 0xF1A77E9L);

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null || m.instructions.size() == 0) continue;
                if (!safeCandidate(m)) { continue; }
                if (rng.nextInt(100) >= chance) continue;
                try {
                    if (flatten(cn.name, m, rng)) flattened++; else skipped++;
                } catch (Throwable t) {
                    skipped++; // never let flattening corrupt a method
                }
            }
        }
        log.println("[crazy] flatten: flattened " + flattened + " method(s), skipped " + skipped
            + " (unsafe/ineligible)");
    }

    private boolean safeCandidate(MethodNode m) {
        if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) return false;
        if (m.tryCatchBlocks != null && !m.tryCatchBlocks.isEmpty()) return false;
        int real = 0;
        for (AbstractInsnNode p : m.instructions.toArray()) {
            int op = p.getOpcode();
            if (op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT) return false;
            if (op == Opcodes.JSR || op == Opcodes.RET) return false;
            if (op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH) return false;
            // Local writes are allowed now; soundness is enforced in flatten()
            // by pre-initialising written non-param primitive locals so they
            // are definitely-assigned at the dispatcher merge (see there).
            if (p.getType() != AbstractInsnNode.LABEL
                && p.getType() != AbstractInsnNode.LINE
                && p.getType() != AbstractInsnNode.FRAME) real++;
        }
        return real >= 6 && real <= 3000;
    }

    private boolean flatten(String owner, MethodNode m, Random rng) throws Exception {
        AbstractInsnNode[] insns = m.instructions.toArray();

        // 1. stack heights + per-local types per instruction
        Analyzer<BasicValue> az = new Analyzer<>(new BasicInterpreter());
        Frame<BasicValue>[] frames = az.analyze(owner, m);

        // 1b. SOUNDNESS: flattening adds a dispatcher->block edge, so the
        // verifier merges each block's entry frame with the dispatcher; a
        // local written by another block would be `top` there -> VerifyError.
        // Fix: pre-initialise every written non-param local at method entry so
        // it is definitely-assigned with a fixed type at the merge. Sound only
        // for PRIMITIVE locals (int/long/float/double): reference slots reused
        // across disjoint scopes merge to a common supertype which is unsafe
        // to read as the original type, so any written reference local makes
        // the method ineligible (skipped, not flattened).
        int maxL = m.maxLocals;
        // 0 = unseen, 1 = INT, 2 = LONG, 3 = FLOAT, 4 = DOUBLE, 5 = REF, 9 = MIXED
        int[] cat = new int[maxL];
        for (Frame<BasicValue> f : frames) {
            if (f == null) continue;
            for (int s = 0; s < f.getLocals() && s < maxL; s++) {
                BasicValue v = f.getLocal(s);
                if (v == null) continue;
                Type vt = v.getType();
                if (vt == null) continue;            // uninitialised here
                int c = switch (vt.getSort()) {
                    case Type.VOID -> 0;
                    case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> 1;
                    case Type.LONG -> 2;
                    case Type.FLOAT -> 3;
                    case Type.DOUBLE -> 4;
                    default -> 5;                    // OBJECT / ARRAY
                };
                if (c == 0) continue;
                cat[s] = (cat[s] == 0) ? c : (cat[s] == c ? c : 9);
            }
        }
        // parameter slots are definitely-assigned at entry (no init needed)
        boolean[] isParam = new boolean[maxL];
        {
            int p = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
            if (p == 1 && maxL > 0) isParam[0] = true;
            for (Type a : Type.getArgumentTypes(m.desc)) {
                if (p < maxL) isParam[p] = true;
                p += a.getSize();
            }
        }
        // which slots get written
        boolean[] written = new boolean[maxL];
        for (AbstractInsnNode p : insns) {
            if (p instanceof VarInsnNode vi) {
                int op = vi.getOpcode();
                if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE && vi.var < maxL) written[vi.var] = true;
            } else if (p instanceof org.objectweb.asm.tree.IincInsnNode ii) {
                if (ii.var < maxL) written[ii.var] = true;
            }
        }
        // source dataflow (producer-preserving) — used to prove a written
        // reference slot only ever holds a single concrete type, so null-init
        // is sound (verifier merges null ⊔ T = T).
        Frame<SourceValue>[] sframes;
        try {
            sframes = new Analyzer<>(new SrcPreserve()).analyze(owner, m);
        } catch (Throwable t) { return false; }

        // eligibility + collect prologue inits
        List<int[]> prologueInit = new ArrayList<>(); // {slot, category}  (cat 5 = reference -> null-init)
        for (int s = 0; s < maxL; s++) {
            int c = cat[s];
            if (c == 0) continue;                    // slot unused
            if (c == 9) return false;                // mixed primitive/ref category -> unsafe
            if (c == 5) {
                if (!written[s] || isParam[s]) continue;     // unwritten/param ref -> already safe
                if (!singleRefType(insns, sframes, s)) return false;
                prologueInit.add(new int[]{s, 5});           // null-init
            } else if (written[s] && !isParam[s]) {
                prologueInit.add(new int[]{s, c});           // primitive default-init
            }
        }

        // 2. leaders: index 0; any jump target; insn after a branch/return/throw
        boolean[] leader = new boolean[insns.length];
        Map<AbstractInsnNode, Integer> idx = new IdentityHashMap<>();
        for (int i = 0; i < insns.length; i++) idx.put(insns[i], i);
        markLeader(leader, 0);
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode p = insns[i];
            if (p instanceof JumpInsnNode j) {
                leader[idx.get(j.label)] = true;
                if (i + 1 < insns.length) leader[i + 1] = true;
            } else {
                int op = p.getOpcode();
                if (isReturnOrThrow(op) && i + 1 < insns.length) leader[i + 1] = true;
            }
        }
        // a leader must fall on a real instruction; shift past labels/line/frame
        // and require empty operand stack there (so a block may start)
        List<Integer> blockStarts = new ArrayList<>();
        for (int i = 0; i < insns.length; i++) {
            if (!leader[i]) continue;
            int r = nextReal(insns, i);
            if (r < 0) continue;
            Frame<BasicValue> f = frames[r];
            if (f == null) return false;            // unreachable code — bail
            if (f.getStackSize() != 0) return false; // non-empty stack at a block start — unsafe, bail
            if (!blockStarts.contains(r)) blockStarts.add(r);
        }
        if (!blockStarts.contains(firstReal(insns))) return false;
        if (blockStarts.size() < 3) return false;   // nothing meaningful to flatten

        java.util.Collections.sort(blockStarts);

        // block index by start instruction index
        Map<Integer, Integer> blockOf = new LinkedHashMap<>();
        for (int b = 0; b < blockStarts.size(); b++) blockOf.put(blockStarts.get(b), b);

        // every JUMP target must be a block start (else we can't dispatch to it)
        for (AbstractInsnNode p : insns) {
            if (p instanceof JumpInsnNode j) {
                int t = nextReal(insns, idx.get(j.label));
                if (t < 0 || !blockOf.containsKey(t)) return false;
            }
        }

        int nBlocks = blockStarts.size();
        // random state-id permutation
        int[] stateOf = new int[nBlocks];
        List<Integer> perm = new ArrayList<>();
        for (int i = 0; i < nBlocks; i++) perm.add(i);
        java.util.Collections.shuffle(perm, rng);
        for (int i = 0; i < nBlocks; i++) stateOf[i] = perm.get(i);

        int stateVar = m.maxLocals;                 // fresh local slot
        LabelNode dispatch = new LabelNode();
        LabelNode[] caseLabel = new LabelNode[nBlocks];
        for (int i = 0; i < nBlocks; i++) caseLabel[i] = new LabelNode();

        InsnList out = new InsnList();
        // pre-initialise written non-param primitive locals so every local is
        // definitely-assigned with a fixed type at the dispatcher merge
        for (int[] si : prologueInit) {
            int slot = si[0], c = si[1];
            switch (c) {
                case 1 -> { out.add(new InsnNode(Opcodes.ICONST_0)); out.add(new VarInsnNode(Opcodes.ISTORE, slot)); }
                case 2 -> { out.add(new InsnNode(Opcodes.LCONST_0)); out.add(new VarInsnNode(Opcodes.LSTORE, slot)); }
                case 3 -> { out.add(new InsnNode(Opcodes.FCONST_0)); out.add(new VarInsnNode(Opcodes.FSTORE, slot)); }
                case 4 -> { out.add(new InsnNode(Opcodes.DCONST_0)); out.add(new VarInsnNode(Opcodes.DSTORE, slot)); }
                case 5 -> { out.add(new InsnNode(Opcodes.ACONST_NULL)); out.add(new VarInsnNode(Opcodes.ASTORE, slot)); }
                default -> { }
            }
        }
        // $s = state(entryBlock)
        out.add(intPush(stateOf[blockOf.get(firstReal(insns))]));
        out.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        out.add(dispatch);
        out.add(new VarInsnNode(Opcodes.ILOAD, stateVar));
        // lookupswitch over state ids -> case labels (sorted keys required)
        Integer[] order = new Integer[nBlocks];
        for (int i = 0; i < nBlocks; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(stateOf[a], stateOf[b]));
        int[] sortedKeys = new int[nBlocks];
        LabelNode[] sortedLabels = new LabelNode[nBlocks];
        for (int i = 0; i < nBlocks; i++) { sortedKeys[i] = stateOf[order[i]]; sortedLabels[i] = caseLabel[order[i]]; }
        out.add(new LookupSwitchInsnNode(caseLabel[0], sortedKeys, sortedLabels));

        // emit each block
        for (int b = 0; b < nBlocks; b++) {
            int start = blockStarts.get(b);
            int end = (b + 1 < nBlocks) ? blockStarts.get(b + 1) : insns.length; // exclusive
            out.add(caseLabel[b]);

            // copy block body except its terminating control transfer
            int lastReal = prevRealBefore(insns, end);
            for (int i = start; i < end; i++) {
                AbstractInsnNode p = insns[i];
                if (p instanceof LabelNode) continue;            // labels rebuilt via dispatcher
                if (p.getType() == AbstractInsnNode.LINE) continue;
                if (p.getType() == AbstractInsnNode.FRAME) continue;
                boolean isLast = (i == lastReal);
                if (p instanceof JumpInsnNode j) {
                    int tgtBlock = blockOf.get(nextReal(insns, idx.get(j.label)));
                    if (j.getOpcode() == Opcodes.GOTO) {
                        out.add(intPush(stateOf[tgtBlock]));
                        out.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
                        out.add(new JumpInsnNode(Opcodes.GOTO, dispatch));
                    } else {
                        // conditional: take-branch sets target state, else next block
                        if (b + 1 >= nBlocks) throw new IllegalStateException("cond in last block");
                        LabelNode take = new LabelNode();
                        out.add(new JumpInsnNode(j.getOpcode(), take));
                        int nextBlock = b + 1; // fall-through successor
                        out.add(intPush(stateOf[nextBlock]));
                        out.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
                        out.add(new JumpInsnNode(Opcodes.GOTO, dispatch));
                        out.add(take);
                        out.add(intPush(stateOf[tgtBlock]));
                        out.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
                        out.add(new JumpInsnNode(Opcodes.GOTO, dispatch));
                    }
                    continue;
                }
                int op = p.getOpcode();
                if (isLast && !isReturnOrThrow(op) && !(p instanceof JumpInsnNode)) {
                    // block falls through to next block
                    if (b + 1 >= nBlocks) throw new IllegalStateException("fallthrough in last block");
                    out.add(p.clone(new java.util.HashMap<>()));
                    out.add(intPush(stateOf[b + 1]));
                    out.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
                    out.add(new JumpInsnNode(Opcodes.GOTO, dispatch));
                } else {
                    out.add(p.clone(new java.util.HashMap<>()));
                }
            }
        }

        m.instructions = out;
        m.localVariables = null; // labels were rebuilt; drop debug locals (stripped anyway)
        m.maxLocals = Math.max(m.maxLocals, stateVar + 1);
        m.maxStack = Math.max(m.maxStack, 2);
        return true;
    }

    // --- helpers ---------------------------------------------------------

    /** SourceInterpreter that keeps the ORIGINAL producer through loads/stores/dup. */
    private static final class SrcPreserve extends SourceInterpreter {
        SrcPreserve() { super(Opcodes.ASM9); }
        @Override public SourceValue copyOperation(AbstractInsnNode i, SourceValue v) { return v; }
    }

    /**
     * True iff every value ever stored into reference slot {@code s} is either
     * the null constant or the SAME concrete object type. Then null-init is
     * verifier-sound (null ⊔ T = T). Any unknown producer => false (skip the
     * method — soundness over coverage).
     */
    private boolean singleRefType(AbstractInsnNode[] insns, Frame<SourceValue>[] sframes, int s) {
        String only = null;
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof VarInsnNode vi)
                || vi.getOpcode() != Opcodes.ASTORE || vi.var != s) continue;
            Frame<SourceValue> f = sframes[i];
            if (f == null) continue;                       // unreachable store
            if (f.getStackSize() == 0) return false;
            SourceValue sv = f.getStack(f.getStackSize() - 1);
            if (sv == null || sv.insns == null || sv.insns.isEmpty()) return false;
            for (AbstractInsnNode p : sv.insns) {
                String t = classifyRef(p);
                if (t == null) return false;               // unknown -> unsafe
                if (t.isEmpty()) continue;                  // null const -> compatible
                if (only == null) only = t;
                else if (!only.equals(t)) return false;     // conflicting types
            }
        }
        return true;
    }

    /** "" = null constant; non-empty = concrete object type; null = unknown. */
    private static String classifyRef(AbstractInsnNode p) {
        if (p instanceof InsnNode in && in.getOpcode() == Opcodes.ACONST_NULL) return "";
        if (p instanceof TypeInsnNode ti) {
            int op = ti.getOpcode();
            if (op == Opcodes.NEW || op == Opcodes.CHECKCAST) return ti.desc;
            return null;                                    // ANEWARRAY etc. -> skip
        }
        if (p instanceof FieldInsnNode fi) {
            Type t = Type.getType(fi.desc);
            return t.getSort() == Type.OBJECT ? t.getInternalName() : null;
        }
        if (p instanceof MethodInsnNode mi) {
            Type rt = Type.getReturnType(mi.desc);
            return rt.getSort() == Type.OBJECT ? rt.getInternalName() : null;
        }
        if (p instanceof InvokeDynamicInsnNode id) {
            Type rt = Type.getReturnType(id.desc);
            return rt.getSort() == Type.OBJECT ? rt.getInternalName() : null;
        }
        if (p instanceof LdcInsnNode ld) {
            if (ld.cst instanceof String) return "java/lang/String";
            if (ld.cst instanceof Type)   return "java/lang/Class";
            return null;
        }
        return null;                                        // ALOAD-copy / other -> skip
    }

    private static boolean isReturnOrThrow(int op) {
        return (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) || op == Opcodes.ATHROW;
    }
    private static void markLeader(boolean[] leader, int i) { if (i >= 0 && i < leader.length) leader[i] = true; }

    private static int nextReal(AbstractInsnNode[] insns, int from) {
        for (int i = from; i >= 0 && i < insns.length; i++) {
            AbstractInsnNode p = insns[i];
            if (p.getType() != AbstractInsnNode.LABEL
                && p.getType() != AbstractInsnNode.LINE
                && p.getType() != AbstractInsnNode.FRAME) return i;
        }
        return -1;
    }
    private static int firstReal(AbstractInsnNode[] insns) { return nextReal(insns, 0); }
    private static int prevRealBefore(AbstractInsnNode[] insns, int endExclusive) {
        for (int i = endExclusive - 1; i >= 0; i--) {
            AbstractInsnNode p = insns[i];
            if (p.getType() != AbstractInsnNode.LABEL
                && p.getType() != AbstractInsnNode.LINE
                && p.getType() != AbstractInsnNode.FRAME) return i;
        }
        return -1;
    }

    private static AbstractInsnNode intPush(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v);
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE)
            return new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE)
            return new org.objectweb.asm.tree.IntInsnNode(Opcodes.SIPUSH, v);
        return new LdcInsnNode(v);
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
}
