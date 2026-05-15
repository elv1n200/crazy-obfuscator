package dev.crazy.obf.analysis;

import dev.crazy.obf.config.ExclusionRules;
import dev.crazy.obf.io.JarContents;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Excludes our-own classes that {@code @Mixin} code references from renaming.
 *
 * Mixin RELOCATES a mixin's handler/inject code into the target (usually a
 * Minecraft) class at load time. If that relocated code touches one of the
 * mod's own helper classes and we have renamed / repackaged it, the relocated
 * code can no longer resolve or access it — e.g.
 *
 *   IllegalAccessError: failed to access class a.acc
 *       from class net.minecraft.class_465.handler$..$onSlotClicked
 *
 * (the helper got moved to package {@code a} / lost its package-relative
 * accessibility once relocated into {@code net.minecraft}).
 *
 * The mixin classes themselves are already no-touch (FabricScanner). This
 * adds the transitive piece: every own-jar type a mixin class references
 * (supertypes, field/method descriptors, instruction operands, indy args)
 * is excluded from renaming so it stays in its original package with its
 * original access — exactly what the relocated code expects. Same proven
 * shape as the Kotlin / GSON scanners; runs only when renaming is on.
 */
public final class MixinReferenceScanner {

    private static final String MIXIN_ANN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private final JarContents contents;
    private final ExclusionRules ex;
    private final PrintStream log;
    private int excluded;

    public MixinReferenceScanner(JarContents contents, ExclusionRules ex, PrintStream log) {
        this.contents = contents;
        this.ex = ex;
        this.log = log;
    }

    public void scan() {
        Set<String> ours = contents.classes().keySet();
        Set<String> protect = new HashSet<>();

        for (ClassNode cn : contents.classes().values()) {
            if (!isMixin(cn)) continue;
            collectRefs(cn, ours, protect);
        }
        // also protect a one-level closure: field types of protected classes
        // (mixin code often walks into the helper's fields)
        Set<String> extra = new HashSet<>();
        for (String c : protect) {
            ClassNode cn = contents.classes().get(c);
            if (cn == null || cn.fields == null) continue;
            for (FieldNode f : cn.fields) {
                addType(f.desc, ours, extra);
                addSig(f.signature, ours, extra);
            }
        }
        protect.addAll(extra);

        for (String c : protect) {
            if (ex.isClassNoTouch(c)) continue;       // already fully protected
            ex.addClass(c);                            // keep name + package + access
            excluded++;
        }
        log.println("[crazy] mixin-ref-scan: excluded " + excluded
            + " mixin-referenced class(es) from rename");
    }

    private static boolean isMixin(ClassNode cn) {
        return hasAnn(cn.visibleAnnotations) || hasAnn(cn.invisibleAnnotations);
    }
    private static boolean hasAnn(java.util.List<AnnotationNode> anns) {
        if (anns == null) return false;
        for (AnnotationNode a : anns) if (MIXIN_ANN.equals(a.desc)) return true;
        return false;
    }

    private void collectRefs(ClassNode cn, Set<String> ours, Set<String> out) {
        addName(cn.superName, ours, out);
        if (cn.interfaces != null) for (String i : cn.interfaces) addName(i, ours, out);
        addSig(cn.signature, ours, out);

        if (cn.fields != null) for (FieldNode f : cn.fields) {
            addType(f.desc, ours, out);
            addSig(f.signature, ours, out);
        }
        if (cn.methods != null) for (MethodNode m : cn.methods) {
            addType(m.desc, ours, out);
            addSig(m.signature, ours, out);
            if (m.exceptions != null) for (String e : m.exceptions) addName(e, ours, out);
            if (m.instructions == null) continue;
            for (AbstractInsnNode p : m.instructions.toArray()) {
                if (p instanceof MethodInsnNode mi) { addName(mi.owner, ours, out); addType(mi.desc, ours, out); }
                else if (p instanceof FieldInsnNode fi) { addName(fi.owner, ours, out); addType(fi.desc, ours, out); }
                else if (p instanceof TypeInsnNode ti) addName(ti.desc, ours, out);
                else if (p instanceof MultiANewArrayInsnNode ma) addType(ma.desc, ours, out);
                else if (p instanceof LdcInsnNode ld && ld.cst instanceof Type t) addType(t.getDescriptor(), ours, out);
                else if (p instanceof InvokeDynamicInsnNode id) {
                    addType(id.desc, ours, out);
                    addHandle(id.bsm, ours, out);
                    if (id.bsmArgs != null) for (Object a : id.bsmArgs) {
                        if (a instanceof Type t) addType(t.getDescriptor(), ours, out);
                        else if (a instanceof Handle h) addHandle(h, ours, out);
                    }
                }
            }
        }
    }

    private void addHandle(Handle h, Set<String> ours, Set<String> out) {
        if (h == null) return;
        addName(h.getOwner(), ours, out);
        addType(h.getDesc(), ours, out);
    }

    /** internal name (may be array form "[Lx;" or plain "x"). */
    private void addName(String name, Set<String> ours, Set<String> out) {
        if (name == null) return;
        if (name.startsWith("[")) { addType(name, ours, out); return; }
        if (ours.contains(name)) out.add(name);
    }

    /** scan every object type inside a descriptor. */
    private void addType(String desc, Set<String> ours, Set<String> out) {
        if (desc == null) return;
        int i = 0;
        while ((i = desc.indexOf('L', i)) >= 0) {
            int semi = desc.indexOf(';', i);
            if (semi < 0) break;
            String n = desc.substring(i + 1, semi);
            if (ours.contains(n)) out.add(n);
            i = semi + 1;
        }
    }

    /** scan a generic signature for object types (':' / '<' / ';' delimited). */
    private void addSig(String sig, Set<String> ours, Set<String> out) {
        if (sig == null) return;
        int i = 0;
        while ((i = sig.indexOf('L', i)) >= 0) {
            int end = i + 1;
            while (end < sig.length()) {
                char c = sig.charAt(end);
                if (c == ';' || c == '<') break;
                end++;
            }
            String n = sig.substring(i + 1, end);
            if (ours.contains(n)) out.add(n);
            i = end;
        }
    }
}
