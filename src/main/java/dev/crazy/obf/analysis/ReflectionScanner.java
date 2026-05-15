package dev.crazy.obf.analysis;

import dev.crazy.obf.config.ExclusionRules;
import dev.crazy.obf.io.JarContents;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.PrintStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Heuristic — scans every method for string literals passed into reflection sinks
 * (Class.forName, getDeclaredField, getDeclaredMethod, getField, getMethod, etc.)
 * and adds whatever target class/field/method it can guess to the exclusion list.
 *
 * This catches the most common cases (GSON field references, plugin loaders,
 * Mixin sub-targets, config reflection) without dataflow analysis.
 */
public final class ReflectionScanner {

    private static final Set<String> CLASS_SINKS = Set.of(
        "java/lang/Class.forName",
        "java/lang/ClassLoader.loadClass"
    );
    private static final Set<String> MEMBER_SINKS = Set.of(
        "java/lang/Class.getDeclaredField",
        "java/lang/Class.getDeclaredMethod",
        "java/lang/Class.getField",
        "java/lang/Class.getMethod"
    );

    private final JarContents contents;
    private final ExclusionRules ex;
    private final PrintStream log;

    public ReflectionScanner(JarContents contents, ExclusionRules ex, PrintStream log) {
        this.contents = contents;
        this.ex = ex;
        this.log = log;
    }

    public void scan() {
        Set<String> internalNames = new HashSet<>(contents.classes().keySet());
        for (ClassNode cn : contents.classes().values()) {
            scanAnnotations(cn);
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                AbstractInsnNode prev = null;
                for (AbstractInsnNode ins : m.instructions.toArray()) {
                    if (ins.getOpcode() == Opcodes.INVOKESTATIC || ins.getOpcode() == Opcodes.INVOKEVIRTUAL
                     || ins.getOpcode() == Opcodes.INVOKESPECIAL || ins.getOpcode() == Opcodes.INVOKEINTERFACE) {
                        MethodInsnNode min = (MethodInsnNode) ins;
                        String sink = min.owner + "." + min.name;
                        if (CLASS_SINKS.contains(sink) && prev instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                            String internal = s.replace('.', '/');
                            if (internalNames.contains(internal)) {
                                ex.addClass(internal);
                                ex.preserveString(s);
                                log.println("[crazy] excluding (Class.forName): " + internal);
                            }
                        } else if (MEMBER_SINKS.contains(sink) && prev instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                            ex.addMember("**#" + s);
                            ex.preserveString(s);
                        }
                        // class literal (LDC class).getField etc. — we can resolve the owner
                        if (MEMBER_SINKS.contains(sink) && prev instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                            // covered above
                        }
                    }
                    prev = ins;
                }
            }
            // .class literal followed by reflection lookup — resolve owner
            scanClassLiteralReflection(cn);
        }
    }

    /**
     * Looks for the GSON / Jackson / FastJson annotations that fix a field's
     * serialized name. Preserves both the field name and the literal string.
     *
     *   @SerializedName("foo")  // com.google.gson
     *   @JsonProperty("foo")    // jackson
     *   @JSONField(name="foo")  // fastjson
     *   @Expose                 // gson (uses original name; locks field)
     */
    private void scanAnnotations(ClassNode cn) {
        if (cn.fields == null) return;
        for (FieldNode f : cn.fields) {
            String fixed = explicitName(f.visibleAnnotations);
            if (fixed == null) fixed = explicitName(f.invisibleAnnotations);
            if (fixed != null) {
                ex.preserveString(fixed);
                // The annotation pins the JSON key, not the field name — so the
                // field is free to be renamed. We do nothing else here.
                continue;
            }
            // @Expose / GSON default: serialization uses field name directly. Lock both.
            if (hasAny(f.visibleAnnotations, "Lcom/google/gson/annotations/Expose;")
             || hasAny(f.invisibleAnnotations, "Lcom/google/gson/annotations/Expose;")) {
                ex.addMember(cn.name + "#" + f.name);
                ex.preserveString(f.name);
                log.println("[crazy] @Expose: locking field " + cn.name + "#" + f.name);
            }
        }
    }

    private static String explicitName(List<AnnotationNode> anns) {
        if (anns == null) return null;
        for (AnnotationNode a : anns) {
            if (a.desc == null) continue;
            boolean match = a.desc.equals("Lcom/google/gson/annotations/SerializedName;")
                          || a.desc.equals("Lcom/fasterxml/jackson/annotation/JsonProperty;")
                          || a.desc.equals("Lcom/alibaba/fastjson/annotation/JSONField;");
            if (!match) continue;
            if (a.values == null) continue;
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                String key = String.valueOf(a.values.get(i));
                Object v = a.values.get(i + 1);
                if ("value".equals(key) || "name".equals(key)) {
                    if (v instanceof String s) return s;
                }
            }
        }
        return null;
    }

    private static boolean hasAny(List<AnnotationNode> anns, String desc) {
        if (anns == null) return false;
        for (AnnotationNode a : anns) if (desc.equals(a.desc)) return true;
        return false;
    }

    /**
     * Matches the pattern: LDC <class>; ASTORE _; ALOAD _; LDC "name"; INVOKEVIRTUAL Class.getDeclaredField
     * by walking simply: if we see an LDC class followed within 8 insns by a member sink with a literal string,
     * assume the owner is that class.
     */
    private void scanClassLiteralReflection(ClassNode cn) {
        if (cn.methods == null) return;
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) continue;
            AbstractInsnNode[] arr = m.instructions.toArray();
            for (int i = 0; i < arr.length; i++) {
                if (!(arr[i] instanceof LdcInsnNode classLdc)) continue;
                if (!(classLdc.cst instanceof org.objectweb.asm.Type t) || t.getSort() != org.objectweb.asm.Type.OBJECT) continue;
                String owner = t.getInternalName();
                // walk ahead a few insns for the member call
                for (int j = i + 1; j < Math.min(arr.length, i + 12); j++) {
                    if (arr[j] instanceof MethodInsnNode mi) {
                        String sink = mi.owner + "." + mi.name;
                        if (!MEMBER_SINKS.contains(sink)) continue;
                        // find the literal string between i and j
                        for (int k = j - 1; k > i; k--) {
                            if (arr[k] instanceof LdcInsnNode ld && ld.cst instanceof String s) {
                                if (contents.classes().containsKey(owner)) {
                                    ex.addMember(owner + "#" + s);
                                    ex.preserveString(s);
                                    log.println("[crazy] reflection: locking " + owner + "#" + s);
                                }
                                break;
                            }
                        }
                        break;
                    }
                }
            }
        }
    }
}
