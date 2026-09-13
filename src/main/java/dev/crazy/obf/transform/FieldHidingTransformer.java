package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.PrintStream;

/**
 * invokedynamic-based field-access hiding — the data-flow analogue of
 * {@link ReferenceHidingTransformer}.
 *
 * <p>Rewrites eligible {@code GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC} sites to an
 * {@code invokedynamic} bound to an injected, self-decrypting bootstrap
 * ({@code crazy/FIndy}). A decompiler then sees an opaque dynamic call site with
 * encrypted args instead of {@code owner.field} — the read/write graph over your
 * own fields disappears. The bootstrap resolves a getter/setter
 * {@link java.lang.invoke.MethodHandle} once and returns a
 * {@link java.lang.invoke.ConstantCallSite}, so steady-state cost is nil.
 *
 * <p>Conservative soundness gates (a hardening pass must never break the program):
 * <ul>
 *   <li>the field must be <em>declared in the referenced owner</em> and that
 *       owner must be one of our own classes (not external, not a no-touch/mixin
 *       class, not {@code crazy/*});</li>
 *   <li>accessible from the call site's full-privilege {@code Lookup}: either the
 *       owner IS the accessing class (self — private ok) or the field is
 *       {@code public};</li>
 *   <li>never inside {@code <init>}/{@code <clinit>} (uninitialisedThis /
 *       final-field-init are verifier-hostile through indy);</li>
 *   <li>never {@code volatile} (a plain handle would drop volatile semantics) and
 *       never a <em>write</em> to a {@code final} field ({@code findSetter}
 *       rejects final).</li>
 * </ul>
 *
 * <p>Runs after {@link NameTransformer} so the encoded owner/name are the final
 * (renamed) symbols. Enabled by {@link dev.crazy.obf.config.ObfConfig#hideFields}.
 */
public final class FieldHidingTransformer implements Transformer {

    public static final String FINDY = "crazy/FIndy";
    private static final String BSM_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
      + "Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;";

    private final PrintStream log;
    private int rewrites;

    public FieldHidingTransformer(PrintStream log) { this.log = log == null ? System.out : log; }
    public FieldHidingTransformer() { this(System.out); }

    @Override public String name() { return "fieldhide"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().hideFields) return;
        int key = (int) (ctx.seed() ^ 0x1E1DC0DEL) | 1;
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, FINDY, "f", BSM_DESC, false);

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.name.equals(FINDY) || cn.name.startsWith("crazy/")) continue;
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                if (m.name.charAt(0) == '<') continue;          // skip <init>/<clinit>
                for (AbstractInsnNode ins : m.instructions.toArray()) {
                    if (!(ins instanceof FieldInsnNode fi)) continue;
                    int kind = kindOf(fi.getOpcode());
                    if (kind < 0) continue;
                    if (fi.owner.charAt(0) == '[') continue;    // array owner (e.g. .length isn't a field insn anyway)
                    if (!eligible(ctx, cn.name, fi, kind)) continue;

                    String indyDesc = switch (kind) {
                        case 0 -> "(L" + fi.owner + ";)" + fi.desc;        // GETFIELD  (owner)T
                        case 1 -> "(L" + fi.owner + ";" + fi.desc + ")V";  // PUTFIELD  (owner,T)V
                        case 2 -> "()" + fi.desc;                          // GETSTATIC ()T
                        default -> "(" + fi.desc + ")V";                   // PUTSTATIC (T)V
                    };
                    InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(
                        "f", indyDesc, bsm, enc(fi.owner, key), enc(fi.name, key), kind);
                    m.instructions.set(fi, indy);
                    rewrites++;
                }
            }
        }

        if (rewrites > 0) injectBootstrap(ctx, key);
        log.println("[crazy] fieldhide: rewired " + rewrites + " field access(es) through invokedynamic");
    }

    private static int kindOf(int op) {
        return switch (op) {
            case Opcodes.GETFIELD  -> 0;
            case Opcodes.PUTFIELD  -> 1;
            case Opcodes.GETSTATIC -> 2;
            case Opcodes.PUTSTATIC -> 3;
            default -> -1;
        };
    }

    /**
     * The field must be declared in the referenced owner (one of our classes),
     * accessible from {@code accessor}'s lookup, non-volatile, and — for writes —
     * non-final. Static-ness must match the opcode.
     */
    private boolean eligible(ObfContext ctx, String accessor, FieldInsnNode fi, int kind) {
        if (ctx.exclusions().isClassNoTouch(fi.owner)) return false;
        if (fi.owner.startsWith("crazy/")) return false;
        ClassNode owner = ctx.contents().classes().get(fi.owner);
        if (owner == null || owner.fields == null) return false;   // external — leave visible
        FieldNode f = null;
        for (FieldNode cand : owner.fields) {
            if (cand.name.equals(fi.name) && cand.desc.equals(fi.desc)) { f = cand; break; }
        }
        if (f == null) return false;                               // inherited/synthetic elsewhere — don't risk it
        if ((f.access & Opcodes.ACC_VOLATILE) != 0) return false;  // keep volatile semantics
        boolean isStatic = (f.access & Opcodes.ACC_STATIC) != 0;
        boolean opStatic = (kind == 2 || kind == 3);
        if (isStatic != opStatic) return false;
        boolean isWrite = (kind == 1 || kind == 3);
        if (isWrite && (f.access & Opcodes.ACC_FINAL) != 0) return false; // findSetter rejects final
        // accessible: self (private ok — the call-site Lookup owns it) or public
        if (fi.owner.equals(accessor)) return true;
        return (f.access & Opcodes.ACC_PUBLIC) != 0;
    }

    /** Symmetric XOR (mirrors crazy/FIndy.d). BMP-safe so it survives the constant pool. */
    private static String enc(String s, int key) {
        char[] a = s.toCharArray();
        for (int i = 0; i < a.length; i++) a[i] = (char) (a[i] ^ ((key + i * 31) & 0x7FFF));
        return new String(a);
    }

    /**
     * Injects:
     *   public final class crazy.FIndy {
     *     public static CallSite f(Lookup l, String n, MethodType t,
     *                              String eo, String en, int kind) throws Throwable {
     *       Class<?> c  = Class.forName(d(eo).replace('/','.'), false,
     *                                   l.lookupClass().getClassLoader());
     *       String  fn  = d(en);
     *       MethodHandle mh = switch (kind) {
     *         case 0 -> l.findGetter(c, fn, t.returnType());
     *         case 1 -> l.findSetter(c, fn, t.parameterType(1));
     *         case 2 -> l.findStaticGetter(c, fn, t.returnType());
     *         default-> l.findStaticSetter(c, fn, t.parameterType(0));
     *       };
     *       return new ConstantCallSite(mh.asType(t));
     *     }
     *     static String d(String s){...XOR...}   // mirrors enc()
     *   }
     */
    private void injectBootstrap(ObfContext ctx, int key) {
        if (ctx.contents().classes().containsKey(FINDY)) return;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            FINDY, null, "java/lang/Object", null);

        var ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);

        emitDecode(cw, key);

        final String LOOKUP = "java/lang/invoke/MethodHandles$Lookup";
        final String MT = "java/lang/invoke/MethodType";
        final String FIND_DESC = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;";

        var f = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "f", BSM_DESC,
            null, new String[]{"java/lang/Throwable"});
        // c = Class.forName(d(eo).replace('/','.'), false, l.lookupClass().getClassLoader())
        f.visitVarInsn(Opcodes.ALOAD, 3);
        f.visitMethodInsn(Opcodes.INVOKESTATIC, FINDY, "d", "(Ljava/lang/String;)Ljava/lang/String;", false);
        f.visitIntInsn(Opcodes.BIPUSH, '/');
        f.visitIntInsn(Opcodes.BIPUSH, '.');
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replace", "(CC)Ljava/lang/String;", false);
        f.visitInsn(Opcodes.ICONST_0);
        f.visitVarInsn(Opcodes.ALOAD, 0);
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LOOKUP, "lookupClass", "()Ljava/lang/Class;", false);
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
        f.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        f.visitVarInsn(Opcodes.ASTORE, 6);                       // c
        // fn = d(en)
        f.visitVarInsn(Opcodes.ALOAD, 4);
        f.visitMethodInsn(Opcodes.INVOKESTATIC, FINDY, "d", "(Ljava/lang/String;)Ljava/lang/String;", false);
        f.visitVarInsn(Opcodes.ASTORE, 7);                       // fn

        // dispatch on kind -> mh (local 8):
        //   0 instance getter, 1 instance setter, 2 static getter, 3 static setter
        Label arm0 = new Label(), arm1 = new Label(), arm2 = new Label(), arm3 = new Label(), done = new Label();
        f.visitVarInsn(Opcodes.ILOAD, 5); f.visitJumpInsn(Opcodes.IFEQ, arm0);
        f.visitVarInsn(Opcodes.ILOAD, 5); f.visitInsn(Opcodes.ICONST_1); f.visitJumpInsn(Opcodes.IF_ICMPEQ, arm1);
        f.visitVarInsn(Opcodes.ILOAD, 5); f.visitInsn(Opcodes.ICONST_2); f.visitJumpInsn(Opcodes.IF_ICMPEQ, arm2);
        f.visitJumpInsn(Opcodes.GOTO, arm3);

        // kind 0: instance getter — mh = l.findGetter(c, fn, t.returnType())
        f.visitLabel(arm0);
        f.visitVarInsn(Opcodes.ALOAD, 0);
        f.visitVarInsn(Opcodes.ALOAD, 6);
        f.visitVarInsn(Opcodes.ALOAD, 7);
        f.visitVarInsn(Opcodes.ALOAD, 2);
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MT, "returnType", "()Ljava/lang/Class;", false);
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LOOKUP, "findGetter", FIND_DESC, false);
        f.visitVarInsn(Opcodes.ASTORE, 8);
        f.visitJumpInsn(Opcodes.GOTO, done);

        f.visitLabel(arm1); // kind 1: instance setter — l.findSetter(c, fn, t.parameterType(1))
        f.visitVarInsn(Opcodes.ALOAD, 0);
        f.visitVarInsn(Opcodes.ALOAD, 6);
        f.visitVarInsn(Opcodes.ALOAD, 7);
        f.visitVarInsn(Opcodes.ALOAD, 2);
        f.visitInsn(Opcodes.ICONST_1);
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MT, "parameterType", "(I)Ljava/lang/Class;", false);
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LOOKUP, "findSetter", FIND_DESC, false);
        f.visitVarInsn(Opcodes.ASTORE, 8);
        f.visitJumpInsn(Opcodes.GOTO, done);

        f.visitLabel(arm2); // kind 2: static getter — l.findStaticGetter(c, fn, t.returnType())
        f.visitVarInsn(Opcodes.ALOAD, 0);
        f.visitVarInsn(Opcodes.ALOAD, 6);
        f.visitVarInsn(Opcodes.ALOAD, 7);
        f.visitVarInsn(Opcodes.ALOAD, 2);
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MT, "returnType", "()Ljava/lang/Class;", false);
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LOOKUP, "findStaticGetter", FIND_DESC, false);
        f.visitVarInsn(Opcodes.ASTORE, 8);
        f.visitJumpInsn(Opcodes.GOTO, done);

        f.visitLabel(arm3); // kind 3: static setter — l.findStaticSetter(c, fn, t.parameterType(0))
        f.visitVarInsn(Opcodes.ALOAD, 0);
        f.visitVarInsn(Opcodes.ALOAD, 6);
        f.visitVarInsn(Opcodes.ALOAD, 7);
        f.visitVarInsn(Opcodes.ALOAD, 2);
        f.visitInsn(Opcodes.ICONST_0);
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MT, "parameterType", "(I)Ljava/lang/Class;", false);
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LOOKUP, "findStaticSetter", FIND_DESC, false);
        f.visitVarInsn(Opcodes.ASTORE, 8);

        // return new ConstantCallSite(mh.asType(t))
        f.visitLabel(done);
        f.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/ConstantCallSite");
        f.visitInsn(Opcodes.DUP);
        f.visitVarInsn(Opcodes.ALOAD, 8);
        f.visitVarInsn(Opcodes.ALOAD, 2);
        f.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "asType",
            "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
        f.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>",
            "(Ljava/lang/invoke/MethodHandle;)V", false);
        f.visitInsn(Opcodes.ARETURN);
        f.visitMaxs(0, 0);

        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        ctx.contents().classes().put(cn.name, cn);
    }


    private void emitDecode(ClassWriter cw, int key) {
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
    }
}
