package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

/**
 * Removes Kotlin-specific annotations whose contents become inconsistent after
 * renaming.
 *
 * Kotlin's @Metadata stores fully-qualified names + member signatures as
 * strings inside the annotation payload. ASM's ClassRemapper does NOT rewrite
 * annotation string values, so after a class rename the metadata still points
 * at the OLD names. Kotlin reflection (KClass APIs) then fails with
 * IllegalAccessError / ClassNotFoundError / KotlinReflectionInternalError.
 *
 * The cleanest fix is to strip the metadata entirely. Costs:
 *   - KClass.simpleName  -> falls back to the JVM name
 *   - KClass.qualifiedName -> may return null
 *   - KClass.members / KClass.functions -> empty (no Kotlin view)
 *   - kotlinx.serialization (which calls Foo.Companion.serializer() via
 *     reflection over metadata-derived signatures) breaks. Exclude such
 *     classes manually if you use it.
 *
 * Direct field/method calls still work (they go through standard JVM bytecode).
 *
 * Only runs when ObfConfig.stripKotlinMetadata == true. Operates on every
 * class that is NOT marked no-touch — even if the class itself wasn't renamed,
 * other classes around it likely were, and Kotlin metadata cross-references
 * them, so consistency is best obtained by stripping uniformly inside the
 * obfuscation scope.
 */
public final class KotlinMetadataStripper implements Transformer {

    private static final Set<String> STRIP = Set.of(
        "Lkotlin/Metadata;",
        "Lkotlin/coroutines/jvm/internal/DebugMetadata;",
        "Lkotlin/jvm/internal/SourceDebugExtension;"
    );

    @Override public String name() { return "kotlin-strip"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().stripKotlinMetadata) return;

        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            // CRITICAL: only strip metadata from classes that will actually be
            // renamed. An excluded class keeps its original name, so its Kotlin
            // @Metadata stays 100% consistent and MUST be preserved — otherwise
            // Kotlin reflection breaks. Real bug: stripping @Metadata from the
            // excluded entry point `cop.CopMod` (a Kotlin `object`) made the
            // Fabric Kotlin adapter try to call its private constructor instead
            // of returning INSTANCE -> IllegalAccessException at game init.
            // (crash-2026-05-15_10.09.13)
            if (ctx.exclusions().isClassExcluded(cn.name)) continue;
            if (!inRenameScope(cn.name, ctx)) continue;

            stripAnns(cn.visibleAnnotations);
            stripAnns(cn.invisibleAnnotations);

            if (cn.methods != null) {
                for (MethodNode m : cn.methods) {
                    stripAnns(m.visibleAnnotations);
                    stripAnns(m.invisibleAnnotations);
                }
            }
        }
    }

    private static void stripAnns(List<AnnotationNode> anns) {
        if (anns == null) return;
        anns.removeIf(a -> a.desc != null && STRIP.contains(a.desc));
    }

    /**
     * Mirrors NameTransformer's scope test: a class is only renamed if it lies
     * within a configured root package (or no roots configured -> everything).
     * Classes outside the rename scope keep their names, so their metadata is
     * still valid and must be preserved.
     */
    private static boolean inRenameScope(String internal, ObfContext ctx) {
        if (!ctx.config().renameClasses) return false; // moderate/conservative: no class rename -> keep metadata
        var roots = ctx.config().rootPackages;
        if (roots == null || roots.isEmpty()) return true;
        for (String pkg : roots) {
            String p = pkg.replace('.', '/');
            if (internal.equals(p) || internal.startsWith(p + "/")) return true;
        }
        return false;
    }
}
