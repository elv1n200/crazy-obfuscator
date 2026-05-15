package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;

/**
 * Placeholder for invokedynamic-based reference hiding.
 *
 * Doing this safely on Fabric requires teaching every call site to resolve
 * through a generated bootstrap, AND keeping Mixin/Reflection-touched call
 * sites verbatim. That's a substantial amount of extra code that buys little
 * over the other transformers for the "anti-leak" goal.
 *
 * Enabled only when ObfConfig.hideReferences = true. Default = off. When off
 * this is a no-op; the pipeline still adds it for forward compatibility.
 */
public final class ReferenceHidingTransformer implements Transformer {

    @Override public String name() { return "refhide"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().hideReferences) return;
        // intentionally not implemented in this initial release — left as a stub
        // so the Pipeline order is stable when it lands.
    }
}
