package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.Random;

/**
 * Control-flow obfuscation.
 *
 * Level 1 (default): adds a uniform opaque predicate at method entry
 *
 *     if (CRAZY$BOMB != null) goto real else throw new RuntimeException();
 *
 * Level 2: per-method polymorphism — 3 different predicate shapes, plus a
 * scattered GOTO chain inserted at a random mid-method instruction, which
 * is logically a no-op but makes decompiler output uglier.
 */
public final class FlowTransformer implements Transformer {

    private static final String GUARD_FIELD = "CRAZY$BOMB";
    private static final String GUARD_DESC  = "Ljava/lang/Object;";
    private static final String LONG_FIELD  = "CRAZY$L";
    private static final String LONG_DESC   = "J";

    @Override public String name() { return "flow"; }

    @Override
    public void apply(ObfContext ctx) {
        int level = ctx.config().flowLevel;
        if (level <= 0) return;
        Random rng = new Random(ctx.seed() ^ 0xF10FDEADC0DEL);

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0) continue;
            if (cn.methods == null) continue;

            boolean usedGuard = false;
            boolean usedLong  = false;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null || m.instructions.size() < 2) continue;
                if ("<clinit>".equals(m.name) || "<init>".equals(m.name)) continue;
                if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;

                int shape = level >= 2 ? rng.nextInt(3) : 0;
                switch (shape) {
                    case 0 -> { addReferenceGuard(cn.name, m); usedGuard = true; }
                    case 1 -> { addHashCodeGuard(cn.name, m); usedGuard = true; }
                    case 2 -> { addLongGuard(cn.name, m); usedLong = true; }
                }

                if (level >= 2 && rng.nextInt(100) < 35) sprinkleGotoChain(m, rng);
            }

            if (usedGuard) ensureGuardField(cn);
            if (usedLong)  ensureLongField(cn, ctx.seed());
        }
    }

    // ----- shape 0: reference != null --------------------------------------

    private void addReferenceGuard(String owner, MethodNode m) {
        InsnList prelude = new InsnList();
        LabelNode real = new LabelNode(new Label());

        prelude.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, GUARD_FIELD, GUARD_DESC));
        prelude.add(new JumpInsnNode(Opcodes.IFNONNULL, real));
        addThrow(prelude);
        prelude.add(real);

        AbstractInsnNode first = m.instructions.getFirst();
        m.instructions.insertBefore(first, prelude);
    }

    // ----- shape 1: hashCode() != Integer.MIN_VALUE ------------------------

    private void addHashCodeGuard(String owner, MethodNode m) {
        InsnList prelude = new InsnList();
        LabelNode real = new LabelNode(new Label());

        prelude.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, GUARD_FIELD, GUARD_DESC));
        prelude.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false));
        prelude.add(new LdcInsnNode(Integer.MIN_VALUE));
        prelude.add(new JumpInsnNode(Opcodes.IF_ICMPNE, real));
        addThrow(prelude);
        prelude.add(real);

        AbstractInsnNode first = m.instructions.getFirst();
        m.instructions.insertBefore(first, prelude);
    }

    // ----- shape 2: a non-zero seed long --------------------------------

    private void addLongGuard(String owner, MethodNode m) {
        InsnList prelude = new InsnList();
        LabelNode real = new LabelNode(new Label());

        prelude.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, LONG_FIELD, LONG_DESC));
        prelude.add(new InsnNode(Opcodes.LCONST_0));
        prelude.add(new InsnNode(Opcodes.LCMP));
        prelude.add(new JumpInsnNode(Opcodes.IFNE, real));
        addThrow(prelude);
        prelude.add(real);

        AbstractInsnNode first = m.instructions.getFirst();
        m.instructions.insertBefore(first, prelude);
    }

    private void addThrow(InsnList prelude) {
        prelude.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        prelude.add(new InsnNode(Opcodes.DUP));
        prelude.add(new LdcInsnNode(""));
        prelude.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false));
        prelude.add(new InsnNode(Opcodes.ATHROW));
    }

    // ----- mid-method GOTO chain ----------------------------------------

    private void sprinkleGotoChain(MethodNode m, Random rng) {
        // pick a random label position in the method that is not inside a
        // catch block (heuristic: skip the first 4 insns we just added)
        AbstractInsnNode[] arr = m.instructions.toArray();
        if (arr.length < 10) return;
        int pickedIdx = 6 + rng.nextInt(arr.length - 7);
        AbstractInsnNode anchor = arr[pickedIdx];

        // Build: GOTO L2 ; L1: GOTO L3 ; L2: GOTO L1 ; L3:  (then the original anchor)
        LabelNode l1 = new LabelNode(new Label());
        LabelNode l2 = new LabelNode(new Label());
        LabelNode l3 = new LabelNode(new Label());

        InsnList chain = new InsnList();
        chain.add(new JumpInsnNode(Opcodes.GOTO, l2));
        chain.add(l1);
        chain.add(new JumpInsnNode(Opcodes.GOTO, l3));
        chain.add(l2);
        chain.add(new JumpInsnNode(Opcodes.GOTO, l1));
        chain.add(l3);

        m.instructions.insertBefore(anchor, chain);
    }

    // ----- helper fields -----------------------------------------------

    private void ensureGuardField(ClassNode cn) {
        if (cn.fields != null) {
            for (FieldNode f : cn.fields) {
                if (GUARD_FIELD.equals(f.name) && GUARD_DESC.equals(f.desc)) {
                    initGuardInClinit(cn);
                    return;
                }
            }
        }
        if (cn.fields == null) cn.fields = new ArrayList<>();
        cn.fields.add(new FieldNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            GUARD_FIELD, GUARD_DESC, null, null));
        initGuardInClinit(cn);
    }

    private void ensureLongField(ClassNode cn, long seed) {
        if (cn.fields != null) {
            for (FieldNode f : cn.fields) {
                if (LONG_FIELD.equals(f.name) && LONG_DESC.equals(f.desc)) {
                    initLongInClinit(cn, seed);
                    return;
                }
            }
        }
        if (cn.fields == null) cn.fields = new ArrayList<>();
        cn.fields.add(new FieldNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            LONG_FIELD, LONG_DESC, null, null));
        initLongInClinit(cn, seed);
    }

    private void initGuardInClinit(ClassNode cn) {
        MethodNode clinit = clinit(cn);
        for (AbstractInsnNode ins : clinit.instructions.toArray()) {
            if (ins instanceof FieldInsnNode fi
                && fi.getOpcode() == Opcodes.PUTSTATIC
                && fi.owner.equals(cn.name)
                && GUARD_FIELD.equals(fi.name)) return;
        }
        InsnList init = new InsnList();
        init.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
        init.add(new InsnNode(Opcodes.DUP));
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, GUARD_FIELD, GUARD_DESC));
        AbstractInsnNode first = clinit.instructions.getFirst();
        if (first == null) clinit.instructions.add(init);
        else clinit.instructions.insertBefore(first, init);
    }

    private void initLongInClinit(ClassNode cn, long seed) {
        MethodNode clinit = clinit(cn);
        for (AbstractInsnNode ins : clinit.instructions.toArray()) {
            if (ins instanceof FieldInsnNode fi
                && fi.getOpcode() == Opcodes.PUTSTATIC
                && fi.owner.equals(cn.name)
                && LONG_FIELD.equals(fi.name)) return;
        }
        // value: System.nanoTime() | 1L  — guaranteed non-zero and unknown at compile time
        InsnList init = new InsnList();
        init.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        init.add(new LdcInsnNode(1L));
        init.add(new InsnNode(Opcodes.LOR));
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, LONG_FIELD, LONG_DESC));
        AbstractInsnNode first = clinit.instructions.getFirst();
        if (first == null) clinit.instructions.add(init);
        else clinit.instructions.insertBefore(first, init);
    }

    private MethodNode clinit(ClassNode cn) {
        if (cn.methods != null) {
            for (MethodNode m : cn.methods) if ("<clinit>".equals(m.name)) return m;
        }
        MethodNode m = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        if (cn.methods == null) cn.methods = new ArrayList<>();
        cn.methods.add(m);
        return m;
    }
}
