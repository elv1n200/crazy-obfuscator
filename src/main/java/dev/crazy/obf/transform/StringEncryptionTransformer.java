package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Polymorphic string-literal encryption.
 *
 * Every encrypted class gets its OWN decoder method whose:
 *   - name is randomized (3-char suffix on CRAZY$d)
 *   - mixing constants (mult, add) are randomized
 *   - decryption formula is one of three equivalent shapes
 *
 * Encryption is symmetric XOR:
 *   c[i] ^= ((salt * mult + i * add) & 0x7FFF)
 *
 * The decoder ALSO XORs with the same expression — so encrypt is its own inverse.
 *
 * Why polymorphism matters: a deobfuscator that knows the ZKM/Allatori pattern
 * looks for a single CRAZY$d shape jar-wide. With per-class constants and shapes,
 * a generic pattern doesn't match — they have to deal with each class.
 */
public final class StringEncryptionTransformer implements Transformer {

    private static final String DECODER_PREFIX = "CRAZY$d";
    private static final String DECODER_DESC   = "(Ljava/lang/String;I)Ljava/lang/String;";

    /** Injected condy bootstrap (only when hideStringsCondy is enabled). */
    public  static final String CONDY = "crazy/C";
    private static final String CONDY_BSM_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
      + "Ljava/lang/String;III)Ljava/lang/Object;";
    private static final Handle CONDY_BSM =
        new Handle(Opcodes.H_INVOKESTATIC, CONDY, "s", CONDY_BSM_DESC, false);

    /**
     * Per-class decoder spec. The keystream is a keyed LCG whose output is
     * xorshift-mixed, seeded from the per-call-site salt:
     *
     *   st = salt
     *   repeat:  st = st*A + C;  k = st; k ^= k>>>13; k ^= k<<9; k ^= k>>>17;
     *            cipher[i] ^= (k & 0x7FFF)
     *
     * A is odd (full-period LCG over 2^32). The nonlinear mix means an
     * attacker can't recover A/C from a couple of known plaintext chars and
     * decrypt the rest with linear algebra (the old (salt*mult+i*add) scheme
     * could be solved from two chars). Still ~8 int ops/char, fully reversible
     * (XOR the identical stream), deterministic across offline encode + the
     * injected runtime decoder.
     */
    private static final class Spec {
        final String methodName;
        final int a;   // LCG multiplier (odd)
        final int c;   // LCG increment
        Spec(String n, int a, int c) { methodName = n; this.a = a; this.c = c; }
    }

    private final Map<String, Spec> specs = new HashMap<>();

    @Override public String name() { return "strings"; }

    @Override
    public void apply(ObfContext ctx) {
        int chance = clamp(ctx.config().stringEncryptionChance, 0, 100);

        // Targeted mode: if exact strings and/or regexes are configured, ONLY
        // matching literals are encrypted (everything else stays plaintext).
        // Lets you hide just a URL/endpoint/token without the cost & risk of
        // encrypting every string. Active even if chance == 0.
        java.util.Set<String> exact = new java.util.HashSet<>(
            ctx.config().encryptStringsExact == null ? java.util.List.of() : ctx.config().encryptStringsExact);
        java.util.List<java.util.regex.Pattern> patterns = new java.util.ArrayList<>();
        if (ctx.config().encryptStringsMatching != null) {
            for (String p : ctx.config().encryptStringsMatching) {
                if (p == null || p.isEmpty()) continue;
                try { patterns.add(java.util.regex.Pattern.compile(p)); }
                catch (java.util.regex.PatternSyntaxException e) {
                    patterns.add(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(p)));
                }
            }
        }
        boolean targeted = !exact.isEmpty() || !patterns.isEmpty();
        if (chance <= 0 && !targeted) return;
        boolean condyMode = ctx.config().hideStringsCondy;
        boolean anyCondy = false;
        Random rng = new Random(ctx.seed() ^ 0xDECAFC0FFEEL);

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.name.equals(CONDY)) continue;
            if (cn.methods == null || cn.methods.isEmpty()) continue;
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0) continue;

            // condy needs class-file version >= 55 (Java 11); older classes
            // transparently fall back to the inline decoder method.
            boolean useCondy = condyMode && (cn.version & 0xFFFF) >= Opcodes.V11;

            boolean touched = false;
            Spec spec = null;

            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                if ((m.access & Opcodes.ACC_ABSTRACT) != 0) continue;
                if ((m.access & Opcodes.ACC_NATIVE) != 0) continue;

                for (AbstractInsnNode ins : m.instructions.toArray()) {
                    if (!(ins instanceof LdcInsnNode ldc)) continue;
                    if (!(ldc.cst instanceof String s)) continue;
                    if (s.isEmpty()) continue;
                    // Never encrypt strings the scanners flagged as runtime-critical
                    // (reflection/mixin/Fabric names) — that holds even in targeted mode.
                    if (ctx.exclusions().shouldPreserveString(s)) continue;
                    if (targeted) {
                        // explicit selection: only matching literals, and an
                        // explicit pick overrides the looksLikeReference skip
                        // (so URLs/identifiers the user named DO get encrypted)
                        if (!isTargeted(s, exact, patterns)) continue;
                    } else {
                        if (looksLikeReference(s)) continue;
                        if (rng.nextInt(100) >= chance) continue;
                    }

                    if (useCondy) {
                        // ldc "text"  ->  ldc CONSTANT_Dynamic(s)  whose bootstrap
                        // (crazy/C.s) regenerates the keystream and returns the
                        // decoded String at link time. No plaintext, no visible
                        // decoder call — opaque to javap and decompilers alike.
                        int salt = rng.nextInt();
                        int a = rng.nextInt() | 1;       // odd -> full-period LCG
                        int c = rng.nextInt() | 1;
                        String enc = transform(s, salt, a, c);
                        ConstantDynamic cd = new ConstantDynamic(
                            "s", "Ljava/lang/String;", CONDY_BSM, enc, salt, a, c);
                        m.instructions.set(ins, new LdcInsnNode(cd));
                        anyCondy = true;
                        touched = true;
                        continue;
                    }

                    if (spec == null) spec = mintSpec(cn.name, rng);

                    int salt = rng.nextInt();
                    String enc = transform(s, salt, spec.a, spec.c);

                    InsnList replacement = new InsnList();
                    replacement.add(new LdcInsnNode(enc));
                    replacement.add(intConst(salt));
                    replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, spec.methodName, DECODER_DESC, false));
                    m.instructions.insert(ins, replacement);
                    m.instructions.remove(ins);
                    touched = true;
                }
            }

            if (touched && spec != null) {
                injectDecoder(cn, spec);
                specs.put(cn.name, spec);
            }
        }

        if (anyCondy) injectCondyBootstrap(ctx);
    }

    /**
     * Injects {@code crazy/C} (once per jar) with the condy bootstrap:
     *
     *   public static Object s(Lookup l, String name, Class&lt;?&gt; type,
     *                          String enc, int salt, int a, int c) {
     *       char[] arr = enc.toCharArray();
     *       int st = salt;
     *       for (int i = 0; i &lt; arr.length; i++) {
     *           st = st*a + c;
     *           int k = st; k ^= k>>>13; k ^= k<<9; k ^= k>>>17;
     *           arr[i] = (char)(arr[i] ^ (k & 0x7FFF));
     *       }
     *       return new String(arr).intern();
     *   }
     *
     * Same keystream as {@link #transform} / {@link #emitDecoder}, so encode and
     * link-time decode stay in lockstep.
     */
    private void injectCondyBootstrap(ObfContext ctx) {
        if (ctx.contents().classes().containsKey(CONDY)) return;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            CONDY, null, "java/lang/Object", null);

        var ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);

        var s = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "s", CONDY_BSM_DESC, null, null);
        // arr = enc.toCharArray()  (local 7)
        s.visitVarInsn(Opcodes.ALOAD, 3);
        s.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
        s.visitVarInsn(Opcodes.ASTORE, 7);
        // st = salt  (local 8)
        s.visitVarInsn(Opcodes.ILOAD, 4);
        s.visitVarInsn(Opcodes.ISTORE, 8);
        // i = 0  (local 9)
        s.visitInsn(Opcodes.ICONST_0);
        s.visitVarInsn(Opcodes.ISTORE, 9);

        Label loop = new Label(), end = new Label();
        s.visitLabel(loop);
        s.visitVarInsn(Opcodes.ILOAD, 9);
        s.visitVarInsn(Opcodes.ALOAD, 7);
        s.visitInsn(Opcodes.ARRAYLENGTH);
        s.visitJumpInsn(Opcodes.IF_ICMPGE, end);

        // st = st*a + c
        s.visitVarInsn(Opcodes.ILOAD, 8);
        s.visitVarInsn(Opcodes.ILOAD, 5);
        s.visitInsn(Opcodes.IMUL);
        s.visitVarInsn(Opcodes.ILOAD, 6);
        s.visitInsn(Opcodes.IADD);
        s.visitVarInsn(Opcodes.ISTORE, 8);
        // k = st  (local 10)
        s.visitVarInsn(Opcodes.ILOAD, 8);
        s.visitVarInsn(Opcodes.ISTORE, 10);
        // k ^= k >>> 13
        s.visitVarInsn(Opcodes.ILOAD, 10);
        s.visitVarInsn(Opcodes.ILOAD, 10);
        s.visitIntInsn(Opcodes.BIPUSH, 13);
        s.visitInsn(Opcodes.IUSHR);
        s.visitInsn(Opcodes.IXOR);
        s.visitVarInsn(Opcodes.ISTORE, 10);
        // k ^= k << 9
        s.visitVarInsn(Opcodes.ILOAD, 10);
        s.visitVarInsn(Opcodes.ILOAD, 10);
        s.visitIntInsn(Opcodes.BIPUSH, 9);
        s.visitInsn(Opcodes.ISHL);
        s.visitInsn(Opcodes.IXOR);
        s.visitVarInsn(Opcodes.ISTORE, 10);
        // k ^= k >>> 17
        s.visitVarInsn(Opcodes.ILOAD, 10);
        s.visitVarInsn(Opcodes.ILOAD, 10);
        s.visitIntInsn(Opcodes.BIPUSH, 17);
        s.visitInsn(Opcodes.IUSHR);
        s.visitInsn(Opcodes.IXOR);
        s.visitVarInsn(Opcodes.ISTORE, 10);
        // arr[i] = (char)(arr[i] ^ (k & 0x7FFF))
        s.visitVarInsn(Opcodes.ALOAD, 7);
        s.visitVarInsn(Opcodes.ILOAD, 9);
        s.visitVarInsn(Opcodes.ALOAD, 7);
        s.visitVarInsn(Opcodes.ILOAD, 9);
        s.visitInsn(Opcodes.CALOAD);
        s.visitVarInsn(Opcodes.ILOAD, 10);
        s.visitLdcInsn(0x7FFF);
        s.visitInsn(Opcodes.IAND);
        s.visitInsn(Opcodes.IXOR);
        s.visitInsn(Opcodes.I2C);
        s.visitInsn(Opcodes.CASTORE);

        s.visitIincInsn(9, 1);
        s.visitJumpInsn(Opcodes.GOTO, loop);

        s.visitLabel(end);
        s.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        s.visitInsn(Opcodes.DUP);
        s.visitVarInsn(Opcodes.ALOAD, 7);
        s.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
        s.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "intern", "()Ljava/lang/String;", false);
        s.visitInsn(Opcodes.ARETURN);
        s.visitMaxs(0, 0);

        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        ctx.contents().classes().put(cn.name, cn);
    }

    private Spec mintSpec(String classKey, Random rng) {
        int a = rng.nextInt() | 1;   // odd -> full-period LCG mod 2^32
        int c = rng.nextInt() | 1;
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 4; i++) suffix.append((char)('a' + rng.nextInt(26)));
        return new Spec(DECODER_PREFIX + suffix, a, c);
    }

    private void injectDecoder(ClassNode cn, Spec spec) {
        if (cn.methods != null) {
            for (MethodNode m : cn.methods) {
                if (spec.methodName.equals(m.name) && DECODER_DESC.equals(m.desc)) return;
            }
        }
        MethodNode mn = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            spec.methodName, DECODER_DESC, null, null);

        emitDecoder(mn, spec);

        if (cn.methods == null) cn.methods = new java.util.ArrayList<>();
        cn.methods.add(mn);
    }

    /**
     * Emits: locals — s@0, salt@1, c@2 (char[]), i@3, st@4, k@5.
     *
     *   c = s.toCharArray(); st = salt;
     *   for (i=0; i<c.length; i++) {
     *       st = st*A + C;
     *       k = st; k ^= k>>>13; k ^= k<<9; k ^= k>>>17;
     *       c[i] = (char)(c[i] ^ (k & 0x7FFF));
     *   }
     *   return new String(c).intern();
     */
    private void emitDecoder(MethodNode mn, Spec spec) {
        mn.visitVarInsn(Opcodes.ALOAD, 0);
        mn.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
        mn.visitVarInsn(Opcodes.ASTORE, 2);

        mn.visitVarInsn(Opcodes.ILOAD, 1);
        mn.visitVarInsn(Opcodes.ISTORE, 4);            // st = salt

        mn.visitInsn(Opcodes.ICONST_0);
        mn.visitVarInsn(Opcodes.ISTORE, 3);            // i = 0

        Label loop = new Label(), end = new Label();
        mn.visitLabel(loop);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitInsn(Opcodes.ARRAYLENGTH);
        mn.visitJumpInsn(Opcodes.IF_ICMPGE, end);

        // st = st*A + C
        mn.visitVarInsn(Opcodes.ILOAD, 4);
        pushIntConst(mn, spec.a);
        mn.visitInsn(Opcodes.IMUL);
        pushIntConst(mn, spec.c);
        mn.visitInsn(Opcodes.IADD);
        mn.visitVarInsn(Opcodes.ISTORE, 4);

        // k = st
        mn.visitVarInsn(Opcodes.ILOAD, 4);
        mn.visitVarInsn(Opcodes.ISTORE, 5);
        // k ^= k >>> 13
        mn.visitVarInsn(Opcodes.ILOAD, 5);
        mn.visitVarInsn(Opcodes.ILOAD, 5);
        pushIntConst(mn, 13);
        mn.visitInsn(Opcodes.IUSHR);
        mn.visitInsn(Opcodes.IXOR);
        mn.visitVarInsn(Opcodes.ISTORE, 5);
        // k ^= k << 9
        mn.visitVarInsn(Opcodes.ILOAD, 5);
        mn.visitVarInsn(Opcodes.ILOAD, 5);
        pushIntConst(mn, 9);
        mn.visitInsn(Opcodes.ISHL);
        mn.visitInsn(Opcodes.IXOR);
        mn.visitVarInsn(Opcodes.ISTORE, 5);
        // k ^= k >>> 17
        mn.visitVarInsn(Opcodes.ILOAD, 5);
        mn.visitVarInsn(Opcodes.ILOAD, 5);
        pushIntConst(mn, 17);
        mn.visitInsn(Opcodes.IUSHR);
        mn.visitInsn(Opcodes.IXOR);
        mn.visitVarInsn(Opcodes.ISTORE, 5);

        // c[i] = (char)(c[i] ^ (k & 0x7FFF))
        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitVarInsn(Opcodes.ILOAD, 3);
        mn.visitInsn(Opcodes.CALOAD);
        mn.visitVarInsn(Opcodes.ILOAD, 5);
        mn.visitLdcInsn(0x7FFF);
        mn.visitInsn(Opcodes.IAND);
        mn.visitInsn(Opcodes.IXOR);
        mn.visitInsn(Opcodes.I2C);
        mn.visitInsn(Opcodes.CASTORE);

        mn.visitIincInsn(3, 1);
        mn.visitJumpInsn(Opcodes.GOTO, loop);

        mn.visitLabel(end);
        emitReturnNewString(mn);
    }

    private void emitReturnNewString(MethodNode mn) {
        mn.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        mn.visitInsn(Opcodes.DUP);
        mn.visitVarInsn(Opcodes.ALOAD, 2);
        mn.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
        mn.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "intern", "()Ljava/lang/String;", false);
        mn.visitInsn(Opcodes.ARETURN);
        mn.visitMaxs(0, 0);
    }

    private static void pushIntConst(MethodNode mn, int v) {
        if (v >= -1 && v <= 5) mn.visitInsn(Opcodes.ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mn.visitIntInsn(Opcodes.BIPUSH, v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mn.visitIntInsn(Opcodes.SIPUSH, v);
        else mn.visitLdcInsn(v);
    }

    /**
     * Symmetric transform — XORs with the exact same keystream the injected
     * decoder regenerates. Must mirror {@link #emitDecoder} bit-for-bit; JVM
     * int ops (IMUL/IADD/IUSHR/ISHL/IXOR) match Java int semantics, so offline
     * encode and runtime decode stay in lockstep. Package-private for tests.
     */
    public static String transform(String s, int salt, int a, int c0) {
        char[] c = s.toCharArray();
        int st = salt;
        for (int i = 0; i < c.length; i++) {
            st = st * a + c0;
            int k = st;
            k ^= k >>> 13;
            k ^= k << 9;
            k ^= k >>> 17;
            c[i] = (char) (c[i] ^ (k & 0x7FFF));
        }
        return new String(c);
    }

    private static AbstractInsnNode intConst(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v);
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE)  return new IntInsnNode(Opcodes.BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, v);
        return new LdcInsnNode(v);
    }

    /** Targeted-mode predicate: exact match OR any regex matches (substring). */
    public static boolean isTargeted(String s, java.util.Set<String> exact,
                                     java.util.List<java.util.regex.Pattern> patterns) {
        if (exact != null && exact.contains(s)) return true;
        if (patterns != null) for (var p : patterns) if (p.matcher(s).find()) return true;
        return false;
    }

    private static boolean looksLikeReference(String s) {
        if (s.length() > 4096) return true;
        if (s.startsWith("META-INF/")) return true;
        if (s.endsWith(".class") || s.endsWith(".json") || s.endsWith(".png") || s.endsWith(".ogg") || s.endsWith(".mcmeta")) return true;
        if (s.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) return true;
        // Kotlin callable-reference signature strings (baked into bytecode by
        // `X::prop` / `X::fun`). These must stay PLAIN so the name pass can
        // remap the class names inside them to match the renamed metadata.
        // Form: "getSelectedTheme()Lcop/module/...;" or "invoke(Lcop/X;)V".
        if (isMemberSignature(s)) return true;
        return false;
    }

    /** True if the string looks like a JVM member signature (has (...) and an object type). */
    static boolean isMemberSignature(String s) {
        int lp = s.indexOf('('), rp = s.indexOf(')');
        if (lp < 0 || rp < lp) return false;
        // must contain at least one object type reference with a package path
        int l = s.indexOf('L');
        return l >= 0 && s.indexOf(';', l) > l && s.indexOf('/') >= 0;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
}
