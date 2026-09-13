package dev.crazy.obf.transform;

import dev.crazy.obf.config.ObfConfig;
import dev.crazy.obf.model.ObfContext;
import dev.crazy.obf.model.Remapper;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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
                    // Inherited-field access sites (GETFIELD Sub.field where field
                    // is declared in Super) are resolved up the hierarchy by
                    // NameRemapper, so no propagation is needed here.
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
        // IMPORTANT: use an adapter that consults our own Remapper with the exact
        // key formats it was populated with (methodKey/fieldKey). ASM's
        // SimpleRemapper builds its lookup key as owner.name+desc for methods and
        // owner.name for fields — neither matches our keys, so with SimpleRemapper
        // method/field renames were silently dropped (only class renames applied)
        // while the mapping file still claimed them. This adapter fixes that.
        NameRemapper sr = new NameRemapper(ctx);

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
        private final ObfContext ctx;
        private final Remapper r;
        private final Map<String, java.util.Set<String>> superCache = new HashMap<>();
        NameRemapper(ObfContext ctx) { this.ctx = ctx; this.r = ctx.remapper(); }

        @Override public String map(String internalName) { return r.mapClass(internalName); }

        @Override public String mapMethodName(String owner, String name, String descriptor) {
            if (!name.isEmpty() && name.charAt(0) == '<') return name; // <init>/<clinit>
            // Direct hit: this owner declares the (renamed) member.
            String v = r.methods.get(Remapper.methodKey(owner, name, descriptor));
            if (v != null) return v;
            // Inherited call site: owner references a method declared in a
            // supertype (e.g. INVOKEVIRTUAL Sub.foo where foo lives in Super).
            // Resolve up the ORIGINAL hierarchy so the call remaps to match the
            // renamed definition. All declarations of one signature reachable
            // here share a single new name (see the grouping), so the first hit
            // is unambiguous.
            for (String sup : supers(owner)) {
                v = r.methods.get(Remapper.methodKey(sup, name, descriptor));
                if (v != null) return v;
            }
            return name;
        }

        @Override public String mapInvokeDynamicMethodName(String name, String descriptor) {
            // A lambda / SAM-conversion call site (LambdaMetafactory) uses the
            // functional-interface METHOD NAME as the invokedynamic name, and the
            // indy's return type is that interface. If we renamed the SAM, the
            // generated lambda must use the new name or it won't implement the
            // renamed interface -> AbstractMethodError at first use. Resolve the
            // renamed SAM by (return-type interface + name), walking supertypes.
            // NB: ASM also routes CONSTANT_Dynamic constants (our condy string /
            // numeric hiding) through here, whose descriptor is a TYPE descriptor
            // (e.g. "Ljava/lang/String;", "I"), not a method one — skip those.
            if (descriptor.isEmpty() || descriptor.charAt(0) != '(') return name;
            Type ret = Type.getReturnType(descriptor);
            if (ret.getSort() != Type.OBJECT) return name;
            String iface = ret.getInternalName();
            String nn = sam(iface, name);
            if (nn != null) return nn;
            for (String sup : supers(iface)) {
                nn = sam(sup, name);
                if (nn != null) return nn;
            }
            return name;
        }

        @Override public String mapFieldName(String owner, String name, String descriptor) {
            String v = r.fields.get(Remapper.fieldKey(owner, name, descriptor));
            if (v != null) return v;
            for (String sup : supers(owner)) {          // inherited field access
                v = r.fields.get(Remapper.fieldKey(sup, name, descriptor));
                if (v != null) return v;
            }
            return name;
        }

        @Override public String mapRecordComponentName(String owner, String name, String descriptor) {
            return mapFieldName(owner, name, descriptor); // stay in lockstep with the backing field
        }

        @Override public String mapAnnotationAttributeName(String descriptor, String name) {
            return name; // annotation element names are part of the annotation's API
        }

        private java.util.Set<String> supers(String owner) {
            return superCache.computeIfAbsent(owner, o -> ctx.hierarchy().allSupers(o));
        }

        /** owner+name -> single renamed method name (a SAM is the only abstract
         *  method of a functional interface, so owner+name is unambiguous for it);
         *  "" marks an overloaded (ambiguous) owner+name we must not guess at. */
        private Map<String, String> samIndex;

        private String sam(String owner, String name) {
            if (samIndex == null) {
                samIndex = new HashMap<>();
                for (Map.Entry<String, String> e : r.methods.entrySet()) {
                    String k = e.getKey();                 // "owner.name desc"
                    int sp = k.indexOf(' ');
                    if (sp < 0) continue;
                    int dot = k.lastIndexOf('.', sp);
                    if (dot < 0) continue;
                    String on = k.substring(0, sp);        // "owner.name"
                    String prev = samIndex.putIfAbsent(on, e.getValue());
                    if (prev != null && !prev.equals(e.getValue())) samIndex.put(on, "");
                }
            }
            String v = samIndex.get(owner + "." + name);
            return (v == null || v.isEmpty()) ? null : v;
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
        // Link every declaration of a signature that could resolve to the same
        // slot at some class: for each class, union all same-name+desc
        // declarations found across itself and its in-jar supertypes. This
        // catches plain overrides AND the diamond where a subclass inherits the
        // same signature from two unrelated in-jar supertypes (so the whole set
        // still gets ONE name — otherwise virtual dispatch would break).
        for (ClassNode cn : ctx.contents().classes().values()) {
            Map<String, String> firstBySig = new HashMap<>();
            java.util.List<String> types = new ArrayList<>();
            types.add(cn.name);
            for (String sup : ctx.hierarchy().allSupers(cn.name)) if (ourClasses.contains(sup)) types.add(sup);
            for (String t : types) {
                ClassNode tc = ctx.contents().classes().get(t);
                if (tc == null || tc.methods == null) continue;
                for (MethodNode m : tc.methods) {
                    if (m.name.charAt(0) == '<' || (m.access & Opcodes.ACC_STATIC) != 0) continue;
                    String key = Remapper.methodKey(t, m.name, m.desc);
                    if (!parent.containsKey(key)) continue;      // only real nodes
                    String sig = m.name + " " + m.desc;
                    String prev = firstBySig.putIfAbsent(sig, key);
                    if (prev != null) union(parent, prev, key);
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
                // inherited static call sites (INVOKESTATIC Sub.foo) are resolved
                // up the hierarchy by NameRemapper, so no propagation needed.
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
