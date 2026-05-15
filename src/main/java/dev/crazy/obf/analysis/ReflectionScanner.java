package dev.crazy.obf.analysis;

import dev.crazy.obf.config.ExclusionRules;
import dev.crazy.obf.io.JarContents;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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
            // B5: constant strings that reach a sink through locals / copies
            scanDataflow(cn, internalNames);
        }
    }

    /**
     * Source-preserving interpreter: unlike the stock SourceInterpreter (which
     * resets a value's source on every load/store/dup), this keeps the
     * ORIGINAL producer, so a constant tracks through
     * {@code String x = "a.b.C"; ... Class.forName(x)} and dup/swap.
     */
    private static final class PropInterp
            extends org.objectweb.asm.tree.analysis.SourceInterpreter {
        PropInterp() { super(org.objectweb.asm.Opcodes.ASM9); }
        @Override
        public org.objectweb.asm.tree.analysis.SourceValue copyOperation(
                AbstractInsnNode insn, org.objectweb.asm.tree.analysis.SourceValue value) {
            return value; // preserve original source through ?LOAD/?STORE/DUP/SWAP
        }
    }

    /**
     * Flow-sensitive pass: for every reflection sink, resolve the class/member
     * name argument back to its producing instruction(s). If they are all
     * constant string LDCs, apply the same exclusion as the literal case —
     * now also when the literal was stashed in a local first. Sound: it can
     * only ADD exclusions (never renames more), so false positives are safe.
     */
    private void scanDataflow(ClassNode cn, Set<String> internalNames) {
        if (cn.methods == null) return;
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
            try {
                frames = new org.objectweb.asm.tree.analysis.Analyzer<>(new PropInterp())
                        .analyze(cn.name, m);
            } catch (Throwable t) {
                continue; // unanalyzable — skip, never throw
            }
            AbstractInsnNode[] insns = m.instructions.toArray();
            for (int i = 0; i < insns.length; i++) {
                if (!(insns[i] instanceof MethodInsnNode min)) continue;
                String sink = min.owner + "." + min.name;
                boolean cls = CLASS_SINKS.contains(sink);
                boolean mem = MEMBER_SINKS.contains(sink);
                if (!cls && !mem) continue;
                var fr = frames[i];
                if (fr == null) continue;

                // locate the first String argument of the sink on the stack
                Type[] at = Type.getArgumentTypes(min.desc);
                int strArg = -1;
                for (int a = 0; a < at.length; a++) {
                    if ("java/lang/String".equals(at[a].getInternalName())) { strArg = a; break; }
                }
                if (strArg < 0) continue;
                int depthFromTop = 0;
                for (int a = at.length - 1; a > strArg; a--) depthFromTop += at[a].getSize();
                int idx = fr.getStackSize() - 1 - depthFromTop;
                if (idx < 0 || idx >= fr.getStackSize()) continue;
                var sv = fr.getStack(idx);

                String cst = soleConstant(sv);
                if (cst == null) continue;
                if (cls) {
                    String internal = cst.replace('.', '/');
                    if (internalNames.contains(internal)) {
                        ex.addClass(internal);
                        ex.preserveString(cst);
                        log.println("[crazy] excluding (dataflow Class.forName): " + internal);
                    }
                } else {
                    ex.addMember("**#" + cst);
                    ex.preserveString(cst);
                }
            }
        }
    }

    /** Returns the constant string iff every producer is an LDC of the same String. */
    private static String soleConstant(org.objectweb.asm.tree.analysis.SourceValue sv) {
        if (sv == null || sv.insns == null || sv.insns.isEmpty()) return null;
        String only = null;
        for (AbstractInsnNode p : sv.insns) {
            if (!(p instanceof LdcInsnNode ld) || !(ld.cst instanceof String s)) return null;
            if (only == null) only = s;
            else if (!only.equals(s)) return null;
        }
        return only;
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
