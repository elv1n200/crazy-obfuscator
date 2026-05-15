package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * Injects a synthetic class `crazy/AD` whose <clinit> checks for common
 * debugging / instrumentation hints and throws if any are present:
 *
 *   - JVM arg contains `-agentlib:jdwp`
 *   - JVM arg contains `-javaagent:`
 *   - JVM arg contains `-Xrunjdwp`
 *
 * Wires it in by adding a static initializer reference to every renamed class
 * that already has a <clinit> — but only if `antiDebug` is enabled. This way
 * the check fires at first class load.
 *
 * WARNING: This is a deterrent, not protection. A determined attacker can
 * patch the AD class out trivially. It catches casual snooping (someone trying
 * to attach a debugger to a leaked jar). False positives are possible on dev
 * builds — leave OFF by default.
 */
public final class AntiDebugTransformer implements Transformer {

    public static final String AD_CLASS = "crazy/AD";

    @Override public String name() { return "antidebug"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().antiDebug) return;

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            AD_CLASS, null, "java/lang/Object", null);

        // private AD() {}
        var ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);

        // public static void check() { ... }
        var check = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "check", "()V", null, null);
        // List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        check.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/management/ManagementFactory",
            "getRuntimeMXBean", "()Ljava/lang/management/RuntimeMXBean;", false);
        check.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/management/RuntimeMXBean",
            "getInputArguments", "()Ljava/util/List;", true);
        check.visitVarInsn(Opcodes.ASTORE, 0);

        // Iterator it = args.iterator();
        check.visitVarInsn(Opcodes.ALOAD, 0);
        check.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        check.visitVarInsn(Opcodes.ASTORE, 1);

        Label loop = new Label();
        Label end = new Label();
        check.visitLabel(loop);
        check.visitVarInsn(Opcodes.ALOAD, 1);
        check.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        check.visitJumpInsn(Opcodes.IFEQ, end);

        check.visitVarInsn(Opcodes.ALOAD, 1);
        check.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        check.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        check.visitVarInsn(Opcodes.ASTORE, 2);

        // if (arg.startsWith("-agentlib:jdwp") || .contains("-javaagent:") || .contains("-Xrunjdwp")) throw
        checkContains(check, "-agentlib:jdwp");
        checkContains(check, "-javaagent:");
        checkContains(check, "-Xrunjdwp");

        check.visitJumpInsn(Opcodes.GOTO, loop);
        check.visitLabel(end);
        check.visitInsn(Opcodes.RETURN);
        check.visitMaxs(0, 0);

        // <clinit> { check(); } so just loading the class triggers it
        var clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, AD_CLASS, "check", "()V", false);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);

        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        ctx.contents().classes().put(cn.name, cn);
    }

    private void checkContains(org.objectweb.asm.MethodVisitor mv, String needle) {
        Label skip = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitLdcInsn(needle);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/Error");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Error", "<init>", "()V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(skip);
    }
}
