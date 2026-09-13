package dev.crazy.obf.transform;

import dev.crazy.obf.config.ObfConfig;
import dev.crazy.obf.model.ObfContext;
import dev.crazy.obf.model.Remapper;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
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
                    // Inherited-field access sites can carry a subclass owner
                    // (e.g. GETFIELD Sub.field for a field declared in Super).
                    // Register the same new name under every subclass that does
                    // NOT declare its own field of that name+desc (shadowing),
                    // so those references remap consistently.
                    propagateFieldRename(cn.name, f.name, f.desc, nn, ctx);
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
        // IMPORTANT: use an adapter that consults our own Remapper with the exact
        // key formats it was populated with (methodKey/fieldKey). ASM's
        // SimpleRemapper builds its lookup key as owner.name+desc for methods and
        // owner.name for fields — neither matches our keys, so with SimpleRemapper
        // method/field renames were silently dropped (only class renames applied)
        // while the mapping file still claimed them. This adapter fixes that.
        NameRemapper sr = new NameRemapper(r);

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

    private void cleanupInnerClasses(ClassNode cn, org.objectweb.asm.commons.Remapper sr) {
        if (cn.innerClasses == null) return;
        for (InnerClassNode inner : cn.innerClasses) {
            if (inner.outerName != null) {
                String mapped = sr.mapType(inner.outerName);
                if (mapped != null) inner.outerName = mapped;
            }
        }
    }

    /**
     * Adapter from ASM's {@link org.objectweb.asm.commons.Remapper} onto our
     * {@link Remapper} model, using the SAME key formats our plan populated it
     * with. Definitions and references (owners/names/descriptors/signatures) all
     * flow through here, so a renamed member is renamed consistently everywhere.
     */
    private static final class NameRemapper extends org.objectweb.asm.commons.Remapper {
        private final Remapper r;
        NameRemapper(Remapper r) { this.r = r; }

        @Override public String map(String internalName) { return r.mapClass(internalName); }

        @Override public String mapMethodName(String owner, String name, String descriptor) {
            if (!name.isEmpty() && name.charAt(0) == '<') return name; // <init>/<clinit>
            return r.mapMethod(owner, name, descriptor);
        }

        @Override public String mapInvokeDynamicMethodName(String name, String descriptor) {
            return name; // indy call-site names are synthetic; leave untouched
        }

        @Override public String mapFieldName(String owner, String name, String descriptor) {
            return r.mapField(owner, name, descriptor);
        }

        @Override public String mapRecordComponentName(String owner, String name, String descriptor) {
            // keep a record's component name in lockstep with its backing field
            return r.mapField(owner, name, descriptor);
        }

        @Override public String mapAnnotationAttributeName(String descriptor, String name) {
            return name; // annotation element names are part of the annotation's API
        }
    }

    // ---------------------------------------------------------------------
    // method planning

    /**
     * Group-based method-name planning.
     *
     * <p>Virtual dispatch means every method in an override chain (a method and
     * the same {@code name+desc} declared up/down the hierarchy) MUST get the
     * same new name, and the whole chain must be renamed all-or-nothing — if any
     * link is unrenameable (reserved, annotated, excluded, or overrides a type
     * outside our jar) the entire group stays put, otherwise a subclass override
     * would stop overriding (→ {@code AbstractMethodError} or wrong dispatch).
     *
     * <p>Instance methods are unioned into override groups; static methods carry
     * no dispatch so each is renamed independently, then propagated to inheriting
     * subclasses so inherited {@code INVOKESTATIC Sub.foo} sites remap too.
     */
    private void planMethods(ObfContext ctx) {
        ObfConfig cfg = ctx.config();
        Set<String> ourClasses = ctx.contents().classes().keySet();

        // ---- instance methods: union-find over override chains -------------
        Map<String, String> parent = new HashMap<>();          // union-find
        Map<String, ClassNode> nodeClass = new HashMap<>();
        Map<String, MethodNode> nodeMethod = new HashMap<>();

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.name.charAt(0) == '<') continue;
                if ((m.access & Opcodes.ACC_STATIC) != 0) continue;
                String key = Remapper.methodKey(cn.name, m.name, m.desc);
                parent.put(key, key);
                nodeClass.put(key, cn);
                nodeMethod.put(key, m);
            }
        }
        // Link each declared instance method to the same name+desc method
        // declared in any of its supertypes that live in our jar.
        for (ClassNode cn : ctx.contents().classes().values()) {
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.name.charAt(0) == '<' || (m.access & Opcodes.ACC_STATIC) != 0) continue;
                String childKey = Remapper.methodKey(cn.name, m.name, m.desc);
                for (String sup : ctx.hierarchy().allSupers(cn.name)) {
                    if (!ourClasses.contains(sup)) continue;
                    String supKey = Remapper.methodKey(sup, m.name, m.desc);
                    if (parent.containsKey(supKey)) union(parent, childKey, supKey);
                }
            }
        }
        // Assemble groups and rename each group all-or-nothing.
        Map<String, List<String>> groups = new HashMap<>();
        for (String k : parent.keySet()) groups.computeIfAbsent(find(parent, k), x -> new ArrayList<>()).add(k);

        for (List<String> members : groups.values()) {
            boolean locked = false;
            for (String k : members) {
                ClassNode cn = nodeClass.get(k);
                MethodNode m = nodeMethod.get(k);
                if (!inScope(cn.name, cfg)
                    || ctx.exclusions().isClassExcluded(cn.name)
                    || !methodRenameable(cn, m, ctx)
                    || overridesOutsideJar(cn, ourClasses, ctx)) {
                    locked = true;
                    break;
                }
            }
            if (locked) continue;
            String nn = ctx.names().nextMethod();
            for (String k : members) ctx.remapper().methods.put(k, nn);
        }

        // ---- static methods: independent, propagated to inheritors ----------
        for (ClassNode cn : ctx.contents().classes().values()) {
            if (!inScope(cn.name, cfg)) continue;
            if (ctx.exclusions().isClassExcluded(cn.name)) continue;
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                if (m.name.charAt(0) == '<') continue;
                if ((m.access & Opcodes.ACC_STATIC) == 0) continue;
                if (!methodRenameable(cn, m, ctx)) continue;
                String key = Remapper.methodKey(cn.name, m.name, m.desc);
                if (ctx.remapper().methods.containsKey(key)) continue;
                String nn = ctx.names().nextMethod();
                ctx.remapper().methods.put(key, nn);
                propagateStaticRename(cn.name, m.name, m.desc, nn, ctx);
            }
        }
    }

    /** True if instance methods on {@code cn} could override something we cannot
     *  see (any supertype outside our jar other than Object) — such a chain is
     *  anchored to a fixed external name and must not be renamed. */
    private boolean overridesOutsideJar(ClassNode cn, Set<String> ourClasses, ObfContext ctx) {
        for (String sup : ctx.hierarchy().allSupers(cn.name)) {
            if (!ourClasses.contains(sup) && !"java/lang/Object".equals(sup)) return true;
        }
        return false;
    }

    /** Copy a static-method rename to subclasses that inherit it (do not declare
     *  their own same name+desc), so inherited-access call sites remap too. */
    private void propagateStaticRename(String root, String name, String desc, String newName, ObfContext ctx) {
        Set<String> subs = ctx.hierarchy().subclasses.get(root);
        if (subs == null) return;
        for (String sub : subs) {
            ClassNode subCn = ctx.contents().classes().get(sub);
            boolean declaresOwn = false;
            if (subCn != null && subCn.methods != null) {
                for (MethodNode m : subCn.methods) {
                    if (m.name.equals(name) && m.desc.equals(desc)) { declaresOwn = true; break; }
                }
            }
            if (!declaresOwn) {
                ctx.remapper().methods.putIfAbsent(Remapper.methodKey(sub, name, desc), newName);
                propagateStaticRename(sub, name, desc, newName, ctx);
            }
        }
    }

    /** Copy a field rename to subclasses that inherit it (do not shadow it), so
     *  inherited-access sites carrying a subclass owner remap consistently. */
    private void propagateFieldRename(String root, String name, String desc, String newName, ObfContext ctx) {
        Set<String> subs = ctx.hierarchy().subclasses.get(root);
        if (subs == null) return;
        for (String sub : subs) {
            ClassNode subCn = ctx.contents().classes().get(sub);
            boolean shadows = false;
            if (subCn != null && subCn.fields != null) {
                for (FieldNode f : subCn.fields) {
                    if (f.name.equals(name) && f.desc.equals(desc)) { shadows = true; break; }
                }
            }
            if (!shadows) {
                ctx.remapper().fields.putIfAbsent(Remapper.fieldKey(sub, name, desc), newName);
                propagateFieldRename(sub, name, desc, newName, ctx);
            }
        }
    }

    // ---- union-find helpers (over method-key strings) ---------------------

    private static String find(Map<String, String> parent, String x) {
        String root = x;
        while (!root.equals(parent.get(root))) root = parent.get(root);
        // path compression
        while (!x.equals(root)) { String next = parent.get(x); parent.put(x, root); x = next; }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
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

}
