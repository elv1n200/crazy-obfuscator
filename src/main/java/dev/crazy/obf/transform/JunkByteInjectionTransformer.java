package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Random;

/**
 * Byte injection — raw junk class-file attributes.
 *
 * <p>Writes non-standard {@code attribute_info} blobs straight into the class,
 * method and field structures. Per JVMS §4.7.1 a JVM <em>must silently ignore</em>
 * any attribute whose name it does not recognise (or that appears in a location
 * where it is not defined), so the injected bytes never affect loading, linking,
 * verification or execution — the E2E test loads and runs an injected class to
 * prove it.
 *
 * <p>What it costs an attacker:
 * <ul>
 *   <li><b>Signature / YARA-style scanners</b> that fingerprint a jar by hashing
 *       or pattern-matching raw class bytes see per-class random noise, so a
 *       fingerprint built from one build no longer matches the next.</li>
 *   <li><b>Naive / older parsers and home-grown static analysers</b> that walk
 *       every {@code attribute_info} (rather than skipping unknown ones by the
 *       declared length) trip over decoy names and junk payloads.</li>
 *   <li>The decoy names deliberately impersonate real toolchain attributes
 *       ({@code Scala}, {@code ScalaSig}, {@code TASTY}, {@code JADX-…}) so a
 *       reverser eyeballing the constant pool wastes time chasing them.</li>
 * </ul>
 *
 * <p>Honest scope: modern <em>structured</em> decompilers (CFR, Vineflower,
 * Procyon) skip unknown attributes by their length field and are unaffected —
 * pair this with the string / flow / anti-decompile passes for that. The decoy
 * names never collide with an attribute the JVM actually processes in the given
 * location, so class loading stays safe.
 *
 * <p>Opt-in via {@link dev.crazy.obf.config.ObfConfig#injectJunkAttributes}.
 */
public final class JunkByteInjectionTransformer implements Transformer {

    /**
     * Decoy attribute names. None of these are attributes the JVM processes at a
     * class / method / field location, so all are silently ignored at load time —
     * but each looks like it came from a real toolchain, so it burns reverser
     * attention. (Standard names the JVM DOES act on — Signature, Record,
     * BootstrapMethods, NestHost, Synthetic, … — are deliberately absent.)
     */
    private static final String[] DECOYS = {
        "Scala", "ScalaSig", "TASTY", "ScalaInlineInfo",
        "JADX-Debug", "org.eclipse.jdt", "com.intellij.rt",
        "Kotlinc", "SourceID", "DebugId", "CrazyMark",
    };

    private final PrintStream log;
    private int classAttrs, methodAttrs, fieldAttrs;

    public JunkByteInjectionTransformer(PrintStream log) { this.log = log == null ? System.out : log; }
    public JunkByteInjectionTransformer() { this(System.out); }

    @Override public String name() { return "byteinject"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().injectJunkAttributes) return;
        int chance = clamp(ctx.config().junkAttributeChance, 0, 100);
        if (chance <= 0) return;
        Random rng = new Random(ctx.seed() ^ 0xB17E10FFEEDL);

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            // Leave our own injected helpers (crazy/C, crazy/Indy, crazy/W, crazy/AD)
            // byte-clean so their bootstraps stay easy to reason about.
            if (cn.name.startsWith("crazy/")) continue;

            // 1..2 junk attributes on the class itself.
            int nClass = 1 + rng.nextInt(2);
            for (int i = 0; i < nClass; i++) {
                cn.attrs = add(cn.attrs, junk(rng));
                classAttrs++;
            }

            // A junk attribute on a random subset of concrete methods.
            if (cn.methods != null) {
                for (MethodNode m : cn.methods) {
                    if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                    if (rng.nextInt(100) >= chance) continue;
                    m.attrs = add(m.attrs, junk(rng));
                    methodAttrs++;
                }
            }

            // A junk attribute on a random subset of fields.
            if (cn.fields != null) {
                for (FieldNode f : cn.fields) {
                    if (rng.nextInt(100) >= chance) continue;
                    f.attrs = add(f.attrs, junk(rng));
                    fieldAttrs++;
                }
            }
        }

        log.println("[crazy] byteinject: " + classAttrs + " class, " + methodAttrs
            + " method, " + fieldAttrs + " field junk attribute(s)");
    }

    private static java.util.List<Attribute> add(java.util.List<Attribute> list, Attribute a) {
        if (list == null) list = new ArrayList<>();
        list.add(a);
        return list;
    }

    /** Mint one junk attribute: a decoy name (with a random suffix so names stay
     *  distinct across the class) plus a short random payload. */
    private static Attribute junk(Random rng) {
        String base = DECOYS[rng.nextInt(DECOYS.length)];
        // Suffix keeps names unique-ish without ever landing on a real
        // attribute name; kept short to limit constant-pool bloat.
        StringBuilder nm = new StringBuilder(base);
        int extra = rng.nextInt(4);
        for (int i = 0; i < extra; i++) nm.append((char) ('A' + rng.nextInt(26)));

        byte[] payload = new byte[8 + rng.nextInt(56)]; // 8..63 bytes
        rng.nextBytes(payload);
        return new JunkAttribute(nm.toString(), payload);
    }

    /**
     * A raw, opaque class-file attribute. ASM's {@link ClassWriter} writes it as
     * {@code attribute_name_index(u2) attribute_length(u4) info[length]} — the
     * length is taken from the {@link ByteVector} we return, so it is always
     * internally consistent (no length lie that the verifier could reject).
     */
    static final class JunkAttribute extends Attribute {
        private final byte[] payload;
        JunkAttribute(String type, byte[] payload) { super(type); this.payload = payload; }

        @Override
        protected ByteVector write(ClassWriter cw, byte[] code, int len, int maxStack, int maxLocals) {
            return new ByteVector().putByteArray(payload, 0, payload.length);
        }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
}
