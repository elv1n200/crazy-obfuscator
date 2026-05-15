package dev.crazy.obf.transform;

import dev.crazy.obf.config.ObfConfig;
import dev.crazy.obf.model.ObfContext;
import dev.crazy.obf.model.Remapper;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renames classes, methods, and fields belonging to the user's own packages.
 *
 * Safety rules baked in:
 *   - Never rename anything outside `rootPackages`.
 *   - Never rename excluded classes/members (Fabric scanner result).
 *   - Never rename `<init>`/`<clinit>`/`main(String[])`.
 *   - Never rename Object overrides (equals/hashCode/toString/clone/finalize).
 *   - Never rename a method that overrides a method NOT in our jar (its name is
 *     fixed by the parent we cannot see).
 *   - Methods linked by inheritance share a single new name.
 *   - Fields named `serialVersionUID` are preserved.
 */
public final class NameTransformer implements Transformer {

    private static final Set<String> RESERVED_METHODS = Set.of(
        "<init>", "<clinit>", "main",
        "equals", "hashCode", "toString", "clone", "finalize",
        "readObject", "writeObject", "readResolve", "writeReplace", "readObjectNoData",
        "values", "valueOf" // enums
    );

    @Override public String name() { return "names"; }

    @Override
    public void plan(ObfContext ctx) {
        ObfConfig cfg = ctx.config();
        Remapper r = ctx.remapper();

        // 1. Classes
        if (cfg.renameClasses) {
            List<String> toRename = new ArrayList<>();
            for (String internal : ctx.contents().classes().keySet()) {
                if (!inScope(internal, cfg)) continue;
                if (ctx.exclusions().isClassExcluded(internal)) continue;
                toRename.add(internal);
            }
            // Process shallow classes before deep ones so an inner class's
            // outer already has its new name when we build the inner's name.
            // Preserving the Outer$Inner nesting (instead of flattening to an
            // unrelated top-level name) keeps generic signatures valid — ASM's
            // SignatureRemapper only emits correct `LOuter<..>.Inner;` output
            // when the inner's new name shares the outer's new prefix. This is
            // also what lets us NOT strip signatures (so GSON TypeToken works).
            toRename.sort(java.util.Comparator.comparingInt(s -> countChar(s, '$')));
            for (String internal : toRename) {
                String newName;
                String mappedAncestor = null;
                // Walk up the $-chain to the nearest ancestor that already has
                // a new name (shallower classes were processed first). Kotlin
                // emits synthetic segments (Foo$bar$1) where the immediate
                // "$"-parent is not a real class, so a single split is wrong —
                // we must find the closest REAL mapped enclosing class.
                int cut = internal.lastIndexOf('$');
                while (cut >= 0) {
                    String anc = internal.substring(0, cut);
                    if (r.classes.containsKey(anc)) { mappedAncestor = r.classes.get(anc); break; }
                    cut = internal.lastIndexOf('$', cut - 1);
                }
                if (mappedAncestor != null) {
                    newName = mappedAncestor + "$" + ctx.names().nextClass();
                } else {
                    newName = cfg.flattenPackages
                            ? cfg.flattenedPackage + "/" + ctx.names().nextClass()
                            : packageOf(internal) + "/" + ctx.names().nextClass();
                }
                r.classes.put(internal, newName);
            }
        }

        // 2. Fields
        if (cfg.renameFields) {
            for (ClassNode cn : ctx.contents().classes().values()) {
                if (!inScope(cn.name, cfg)) continue;
                if (ctx.exclusions().isClassExcluded(cn.name)) continue;
                if (cn.fields == null) continue;
                for (FieldNode f : cn.fields) {
                    if (!fieldRenameable(cn, f, ctx)) continue;
                    String nn = ctx.names().nextField();
                    r.fields.put(Remapper.fieldKey(cn.name, f.name, f.desc), nn);
                }
            }
        }

        // 3. Methods — grouped by inheritance
        if (cfg.renameMethods) {
            planMethods(ctx);
        }
    }

    @Override
    public void apply(ObfContext ctx) {
        Remapper r = ctx.remapper();
        Map<String, String> flat = new HashMap<>(r.classes);
        for (Map.Entry<String, String> e : r.fields.entrySet()) flat.put(e.getKey(), e.getValue());
        for (Map.Entry<String, String> e : r.methods.entrySet()) flat.put(e.getKey(), e.getValue());
        SimpleRemapper sr = new SimpleRemapper(flat);

        Map<String, ClassNode> newMap = new LinkedHashMap<>();
        for (ClassNode original : new ArrayList<>(ctx.contents().classes().values())) {
            ClassNode remapped = new ClassNode();
            original.accept(new ClassRemapper(remapped, sr));
            cleanupInnerClasses(remapped, sr);
            // Generic signatures are intentionally PRESERVED now. Nested-class
            // renaming keeps the Outer$Inner relationship, so ASM's
            // SignatureRemapper emits valid `LnewOuter<..>.newInner;` forms.
            // Preserving them is required for libraries that read generic type
            // info reflectively at runtime (e.g. GSON `new TypeToken<T>(){}`).
            newMap.put(remapped.name, remapped);
        }
        ctx.contents().classes().clear();
        ctx.contents().classes().putAll(newMap);

        // resources whose path matches a renamed class need to move (e.g. nested resources)
        // we don't try to remap arbitrary resources — strings inside fabric.mod.json must
        // still point to the original entry-point classes (which were excluded), so this is a no-op.
    }

    private void cleanupInnerClasses(ClassNode cn, SimpleRemapper sr) {
        if (cn.innerClasses == null) return;
        for (InnerClassNode inner : cn.innerClasses) {
            if (inner.outerName != null) {
                String mapped = sr.mapType(inner.outerName);
                if (mapped != null) inner.outerName = mapped;
            }
        }
    }

    // ---------------------------------------------------------------------
    // method planning

    private void planMethods(ObfContext ctx) {
        ObfConfig cfg = ctx.config();
        Set<String> ourClasses = ctx.contents().classes().keySet();

        // Build set of "untouchable" method keys: any (name+desc) where some class
        // in our jar inherits this signature from a class NOT in our jar.
        Set<String> lockedSignatures = new HashSet<>();
        for (ClassNode cn : ctx.contents().classes().values()) {
            for (String sup : ctx.hierarchy().allSupers(cn.name)) {
                if (ourClasses.contains(sup)) continue;
                // We don't have the parent's method list (it's outside our jar),
                // so we can't enumerate. Instead, lock the signature of any method
                // in `cn` that is also implemented elsewhere with the same name+desc
                // and whose access suggests override-ability.
                // -> handled per-method below.
                _ignore(sup);
            }
        }

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (!inScope(cn.name, cfg)) continue;
            if (ctx.exclusions().isClassExcluded(cn.name)) continue;
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (!methodRenameable(cn, m, ctx)) continue;
                // Skip if any non-our-jar supertype could conceivably declare this
                // method (we can't enumerate it, so heuristic: public/protected
                // non-static methods on classes that extend something outside our jar).
                if (potentiallyOverrides(cn, m, ourClasses, ctx)) continue;

                String nn = ctx.names().nextMethod();
                ctx.remapper().methods.put(Remapper.methodKey(cn.name, m.name, m.desc), nn);

                // propagate to subclasses inside our jar
                propagateMethodRename(cn.name, m.name, m.desc, nn, ctx);
            }
        }
    }

    private void propagateMethodRename(String root, String name, String desc, String newName, ObfContext ctx) {
        Set<String> subs = ctx.hierarchy().subclasses.get(root);
        if (subs == null) return;
        for (String sub : subs) {
            ClassNode subCn = ctx.contents().classes().get(sub);
            if (subCn != null && subCn.methods != null) {
                for (MethodNode m : subCn.methods) {
                    if (m.name.equals(name) && m.desc.equals(desc)) {
                        ctx.remapper().methods.put(Remapper.methodKey(sub, name, desc), newName);
                        break;
                    }
                }
            }
            propagateMethodRename(sub, name, desc, newName, ctx);
        }
    }

    private boolean potentiallyOverrides(ClassNode cn, MethodNode m, Set<String> ourClasses, ObfContext ctx) {
        if ((m.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) return false;
        if ((m.access & Opcodes.ACC_FINAL) != 0 && (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) return false;
        // Any supertype outside our jar -> assume it could declare this name+desc
        for (String sup : ctx.hierarchy().allSupers(cn.name)) {
            if (!ourClasses.contains(sup)) {
                // Object never declares user method shapes except the reserved ones (already filtered).
                // Anything else (MC/Fabric/library) -> be safe, don't rename.
                if (!"java/lang/Object".equals(sup)) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // predicates

    private boolean inScope(String internal, ObfConfig cfg) {
        if (cfg.rootPackages.isEmpty()) return true;
        for (String pkg : cfg.rootPackages) {
            String p = pkg.replace('.', '/');
            if (internal.equals(p) || internal.startsWith(p + "/")) return true;
        }
        return false;
    }

    /** Field names with framework meaning — never rename (Kotlin object/companion, enum synthetic). */
    private static final Set<String> RESERVED_FIELDS = Set.of(
        "serialVersionUID", "INSTANCE", "Companion", "$VALUES",
        "$assertionsDisabled", "CREATOR");

    private boolean fieldRenameable(ClassNode cn, FieldNode f, ObfContext ctx) {
        if (RESERVED_FIELDS.contains(f.name)) return false;
        if (ctx.exclusions().isMemberExcluded(cn.name, f.name, f.desc)) return false;
        if ((cn.access & Opcodes.ACC_ENUM) != 0 && (f.access & Opcodes.ACC_ENUM) != 0) return false;
        if (hasAnyAnnotation(f.visibleAnnotations) || hasAnyAnnotation(f.invisibleAnnotations)) return false;
        return true;
    }

    private boolean methodRenameable(ClassNode cn, MethodNode m, ObfContext ctx) {
        if (RESERVED_METHODS.contains(m.name)) return false;
        if (m.name.startsWith("lambda$")) return false;
        if (m.name.startsWith("access$")) return false; // synthetic accessor
        if (ctx.exclusions().isMemberExcluded(cn.name, m.name, m.desc)) return false;
        if ((m.access & Opcodes.ACC_NATIVE) != 0) return false;
        if (hasAnyAnnotation(m.visibleAnnotations) || hasAnyAnnotation(m.invisibleAnnotations)) return false;
        // main(String[])
        if ("main".equals(m.name) && "([Ljava/lang/String;)V".equals(m.desc)) return false;
        return true;
    }

    private boolean hasAnyAnnotation(List<?> l) { return l != null && !l.isEmpty(); }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static String packageOf(String internal) {
        int i = internal.lastIndexOf('/');
        return i < 0 ? "" : internal.substring(0, i);
    }

    private static void _ignore(Object o) {}
}
