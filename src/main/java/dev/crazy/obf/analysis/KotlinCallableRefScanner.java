package dev.crazy.obf.analysis;

import dev.crazy.obf.config.ExclusionRules;
import dev.crazy.obf.io.JarContents;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.PrintStream;

/**
 * Finds Kotlin callable references ({@code X::prop}, {@code X::fun}) and
 * excludes the referenced JVM members from renaming.
 *
 * Kotlin compiles {@code X::leapMode} to a construction like:
 *
 *   new PropertyReference1Impl(owner, "leapMode",
 *                              "getLeapMode()Lcop/.../SelectorComponent;", flags)
 *   Reflection.property1(ref)
 *
 * At runtime Kotlin reflection matches that hardcoded signature string against
 * the class metadata and the actual JVM member. If we rename the JVM method
 * the three views drift apart (we already saw the 7-crash marathon). Rather
 * than remap the member name in bytecode + metadata + the embedded string
 * (needs fragile owner resolution in three places), we do what real
 * obfuscators do: treat the reference target as a reflection target and keep
 * its name. Everything NOT reflected still gets renamed.
 *
 * Member name is parsed from the signature string (text before '('). Owner is
 * traced from the nearest preceding class constant feeding the reference
 * constructor; if it can't be resolved we fall back to a name-only exclusion
 * across all classes (safe, just slightly weaker obfuscation).
 */
public final class KotlinCallableRefScanner {

    private final JarContents contents;
    private final ExclusionRules ex;
    private final PrintStream log;
    private int excluded;

    public KotlinCallableRefScanner(JarContents contents, ExclusionRules ex, PrintStream log) {
        this.contents = contents;
        this.ex = ex;
        this.log = log;
    }

    public void scan() {
        for (ClassNode cn : contents.classes().values()) {
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                AbstractInsnNode[] arr = m.instructions.toArray();
                for (int i = 0; i < arr.length; i++) {
                    if (!isReferenceSink(arr[i])) continue;
                    // Walk back a window collecting the signature string and an
                    // owner class constant.
                    String sig = null, ownerInternal = null;
                    for (int j = i - 1, w = 0; j >= 0 && w < 18; j--, w++) {
                        AbstractInsnNode p = arr[j];
                        if (p instanceof LdcInsnNode ld) {
                            if (sig == null && ld.cst instanceof String s && isCallableSig(s)) {
                                sig = s;
                            } else if (ownerInternal == null && ld.cst instanceof Type t
                                       && (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY)) {
                                ownerInternal = t.getSort() == Type.OBJECT
                                        ? t.getInternalName() : null;
                            }
                        }
                        if (p instanceof MethodInsnNode mi
                            && mi.owner.equals("kotlin/jvm/internal/Reflection")
                            && (mi.name.equals("getOrCreateKotlinClass")
                             || mi.name.equals("getOrCreateKotlinPackage"))) {
                            // owner class is the LDC right before this call
                            for (int k = j - 1; k >= 0 && k > j - 4; k--) {
                                if (arr[k] instanceof LdcInsnNode l2 && l2.cst instanceof Type t2
                                    && t2.getSort() == Type.OBJECT) {
                                    ownerInternal = t2.getInternalName();
                                    break;
                                }
                            }
                        }
                        if (sig != null && ownerInternal != null) break;
                    }
                    if (sig == null) continue;
                    excludeFromSignature(ownerInternal, sig);
                }
            }
        }
        log.println("[crazy] kotlin-ref-scan: excluded " + excluded + " reflected member name(s) from rename");
    }

    private void excludeFromSignature(String owner, String sig) {
        int paren = sig.indexOf('(');
        if (paren <= 0) return;
        String name = sig.substring(0, paren);
        ex.preserveString(sig);

        // Exclude the member itself; for a Kotlin property getter also keep the
        // matching setter and the backing field (the Kotlin property name).
        addMember(owner, name);
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            String cap = name.substring(3);
            addMember(owner, "set" + cap);
            addMember(owner, Character.toLowerCase(cap.charAt(0)) + cap.substring(1)); // backing field
        }
    }

    private void addMember(String owner, String name) {
        if (owner != null && !owner.isEmpty()) ex.addMember(owner + "#" + name);
        else ex.addMember("**#" + name); // owner unknown -> safe over-exclusion
        excluded++;
    }

    private static boolean isReferenceSink(AbstractInsnNode ins) {
        if (ins instanceof MethodInsnNode mi) {
            if (mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.name.equals("<init>")
                && mi.owner.startsWith("kotlin/jvm/internal/")
                && mi.owner.contains("Reference")) return true;
            if (mi.getOpcode() == Opcodes.INVOKESTATIC
                && mi.owner.equals("kotlin/jvm/internal/Reflection")) {
                String n = mi.name;
                return n.startsWith("property") || n.startsWith("mutableProperty")
                    || n.equals("function") || n.startsWith("function")
                    || n.startsWith("localProperty");
            }
        }
        return false;
    }

    /** A Kotlin callable-reference signature: "name(args)ret" or "getX()Ltype;". */
    private static boolean isCallableSig(String s) {
        int lp = s.indexOf('(');
        return lp > 0 && s.indexOf(')', lp) > lp;
    }
}
