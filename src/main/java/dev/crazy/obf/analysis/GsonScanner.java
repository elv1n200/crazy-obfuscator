package dev.crazy.obf.analysis;

import dev.crazy.obf.config.ExclusionRules;
import dev.crazy.obf.io.JarContents;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Protects GSON-serialized model classes from field renaming.
 *
 * GSON binds JSON keys to Java field NAMES by reflection (default
 * FieldNamingPolicy = IDENTITY, no getters). Rename a serialized class's
 * field and its persisted JSON silently stops loading — not a crash, a
 * data-loss bug that only shows up after a restart. Fields annotated
 * {@code @SerializedName}/{@code @Expose} are already safe (NameTransformer
 * skips all annotated fields) because the key is pinned by the annotation,
 * but plain fields are not.
 *
 * Same proven shape as {@link KotlinCallableRefScanner}: find the
 * serialization roots, then exclude — here we also take the transitive
 * closure, since GSON recurses into field types and superclasses.
 *
 * Roots detected:
 *   - subclasses of {@code com.google.gson.reflect.TypeToken} (the
 *     {@code new TypeToken<T>(){}} idiom) — type args read from the class
 *     generic signature
 *   - class constants near a {@code com.google.gson.Gson} /
 *     {@code TypeToken.get(Class)} call site
 *
 * Only runs when field renaming is enabled.
 */
public final class GsonScanner {

    private static final String TYPE_TOKEN = "com/google/gson/reflect/TypeToken";

    private final JarContents contents;
    private final ExclusionRules ex;
    private final PrintStream log;

    public GsonScanner(JarContents contents, ExclusionRules ex, PrintStream log) {
        this.contents = contents;
        this.ex = ex;
        this.log = log;
    }

    public void scan() {
        Set<String> seeds = new HashSet<>();

        for (ClassNode cn : contents.classes().values()) {
            // root: anonymous TypeToken subclass — pull types from its signature
            if (TYPE_TOKEN.equals(cn.superName)) {
                collectTypes(cn.signature, seeds);
            }
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                AbstractInsnNode prev2 = null, prev1 = null;
                for (AbstractInsnNode ins : m.instructions.toArray()) {
                    if (ins instanceof MethodInsnNode mi && isGsonSink(mi)) {
                        // a class constant in the last couple of insns is the
                        // (de)serialized type
                        collectFromLdc(prev1, seeds);
                        collectFromLdc(prev2, seeds);
                    }
                    prev2 = prev1;
                    prev1 = ins;
                }
            }
        }

        // transitive closure: GSON recurses into field types + superclass
        Set<String> done = new HashSet<>();
        Deque<String> work = new ArrayDeque<>(seeds);
        int classes = 0, fields = 0;
        while (!work.isEmpty()) {
            String internal = work.poll();
            if (!done.add(internal)) continue;
            ClassNode cn = contents.classes().get(internal);
            if (cn == null) continue;            // external (JDK/MC/lib) — stop
            classes++;
            if (cn.superName != null && contents.classes().containsKey(cn.superName)) {
                work.add(cn.superName);
            }
            if (cn.fields == null) continue;
            for (FieldNode f : cn.fields) {
                if ((f.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) continue; // statics not serialized
                ex.addMember(internal + "#" + f.name);
                fields++;
                // recurse into the field's declared + generic types
                collectTypeDesc(f.desc, work);
                collectTypes(f.signature, work);
            }
        }
        log.println("[crazy] gson-scan: protected " + fields + " field(s) across "
            + classes + " serialized class(es) from rename");
    }

    private static boolean isGsonSink(MethodInsnNode mi) {
        if (mi.owner.equals("com/google/gson/Gson")) {
            return mi.name.equals("fromJson") || mi.name.equals("toJson")
                || mi.name.equals("getAdapter") || mi.name.equals("fromJsonTree")
                || mi.name.equals("toJsonTree");
        }
        // TypeToken.get(Class) / TypeToken.get(Type)
        return mi.owner.equals(TYPE_TOKEN) && mi.name.equals("get");
    }

    private void collectFromLdc(AbstractInsnNode ins, java.util.Collection<String> out) {
        if (ins instanceof LdcInsnNode ld && ld.cst instanceof Type t && t.getSort() == Type.OBJECT) {
            String in = t.getInternalName();
            if (isOurs(in)) out.add(in);
        }
    }

    /** Pull every {@code Lcop/...;}-style object type out of a generic signature. */
    private void collectTypes(String signature, java.util.Collection<String> out) {
        if (signature == null) return;
        int i = 0;
        while ((i = signature.indexOf('L', i)) >= 0) {
            int end = i + 1;
            while (end < signature.length()) {
                char c = signature.charAt(end);
                if (c == ';' || c == '<') break;
                end++;
            }
            if (end <= signature.length()) {
                String in = signature.substring(i + 1, end);
                if (isOurs(in)) out.add(in);
            }
            i = end;
        }
    }

    private void collectTypeDesc(String desc, java.util.Collection<String> work) {
        if (desc == null) return;
        int i = 0;
        while ((i = desc.indexOf('L', i)) >= 0) {
            int semi = desc.indexOf(';', i);
            if (semi < 0) break;
            String in = desc.substring(i + 1, semi);
            if (isOurs(in)) work.add(in);
            i = semi + 1;
        }
    }

    /** Only our own classes are renameable in the first place. */
    private boolean isOurs(String internal) {
        return contents.classes().containsKey(internal);
    }
}
