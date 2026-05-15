package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Adds a handful of synthetic never-called methods to each class.
 *
 *   - Inflate output noticeably so byte-level signature scanners get confused
 *   - Waste reverse-engineering time on dead code
 *   - Lower signal-to-noise ratio in decompiled output
 *
 * Methods are marked ACC_SYNTHETIC so most tools hide them by default — but
 * a determined attacker still has to parse and dismiss them.
 */
public final class JunkCodeTransformer implements Transformer {

    private static final long SEED_MIX = 0xABCDEF0123456789L;

    @Override public String name() { return "junk"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().injectJunk) return;
        Random rng = new Random(ctx.seed() ^ SEED_MIX);

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0) continue;

            // Build existing (name, desc) set so we never collide with
            // pre-existing methods (e.g. Kotlin's generated `get()Object;` on
            // `by lazy {}` delegate classes). Real bug fixed here: see
            // crash-2026-05-15 cop/module/impl/misc/AutoGFS$amount$2.
            Set<String> existing = new HashSet<>();
            if (cn.methods != null) {
                for (MethodNode m : cn.methods) existing.add(m.name + m.desc);
            }

            int n = 2 + rng.nextInt(4); // 2..5 junk methods per class
            for (int i = 0; i < n; i++) {
                // Prefix with "CRAZY$j" — guarantees no collision with user
                // bytecode while still being short. The alpha counter never
                // produces a "$" so the prefixed form is disjoint from the
                // generator's namespace.
                String name = "CRAZY$j" + ctx.names().nextMethod();
                addJunkMethod(cn, name, existing, rng);
            }
        }
    }

    private void addJunkMethod(ClassNode cn, String name, Set<String> existing, Random rng) {
        // 4 shapes:
        //   0: void no-op            ()V                  RETURN
        //   1: throws an unreachable RE
        //   2: returns a fake int from a small arithmetic chain
        //   3: returns null Object
        int shape = rng.nextInt(4);
        String desc = switch (shape) {
            case 0 -> "()V";
            case 1 -> "()V";
            case 2 -> "()I";
            default -> "()Ljava/lang/Object;";
        };

        // Defense in depth: if we somehow still collide, skip.
        if (!existing.add(name + desc)) return;

        MethodNode mn = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            name, desc, null, null);

        switch (shape) {
            case 0 -> mn.visitInsn(Opcodes.RETURN);
            case 1 -> {
                mn.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
                mn.visitInsn(Opcodes.DUP);
                mn.visitLdcInsn("");
                mn.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false);
                mn.visitInsn(Opcodes.ATHROW);
            }
            case 2 -> {
                int a = rng.nextInt();
                int b = rng.nextInt();
                mn.visitLdcInsn(a);
                mn.visitLdcInsn(b);
                mn.visitInsn(Opcodes.IXOR);
                mn.visitLdcInsn(a ^ b);
                mn.visitInsn(Opcodes.ISUB);
                mn.visitInsn(Opcodes.IRETURN);
            }
            default -> {
                mn.visitInsn(Opcodes.ACONST_NULL);
                mn.visitInsn(Opcodes.ARETURN);
            }
        }
        mn.visitMaxs(0, 0);

        if (cn.methods == null) cn.methods = new ArrayList<>();
        cn.methods.add(mn);
    }
}
