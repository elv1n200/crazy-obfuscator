package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;

/** A pass over the loaded jar contents. */
public interface Transformer {

    String name();

    /** Runs before any class is rewritten. May populate the global Remapper. */
    default void plan(ObfContext ctx) {}

    /** Runs the actual rewrite. Should mutate ClassNode instances inside ctx.contents(). */
    void apply(ObfContext ctx);
}
