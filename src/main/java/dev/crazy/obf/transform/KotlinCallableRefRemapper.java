package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import dev.crazy.obf.model.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.PrintStream;

/**
 * Remaps class names embedded inside Kotlin callable-reference signature
 * strings.
 *
 * `X::prop` / `X::fun` compile to a {@code kotlin.jvm.internal.*Reference*Impl}
 * construction whose 3rd constructor argument is a hardcoded String like
 * {@code "getSelectedTheme()Lcop/module/settings/impl/SelectorComponent;"}.
 *
 * ASM's ClassRemapper rewrites bytecode + descriptors but NOT string constants,
 * so after a rename this string still points at the OLD type while the rewritten
 * Kotlin @Metadata points at the NEW type. Kotlin reflection
 * (KDeclarationContainerImpl.findPropertyDescriptor) matches the embedded string
 * against the metadata and fails: "Property '…' not resolved".
 *
 * This pass scans every string constant in renamed-scope classes; if it looks
 * like a JVM member signature, it remaps every {@code L<internal>;} occurrence
 * through the global {@link Remapper} so the embedded string agrees with both
 * the metadata and the actual (renamed) JVM members.
 *
 * MUST run AFTER NameTransformer (needs the final class map) and relies on
 * StringEncryptionTransformer having skipped these strings (it does — see
 * StringEncryptionTransformer.isMemberSignature).
 */
public final class KotlinCallableRefRemapper implements Transformer {

    private final PrintStream log;
    private int patched;

    public KotlinCallableRefRemapper(PrintStream log) { this.log = log; }
    public KotlinCallableRefRemapper() { this(System.out); }

    @Override public String name() { return "kotlin-ref"; }

    @Override
    public void apply(ObfContext ctx) {
        Remapper r = ctx.remapper();
        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                for (AbstractInsnNode ins : m.instructions.toArray()) {
                    if (ins instanceof LdcInsnNode ldc && ldc.cst instanceof String s
                        && StringEncryptionTransformer.isMemberSignature(s)) {
                        String mapped = remapEmbeddedTypes(s, r);
                        if (!mapped.equals(s)) { ldc.cst = mapped; patched++; }
                    }
                }
            }
        }
        log.println("[crazy] kotlin-ref: patched " + patched + " callable-reference signature strings");
    }

    /**
     * Remaps the object types in a Kotlin callable-reference signature string.
     *
     * Format is {@code <memberName>(<argDesc>)<retDesc>} (or a bare descriptor).
     * The member name part can itself contain an uppercase 'L' (getLeapMode,
     * getLevel, getList, …), so a naive whole-string 'L'…';' scan mis-parses.
     * We split at the first '(' — everything before is the member name (no
     * type info, left untouched) and from '(' on is a well-formed JVM method
     * descriptor that {@link KotlinMetadataRemapper#remapDescriptor} parses
     * correctly (there 'L' only ever starts an object type).
     */
    public static String remapEmbeddedTypes(String s, Remapper r) {
        int paren = s.indexOf('(');
        if (paren >= 0) {
            String memberName = s.substring(0, paren);
            String descriptor = s.substring(paren); // (args...)ret
            return memberName + KotlinMetadataRemapper.remapDescriptor(descriptor, r);
        }
        // No '(' — could be a field-style "name:Ltype;" form. Remap only the
        // part after a ':' if present, else leave as-is (avoid name false hits).
        int colon = s.indexOf(':');
        if (colon >= 0 && s.indexOf('L', colon) >= 0) {
            return s.substring(0, colon + 1)
                 + KotlinMetadataRemapper.remapDescriptor(s.substring(colon + 1), r);
        }
        return s;
    }
}
