package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * XOR-encrypts selected jar resources in place and injects a runtime helper
 * class `crazy/R` that the user's code can call to read them:
 *
 *   byte[] data = crazy.R.read("assets/cop/foo.json");
 *
 * The encrypted resources are stored as `<original-name>.crz` (so a casual
 * inspector who unzips the jar doesn't see them) and the original path is
 * removed. The helper is responsible for finding the encrypted bytes and
 * decrypting them with the embedded key.
 *
 * Use this for embedded JSON, text data, route files — anything that's read
 * at runtime via Class.getResourceAsStream. NOT for resources Fabric/Mixin or
 * Minecraft reads (those need their original path and contents intact).
 *
 * Disabled unless `encryptResources` config list is non-empty.
 */
public final class ResourceEncryptionTransformer implements Transformer {

    public static final String HELPER_CLASS = "crazy/R";
    public static final String EXT = ".crz";

    @Override public String name() { return "resenc"; }

    @Override
    public void apply(ObfContext ctx) {
        var patterns = ctx.config().encryptResources;
        if (patterns == null || patterns.isEmpty()) return;

        var compiled = patterns.stream().map(ResourceEncryptionTransformer::globToRegex).toList();
        int seedKey = (int) (ctx.seed() ^ 0xC5C5A5A5L);

        Map<String, byte[]> repl = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : ctx.contents().resources().entrySet()) {
            String path = e.getKey();
            boolean match = false;
            for (Pattern p : compiled) if (p.matcher(path).matches()) { match = true; break; }
            if (!match) continue;
            byte[] enc = xor(e.getValue(), seedKey);
            repl.put(path, enc);
        }

        for (var e : repl.entrySet()) {
            ctx.contents().resources().remove(e.getKey());
            ctx.contents().resources().put(e.getKey() + EXT, e.getValue());
        }

        if (!repl.isEmpty()) injectHelper(ctx, seedKey);
    }

    private void injectHelper(ObfContext ctx, int key) {
        // Generated helper:
        //
        //   public final class crazy.R {
        //       public static byte[] read(String path) throws IOException {
        //           InputStream in = crazy.R.class.getClassLoader().getResourceAsStream(path + ".crz");
        //           if (in == null) return null;
        //           byte[] b = in.readAllBytes();
        //           in.close();
        //           for (int i = 0; i < b.length; i++) b[i] = (byte)(b[i] ^ (KEY >>> ((i & 3) << 3)));
        //           return b;
        //       }
        //   }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            HELPER_CLASS, null, "java/lang/Object", null);

        var ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);

        var read = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "read", "(Ljava/lang/String;)[B", null, new String[]{"java/io/IOException"});

        // InputStream in = R.class.getClassLoader().getResourceAsStream(path + ".crz")
        read.visitLdcInsn(org.objectweb.asm.Type.getObjectType(HELPER_CLASS));
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
        // build "path + .crz"
        read.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        read.visitInsn(Opcodes.DUP);
        read.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        read.visitLdcInsn(EXT);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;", false);
        read.visitVarInsn(Opcodes.ASTORE, 1);

        // if (in == null) return null
        var nonNull = new org.objectweb.asm.Label();
        read.visitVarInsn(Opcodes.ALOAD, 1);
        read.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
        read.visitInsn(Opcodes.ACONST_NULL);
        read.visitInsn(Opcodes.ARETURN);
        read.visitLabel(nonNull);

        // byte[] b = in.readAllBytes(); in.close();
        read.visitVarInsn(Opcodes.ALOAD, 1);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "readAllBytes", "()[B", false);
        read.visitVarInsn(Opcodes.ASTORE, 2);
        read.visitVarInsn(Opcodes.ALOAD, 1);
        read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "close", "()V", false);

        // for (int i = 0; i < b.length; i++) b[i] ^= (KEY >>> ((i & 3) << 3))
        read.visitInsn(Opcodes.ICONST_0);
        read.visitVarInsn(Opcodes.ISTORE, 3);

        var loop = new org.objectweb.asm.Label();
        var end  = new org.objectweb.asm.Label();
        read.visitLabel(loop);
        read.visitVarInsn(Opcodes.ILOAD, 3);
        read.visitVarInsn(Opcodes.ALOAD, 2);
        read.visitInsn(Opcodes.ARRAYLENGTH);
        read.visitJumpInsn(Opcodes.IF_ICMPGE, end);

        read.visitVarInsn(Opcodes.ALOAD, 2);
        read.visitVarInsn(Opcodes.ILOAD, 3);
        read.visitVarInsn(Opcodes.ALOAD, 2);
        read.visitVarInsn(Opcodes.ILOAD, 3);
        read.visitInsn(Opcodes.BALOAD);

        read.visitLdcInsn(key);
        read.visitVarInsn(Opcodes.ILOAD, 3);
        read.visitInsn(Opcodes.ICONST_3);
        read.visitInsn(Opcodes.IAND);
        read.visitInsn(Opcodes.ICONST_3);
        read.visitInsn(Opcodes.ISHL);
        read.visitInsn(Opcodes.IUSHR);
        read.visitInsn(Opcodes.IXOR);
        read.visitInsn(Opcodes.I2B);
        read.visitInsn(Opcodes.BASTORE);

        read.visitIincInsn(3, 1);
        read.visitJumpInsn(Opcodes.GOTO, loop);
        read.visitLabel(end);

        read.visitVarInsn(Opcodes.ALOAD, 2);
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(0, 0);

        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        ctx.contents().classes().put(cn.name, cn);
    }

    /** XOR encrypts/decrypts with a 4-byte key rotated by byte index. */
    static byte[] xor(byte[] data, int key) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            int shift = (i & 3) << 3;
            int k = (key >>> shift) & 0xFF;
            out[i] = (byte) (data[i] ^ k);
        }
        return out;
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') { sb.append(".*"); i++; }
                    else sb.append("[^/]*");
                    break;
                case '?': sb.append('.'); break;
                case '.': case '\\': case '+': case '(': case ')': case '[': case ']':
                case '{': case '}': case '|': case '^': case '$':
                    sb.append('\\').append(c); break;
                default: sb.append(c);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }
}
