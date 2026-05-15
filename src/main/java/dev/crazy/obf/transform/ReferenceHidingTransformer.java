package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.PrintStream;

/**
 * invokedynamic-based reference hiding.
 *
 * Rewrites eligible call sites to {@code invokedynamic} bound to an injected,
 * self-decrypting bootstrap ({@code crazy/Indy}). The decompiler then sees
 * {@code invokedynamic i(...)} with encrypted bootstrap args instead of the
 * real {@code owner.method} — the internal call graph disappears. The
 * bootstrap resolves the real target once and returns a {@link
 * java.lang.invoke.ConstantCallSite}, so steady-state cost is nil.
 *
 * Deliberately conservative — a hardening pass must never break the program:
 *   - only INVOKESTATIC / INVOKEVIRTUAL / INVOKEINTERFACE
 *     (INVOKESPECIAL = ctor/super/private is verifier-hostile via indy)
 *   - target owner must be one of our own classes (the IP-relevant graph),
 *     not a no-touch (mixin) class, not an array
 *   - the resolved target must be PUBLIC so the call site's Lookup can bind it
 *   - never &lt;init&gt;/&lt;clinit&gt;, never touch crazy/Indy itself
 *
 * Runs after NameTransformer so the embedded owner/name are the final
 * (renamed) symbols. Enabled by ObfConfig.hideReferences.
 */
public final class ReferenceHidingTransformer implements Transformer {

    public static final String INDY = "crazy/Indy";
    private static final String BSM_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
      + "Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;";

    private int rewrites;

    private final PrintStream log;
    public ReferenceHidingTransformer(PrintStream log) { this.log = log; }
    public ReferenceHidingTransformer() { this(System.out); }

    @Override public String name() { return "refhide"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().hideReferences) return;
        int key = (int) (ctx.seed() ^ 0x5DEECE66DL) | 1;

        var classes = ctx.contents().classes();
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, INDY, "i", BSM_DESC, false);

        for (ClassNode cn : classes.values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.name.equals(INDY)) continue;
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                for (AbstractInsnNode ins : m.instructions.toArray()) {
                    if (!(ins instanceof MethodInsnNode mi)) continue;
                    int kind = kindOf(mi.getOpcode());
                    if (kind < 0) continue;
                    if (mi.name.charAt(0) == '<') continue;          // <init>/<clinit>
                    if (mi.owner.charAt(0) == '[') continue;         // array owner
                    if (mi.owner.equals(INDY)) continue;
                    if (!eligibleTarget(ctx, mi, kind)) continue;

                    Type ret = Type.getReturnType(mi.desc);
                    Type[] args = Type.getArgumentTypes(mi.desc);
                    String indyDesc;
                    if (kind == 0) {                                 // static
                        indyDesc = mi.desc;
                    } else {                                         // virtual/interface: receiver -> first param
                        StringBuilder sb = new StringBuilder("(L").append(mi.owner).append(';');
                        for (Type a : args) sb.append(a.getDescriptor());
                        sb.append(')').append(ret.getDescriptor());
                        indyDesc = sb.toString();
                    }
                    InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(
                        "i", indyDesc, bsm,
                        enc(mi.owner, key), enc(mi.name, key), kind);
                    m.instructions.set(mi, indy);
                    rewrites++;
                }
            }
        }

        if (rewrites > 0) injectBootstrap(ctx, key);
        log.println("[crazy] refhide: rewired " + rewrites + " call site(s) through invokedynamic");
    }

    private static int kindOf(int op) {
        return switch (op) {
            case Opcodes.INVOKESTATIC -> 0;
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> 1;
            default -> -1; // INVOKESPECIAL etc. -> skip
        };
    }

    /** Owner must be ours; resolved target must be public + static-ness must match. */
    private boolean eligibleTarget(ObfContext ctx, MethodInsnNode mi, int kind) {
        ClassNode owner = ctx.contents().classes().get(mi.owner);
        if (owner == null) return false;                       // external (JDK/MC/lib) — leave visible
        if ((owner.access & Opcodes.ACC_INTERFACE) != 0 && kind == 1) {
            return true;                                        // interface methods are implicitly public
        }
        ClassNode c = owner;
        int guard = 0;
        while (c != null && guard++ < 64) {
            if (c.methods != null) {
                for (MethodNode tm : c.methods) {
                    if (!tm.name.equals(mi.name) || !tm.desc.equals(mi.desc)) continue;
                    boolean isStatic = (tm.access & Opcodes.ACC_STATIC) != 0;
                    if (kind == 0 && !isStatic) return false;
                    if (kind == 1 && isStatic) return false;
                    return (tm.access & Opcodes.ACC_PUBLIC) != 0;
                }
            }
            c = c.superName == null ? null : ctx.contents().classes().get(c.superName);
        }
        return false; // not found within our jar -> don't risk it
    }

    /** Symmetric XOR (mirrors crazy/Indy.d). BMP-safe so it survives the constant pool. */
    private static String enc(String s, int key) {
        char[] a = s.toCharArray();
        for (int i = 0; i < a.length; i++) a[i] = (char) (a[i] ^ ((key + i * 31) & 0x7FFF));
        return new String(a);
    }

    /**
     * Injects:
     *   public final class crazy.Indy {
     *     public static CallSite i(Lookup l, String n, MethodType t,
     *                              String eo, String en, int kind) throws Throwable {
     *       Class<?> c = Class.forName(d(eo).replace('/','.'), false,
     *                                  l.lookupClass().getClassLoader());
     *       MethodHandle mh = kind == 0
     *           ? l.findStatic(c, d(en), t)
     *           : l.findVirtual(c, d(en), t.dropParameterTypes(0,1));
     *       return new ConstantCallSite(mh.asType(t));
     *     }
     *     static String d(String s){...XOR...}
     *   }
     */
    private void injectBootstrap(ObfContext ctx, int key) {
        if (ctx.contents().classes().containsKey(INDY)) return;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            INDY, null, "java/lang/Object", null);

        var ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);

        // static String d(String s) — XOR decode, mirrors enc()
        var d = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "d",
            "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        d.visitVarInsn(Opcodes.ALOAD, 0);
        d.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
        d.visitVarInsn(Opcodes.ASTORE, 1);
        d.visitInsn(Opcodes.ICONST_0);
        d.visitVarInsn(Opcodes.ISTORE, 2);
        Label dl = new Label(), de = new Label();
        d.visitLabel(dl);
        d.visitVarInsn(Opcodes.ILOAD, 2);
        d.visitVarInsn(Opcodes.ALOAD, 1);
        d.visitInsn(Opcodes.ARRAYLENGTH);
        d.visitJumpInsn(Opcodes.IF_ICMPGE, de);
        d.visitVarInsn(Opcodes.ALOAD, 1);
        d.visitVarInsn(Opcodes.ILOAD, 2);
        d.visitVarInsn(Opcodes.ALOAD, 1);
        d.visitVarInsn(Opcodes.ILOAD, 2);
        d.visitInsn(Opcodes.CALOAD);
        d.visitLdcInsn(key);
        d.visitVarInsn(Opcodes.ILOAD, 2);
        d.visitIntInsn(Opcodes.BIPUSH, 31);
        d.visitInsn(Opcodes.IMUL);
        d.visitInsn(Opcodes.IADD);
        d.visitLdcInsn(0x7FFF);
        d.visitInsn(Opcodes.IAND);
        d.visitInsn(Opcodes.IXOR);
        d.visitInsn(Opcodes.I2C);
        d.visitInsn(Opcodes.CASTORE);
        d.visitIincInsn(2, 1);
        d.visitJumpInsn(Opcodes.GOTO, dl);
        d.visitLabel(de);
        d.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        d.visitInsn(Opcodes.DUP);
        d.visitVarInsn(Opcodes.ALOAD, 1);
        d.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
        d.visitInsn(Opcodes.ARETURN);
        d.visitMaxs(0, 0);

        // CallSite i(Lookup l, String n, MethodType t, String eo, String en, int kind)
        var i = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "i", BSM_DESC,
            null, new String[]{"java/lang/Throwable"});
        // Class c = Class.forName(d(eo).replace('/','.'), false, l.lookupClass().getClassLoader())
        i.visitVarInsn(Opcodes.ALOAD, 3);
        i.visitMethodInsn(Opcodes.INVOKESTATIC, INDY, "d", "(Ljava/lang/String;)Ljava/lang/String;", false);
        i.visitIntInsn(Opcodes.BIPUSH, '/');
        i.visitIntInsn(Opcodes.BIPUSH, '.');
        i.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replace", "(CC)Ljava/lang/String;", false);
        i.visitInsn(Opcodes.ICONST_0);
        i.visitVarInsn(Opcodes.ALOAD, 0);
        i.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "lookupClass", "()Ljava/lang/Class;", false);
        i.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
        i.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        i.visitVarInsn(Opcodes.ASTORE, 6);                       // c

        Label virt = new Label(), done = new Label();
        i.visitVarInsn(Opcodes.ILOAD, 5);                        // kind
        i.visitJumpInsn(Opcodes.IFNE, virt);

        // static: mh = l.findStatic(c, d(en), t)
        i.visitVarInsn(Opcodes.ALOAD, 0);
        i.visitVarInsn(Opcodes.ALOAD, 6);
        i.visitVarInsn(Opcodes.ALOAD, 4);
        i.visitMethodInsn(Opcodes.INVOKESTATIC, INDY, "d", "(Ljava/lang/String;)Ljava/lang/String;", false);
        i.visitVarInsn(Opcodes.ALOAD, 2);
        i.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findStatic",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
        i.visitJumpInsn(Opcodes.GOTO, done);

        // virtual/interface: mh = l.findVirtual(c, d(en), t.dropParameterTypes(0,1))
        i.visitLabel(virt);
        i.visitVarInsn(Opcodes.ALOAD, 0);
        i.visitVarInsn(Opcodes.ALOAD, 6);
        i.visitVarInsn(Opcodes.ALOAD, 4);
        i.visitMethodInsn(Opcodes.INVOKESTATIC, INDY, "d", "(Ljava/lang/String;)Ljava/lang/String;", false);
        i.visitVarInsn(Opcodes.ALOAD, 2);
        i.visitInsn(Opcodes.ICONST_0);
        i.visitInsn(Opcodes.ICONST_1);
        i.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodType", "dropParameterTypes",
            "(II)Ljava/lang/invoke/MethodType;", false);
        i.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findVirtual",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);

        // return new ConstantCallSite(mh.asType(t))
        i.visitLabel(done);
        i.visitVarInsn(Opcodes.ASTORE, 7);                       // mh
        i.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/ConstantCallSite");
        i.visitInsn(Opcodes.DUP);
        i.visitVarInsn(Opcodes.ALOAD, 7);
        i.visitVarInsn(Opcodes.ALOAD, 2);
        i.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "asType",
            "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
        i.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>",
            "(Ljava/lang/invoke/MethodHandle;)V", false);
        i.visitInsn(Opcodes.ARETURN);
        i.visitMaxs(0, 0);

        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        ctx.contents().classes().put(cn.name, cn);
    }
}
