package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Embeds a watermark inside the obfuscated jar.
 *
 *   1. A synthetic class `crazy/W` is added with a static final String field
 *      `M` whose value encodes:  build-time, seed, user-supplied tag.
 *   2. A resource at `META-INF/crazy-build.txt` contains the same info plus a
 *      list of all renamed top-level classes so we can verify mapping integrity.
 *
 * The class is invisible to most class browsers (synthetic + private), but if
 * a leaked jar surfaces you can grep for `crazy/W.class` and read the field to
 * identify the build that leaked.
 */
public final class WatermarkTransformer implements Transformer {

    public static final String WATERMARK_CLASS = "crazy/W";

    @Override public String name() { return "watermark"; }

    @Override
    public void apply(ObfContext ctx) {
        String tag = ctx.config().watermark;
        if (tag == null || tag.isBlank()) tag = "anon";

        long ts = System.currentTimeMillis();
        String payload = "ts=" + ts + ";seed=" + ctx.seed() + ";tag=" + tag;
        String b64 = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        // 1. inject class
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V11,
            Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            WATERMARK_CLASS, null, "java/lang/Object", null);
        FieldNode f = new FieldNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "M", "Ljava/lang/String;", null, b64);
        f.accept(cw);
        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        ctx.contents().classes().put(cn.name, cn);

        // 2. add resource
        StringBuilder sb = new StringBuilder();
        sb.append("# crazy-obfuscator build metadata\n");
        sb.append("timestamp=").append(ts).append('\n');
        sb.append("seed=").append(ctx.seed()).append('\n');
        sb.append("tag=").append(tag).append('\n');
        sb.append("classes=").append(ctx.remapper().classes.size()).append('\n');
        sb.append("methods=").append(ctx.remapper().methods.size()).append('\n');
        sb.append("fields=").append(ctx.remapper().fields.size()).append('\n');
        ctx.contents().resources().put("META-INF/crazy-build.txt",
            sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
