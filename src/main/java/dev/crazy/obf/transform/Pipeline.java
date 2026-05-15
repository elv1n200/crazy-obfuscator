package dev.crazy.obf.transform;

import dev.crazy.obf.config.ObfConfig;
import dev.crazy.obf.model.ObfContext;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public final class Pipeline {

    private final List<Transformer> transformers = new ArrayList<>();
    private final PrintStream log;

    public Pipeline(PrintStream log) { this.log = log; }

    public Pipeline add(Transformer t) { transformers.add(t); return this; }

    public void run(ObfContext ctx) {
        long t0 = System.currentTimeMillis();
        for (Transformer t : transformers) { long s = System.currentTimeMillis(); t.plan(ctx); log("plan  " + t.name() + " (" + (System.currentTimeMillis() - s) + "ms)"); }
        for (Transformer t : transformers) { long s = System.currentTimeMillis(); t.apply(ctx); log("apply " + t.name() + " (" + (System.currentTimeMillis() - s) + "ms)"); }
        log("pipeline done in " + (System.currentTimeMillis() - t0) + "ms");
    }

    private void log(String msg) { log.println("[crazy] " + msg); }

    public static Pipeline standard(ObfConfig cfg, PrintStream log) {
        Pipeline p = new Pipeline(log);
        // resources first — adds helper class that string-enc/names may rename
        if (cfg.encryptResources != null && !cfg.encryptResources.isEmpty())
                                  p.add(new ResourceEncryptionTransformer());
        if (cfg.encryptStrings)   p.add(new StringEncryptionTransformer());
        if (cfg.obfuscateNumbers) p.add(new NumberTransformer());
        if (cfg.obfuscateFlow)    p.add(new FlowTransformer());
        if (cfg.injectJunk)       p.add(new JunkCodeTransformer());
        // Rewriting wins over stripping. The stripper (legacy) runs BEFORE
        // rename; the remapper (correct) runs AFTER rename because it needs the
        // final class map. Only fall back to the stripper if rewriting is off.
        boolean rewrite = cfg.rewriteKotlinMetadata;
        if (cfg.stripKotlinMetadata && !rewrite) p.add(new KotlinMetadataStripper());
        // Name transform must come AFTER everything that injects helpers
        // that we want renamed, but BEFORE Watermark/AntiDebug whose own
        // class names are intentionally fixed (`crazy/W`, `crazy/AD`).
        if (cfg.renameClasses || cfg.renameMethods || cfg.renameFields)
            p.add(new NameTransformer());
        if (rewrite) {
            // Order: rename has built the map; first fix metadata, then fix the
            // hardcoded callable-reference signature strings so all three views
            // (metadata, bytecode descriptors, embedded ref strings) agree.
            p.add(new KotlinMetadataRemapper(log));
            p.add(new KotlinCallableRefRemapper(log));
        }
        if (cfg.antiDebug)        p.add(new AntiDebugTransformer());
        if (cfg.watermark != null) p.add(new WatermarkTransformer());
        if (cfg.stripMetadata)    p.add(new MetadataStripTransformer());
        if (cfg.hideReferences)   p.add(new ReferenceHidingTransformer());
        return p;
    }
}
