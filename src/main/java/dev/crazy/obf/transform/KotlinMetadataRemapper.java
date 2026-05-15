package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import dev.crazy.obf.model.Remapper;
import kotlin.Metadata;
import kotlin.metadata.KmClass;
import kotlin.metadata.KmClassifier;
import kotlin.metadata.KmConstructor;
import kotlin.metadata.KmFunction;
import kotlin.metadata.KmPackage;
import kotlin.metadata.KmProperty;
import kotlin.metadata.KmType;
import kotlin.metadata.KmTypeAlias;
import kotlin.metadata.KmTypeParameter;
import kotlin.metadata.KmTypeProjection;
import kotlin.metadata.KmValueParameter;
import kotlin.metadata.jvm.JvmExtensionsKt;
import kotlin.metadata.jvm.JvmMethodSignature;
import kotlin.metadata.jvm.JvmFieldSignature;
import kotlin.metadata.jvm.KotlinClassMetadata;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites Kotlin's {@code @kotlin.Metadata} so the names embedded in it stay
 * consistent with the JVM-level renames performed by {@link NameTransformer}.
 *
 * ASM's ClassRemapper rewrites bytecode and annotation *descriptors* but NOT
 * the opaque string payload of {@code @Metadata} (its {@code d1}/{@code d2}
 * arrays hold a protobuf with fully-qualified Kotlin names). Left unfixed,
 * Kotlin reflection (KClass/KProperty/KFunction) resolves against stale names
 * and throws KotlinReflectionInternalError — which is exactly what broke COP's
 * settings framework (`settingFromK0` -> `findPropertyDescriptor`).
 *
 * This transformer parses the metadata with JetBrains' official
 * kotlin-metadata-jvm library, deep-remaps every class reference and every JVM
 * signature descriptor using the global {@link Remapper}, and re-emits it.
 *
 * Runs AFTER NameTransformer (needs the final mapping AND the renamed
 * ClassNodes). Replaces the old metadata *stripper* entirely.
 */
public final class KotlinMetadataRemapper implements Transformer {

    private final PrintStream log;
    private int rewritten, failed;

    public KotlinMetadataRemapper(PrintStream log) { this.log = log; }
    public KotlinMetadataRemapper() { this(System.out); }

    @Override public String name() { return "kotlin-meta"; }

    @Override
    public void apply(ObfContext ctx) {
        Remapper r = ctx.remapper();
        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            AnnotationNode ann = findMetadata(cn);
            if (ann == null) continue;
            try {
                rewrite(cn, ann, r);
                rewritten++;
            } catch (Throwable t) {
                failed++;
                // Leave the original annotation in place (stale but present) so
                // we are never worse than "no rewrite". Log for triage.
                log.println("[crazy] kotlin-meta: could not rewrite " + cn.name + ": " + t);
            }
        }
        log.println("[crazy] kotlin-meta: rewrote " + rewritten + " classes, " + failed + " failed");
    }

    // ------------------------------------------------------------------ I/O

    private static AnnotationNode findMetadata(ClassNode cn) {
        AnnotationNode a = find(cn.visibleAnnotations);
        return a != null ? a : find(cn.invisibleAnnotations);
    }
    private static AnnotationNode find(List<AnnotationNode> anns) {
        if (anns == null) return null;
        for (AnnotationNode a : anns) if ("Lkotlin/Metadata;".equals(a.desc)) return a;
        return null;
    }

    @SuppressWarnings("unchecked")
    private void rewrite(ClassNode owner, AnnotationNode ann, Remapper r) {
        // --- read annotation elements ---
        int k = 1;
        int[] mv = null;
        String[] d1 = null, d2 = null;
        String xs = null, pn = null;
        int xi = 0;
        List<Object> v = ann.values == null ? List.of() : ann.values;
        for (int i = 0; i + 1 < v.size(); i += 2) {
            String key = (String) v.get(i);
            Object val = v.get(i + 1);
            switch (key) {
                case "k"  -> k  = (Integer) val;
                case "mv" -> mv = toIntArray((List<Integer>) val);
                case "d1" -> d1 = toStrArray((List<String>) val);
                case "d2" -> d2 = toStrArray((List<String>) val);
                case "xs" -> xs = (String) val;
                case "pn" -> pn = (String) val;
                case "xi" -> xi = (Integer) val;
                default -> { /* bv (deprecated) etc. — ignore */ }
            }
        }
        if (d1 == null) d1 = new String[0];
        if (d2 == null) d2 = new String[0];
        if (mv == null) mv = new int[]{2, 0, 0};

        Metadata md = kotlin.metadata.jvm.JvmMetadataUtil.Metadata(
            k, mv, d1, d2, xs == null ? "" : xs, pn == null ? "" : pn, xi);

        // readStrict (not readLenient) — only strict-mode metadata is writable.
        // Requires the library version >= the Kotlin version that produced the
        // metadata (kotlin-metadata-jvm 2.3.21 for COP's Kotlin 2.3.21).
        KotlinClassMetadata kcm = KotlinClassMetadata.readStrict(md);

        if (kcm instanceof KotlinClassMetadata.Class c) {
            remapClass(c.getKmClass(), r);
        } else if (kcm instanceof KotlinClassMetadata.FileFacade ff) {
            remapPackage(ff.getKmPackage(), r);
        } else if (kcm instanceof KotlinClassMetadata.MultiFileClassPart mp) {
            remapPackage(mp.getKmPackage(), r);
        } else if (kcm instanceof KotlinClassMetadata.MultiFileClassFacade mf) {
            List<String> parts = mf.getPartClassNames();
            for (int i = 0; i < parts.size(); i++) parts.set(i, remapClassName(parts.get(i), r));
        } else if (kcm instanceof KotlinClassMetadata.SyntheticClass sc) {
            if (sc.getKmLambda() != null) remapFunction(sc.getKmLambda().getFunction(), r);
        } else {
            return; // Unknown — leave as-is
        }

        Metadata out = kcm.write();

        // --- write elements back into the annotation ---
        ann.values = new ArrayList<>();
        add(ann, "k", out.k());
        add(ann, "mv", toIntList(out.mv()));
        add(ann, "d1", toStrList(out.d1()));
        add(ann, "d2", toStrList(out.d2()));
        if (out.xs() != null && !out.xs().isEmpty()) add(ann, "xs", out.xs());
        if (out.pn() != null && !out.pn().isEmpty()) add(ann, "pn", out.pn());
        if (out.xi() != 0) add(ann, "xi", out.xi());
    }

    private static void add(AnnotationNode a, String key, Object val) { a.values.add(key); a.values.add(val); }

    // ----------------------------------------------------------- remapping

    private void remapClass(KmClass c, Remapper r) {
        // Capture the OLD name BEFORE remapping — needed to reconstruct the
        // old internal names of nested classes / companion (which are stored
        // as simple names relative to this class).
        String oldOuterJvm = kmToJvm(c.getName());

        c.setName(remapClassName(c.getName(), r));
        for (KmType t : c.getSupertypes()) remapType(t, r);
        for (KmTypeParameter tp : c.getTypeParameters()) remapTypeParam(tp, r);
        for (KmFunction f : c.getFunctions()) remapFunction(f, r);
        for (KmProperty p : c.getProperties()) remapProperty(p, r);
        for (KmConstructor ct : c.getConstructors()) remapConstructor(ct, r);
        for (KmTypeAlias ta : c.getTypeAliases()) remapTypeAlias(ta, r);
        for (KmType t : c.getContextReceiverTypes()) remapType(t, r);
        // Delegated properties (`val x by ...`) live in a JVM-only extension and
        // are what Kotlin reflection reads for `::x` references. Missing this is
        // what kept `getSelectedTheme()L...SelectorComponent;` stale.
        for (KmProperty p : JvmExtensionsKt.getLocalDelegatedProperties(c)) remapProperty(p, r);
        if (c.getInlineClassUnderlyingType() != null) remapType(c.getInlineClassUnderlyingType(), r);

        // Companion + nestedClasses are SIMPLE names relative to this class.
        // We preserve nesting on rename, so kotlin-reflect resolves an inner
        // class (e.g. a @JvmInline value class) through its outer's metadata.
        // These MUST be remapped (not cleared) or `KClassImpl.isValue` fails
        // with "Unresolved class".
        if (c.getCompanionObject() != null) {
            c.setCompanionObject(remapSimpleNested(oldOuterJvm, c.getCompanionObject(), r));
        }
        java.util.List<String> nested = c.getNestedClasses();
        for (int i = 0; i < nested.size(); i++) {
            nested.set(i, remapSimpleNested(oldOuterJvm, nested.get(i), r));
        }
        remapNames(c.getSealedSubclasses(), r);
    }

    /** Map an inner class's simple name using {@code <oldOuter>$<simple>} -> new simple. */
    private static String remapSimpleNested(String oldOuterJvm, String simple, Remapper r) {
        if (oldOuterJvm == null || simple == null) return simple;
        String mapped = r.classes.get(oldOuterJvm + "$" + simple);
        if (mapped == null) return simple;
        int d = mapped.lastIndexOf('$');
        return d >= 0 ? mapped.substring(d + 1) : mapped;
    }

    /** kotlin-metadata ClassName -> JVM internal (strip leading '.', '.'→'$'). */
    static String kmToJvm(String km) {
        if (km == null || km.isEmpty()) return km;
        String b = km.charAt(0) == '.' ? km.substring(1) : km;
        return b.replace('.', '$');
    }

    private void remapPackage(KmPackage p, Remapper r) {
        for (KmFunction f : p.getFunctions()) remapFunction(f, r);
        for (KmProperty pr : p.getProperties()) remapProperty(pr, r);
        for (KmTypeAlias ta : p.getTypeAliases()) remapTypeAlias(ta, r);
        for (KmProperty pr : JvmExtensionsKt.getLocalDelegatedProperties(p)) remapProperty(pr, r);
    }

    private void remapFunction(KmFunction f, Remapper r) {
        for (KmTypeParameter tp : f.getTypeParameters()) remapTypeParam(tp, r);
        if (f.getReceiverParameterType() != null) remapType(f.getReceiverParameterType(), r);
        for (KmType t : f.getContextReceiverTypes()) remapType(t, r);
        for (KmValueParameter vp : f.getValueParameters()) remapValueParam(vp, r);
        remapType(f.getReturnType(), r);
        JvmMethodSignature s = JvmExtensionsKt.getSignature(f);
        if (s != null) JvmExtensionsKt.setSignature(f, remapMethodSig(s, r));
    }

    private void remapProperty(KmProperty p, Remapper r) {
        for (KmTypeParameter tp : p.getTypeParameters()) remapTypeParam(tp, r);
        if (p.getReceiverParameterType() != null) remapType(p.getReceiverParameterType(), r);
        for (KmType t : p.getContextReceiverTypes()) remapType(t, r);
        if (p.getSetterParameter() != null) remapValueParam(p.getSetterParameter(), r);
        remapType(p.getReturnType(), r);
        JvmMethodSignature g = JvmExtensionsKt.getGetterSignature(p);
        if (g != null) JvmExtensionsKt.setGetterSignature(p, remapMethodSig(g, r));
        JvmMethodSignature st = JvmExtensionsKt.getSetterSignature(p);
        if (st != null) JvmExtensionsKt.setSetterSignature(p, remapMethodSig(st, r));
        JvmFieldSignature fs = JvmExtensionsKt.getFieldSignature(p);
        if (fs != null) JvmExtensionsKt.setFieldSignature(p, remapFieldSig(fs, r));
        JvmMethodSignature sma = JvmExtensionsKt.getSyntheticMethodForAnnotations(p);
        if (sma != null) JvmExtensionsKt.setSyntheticMethodForAnnotations(p, remapMethodSig(sma, r));
        JvmMethodSignature smd = JvmExtensionsKt.getSyntheticMethodForDelegate(p);
        if (smd != null) JvmExtensionsKt.setSyntheticMethodForDelegate(p, remapMethodSig(smd, r));
    }

    private void remapConstructor(KmConstructor ct, Remapper r) {
        for (KmValueParameter vp : ct.getValueParameters()) remapValueParam(vp, r);
        JvmMethodSignature s = JvmExtensionsKt.getSignature(ct);
        if (s != null) JvmExtensionsKt.setSignature(ct, remapMethodSig(s, r));
    }

    private void remapTypeAlias(KmTypeAlias ta, Remapper r) {
        for (KmTypeParameter tp : ta.getTypeParameters()) remapTypeParam(tp, r);
        remapType(ta.getUnderlyingType(), r);
        remapType(ta.getExpandedType(), r);
    }

    private void remapTypeParam(KmTypeParameter tp, Remapper r) {
        for (KmType b : tp.getUpperBounds()) remapType(b, r);
    }

    private void remapValueParam(KmValueParameter vp, Remapper r) {
        if (vp.getType() != null) remapType(vp.getType(), r);
        if (vp.getVarargElementType() != null) remapType(vp.getVarargElementType(), r);
    }

    private void remapType(KmType t, Remapper r) {
        if (t == null) return;
        KmClassifier cl = t.getClassifier();
        if (cl instanceof KmClassifier.Class kc) {
            t.setClassifier(new KmClassifier.Class(remapClassName(kc.getName(), r)));
        } else if (cl instanceof KmClassifier.TypeAlias ta) {
            t.setClassifier(new KmClassifier.TypeAlias(remapClassName(ta.getName(), r)));
        }
        for (KmTypeProjection pr : t.getArguments()) {
            if (pr.getType() != null) remapType(pr.getType(), r);
        }
        if (t.getAbbreviatedType() != null) remapType(t.getAbbreviatedType(), r);
        if (t.getOuterType() != null) remapType(t.getOuterType(), r);
        if (t.getFlexibleTypeUpperBound() != null) remapType(t.getFlexibleTypeUpperBound().getType(), r);
    }

    private void remapNames(List<String> names, Remapper r) {
        for (int i = 0; i < names.size(); i++) names.set(i, remapClassName(names.get(i), r));
    }

    // ----------------------------------------------------- name helpers

    /**
     * kotlin-metadata ClassName: package segments separated by '/', nested
     * classes by '.', local classes prefixed with '.'. Convert to a JVM
     * internal name, look it up in the remapper, convert back.
     */
    public static String remapClassName(String kmName, Remapper r) {
        if (kmName == null || kmName.isEmpty()) return kmName;
        boolean local = kmName.charAt(0) == '.';
        String body = local ? kmName.substring(1) : kmName;
        String jvm = body.replace('.', '$');
        String mapped = r.classes.get(jvm);
        if (mapped == null) return kmName; // not ours / not renamed
        String back = mapped.replace('$', '.');
        return local ? "." + back : back;
    }

    private JvmMethodSignature remapMethodSig(JvmMethodSignature s, Remapper r) {
        return new JvmMethodSignature(s.getName(), remapDescriptor(s.getDescriptor(), r));
    }
    private JvmFieldSignature remapFieldSig(JvmFieldSignature s, Remapper r) {
        return new JvmFieldSignature(s.getName(), remapDescriptor(s.getDescriptor(), r));
    }

    /** Remaps every {@code L<internal>;} object type inside a JVM descriptor. */
    public static String remapDescriptor(String desc, Remapper r) {
        if (desc == null || desc.indexOf('L') < 0) return desc;
        StringBuilder out = new StringBuilder(desc.length());
        int i = 0, n = desc.length();
        while (i < n) {
            char ch = desc.charAt(i);
            if (ch == 'L') {
                int semi = desc.indexOf(';', i);
                if (semi < 0) { out.append(desc.substring(i)); break; }
                String internal = desc.substring(i + 1, semi);
                String mapped = r.classes.getOrDefault(internal, internal);
                out.append('L').append(mapped).append(';');
                i = semi + 1;
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    // --------------------------------------------------- array converters

    private static int[] toIntArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
    private static String[] toStrArray(List<String> l) { return l.toArray(new String[0]); }
    private static List<Integer> toIntList(int[] a) {
        List<Integer> l = new ArrayList<>(a.length);
        for (int x : a) l.add(x);
        return l;
    }
    private static List<String> toStrList(String[] a) { return new ArrayList<>(java.util.Arrays.asList(a)); }
}
