package dev.crazy.obf;

import dev.crazy.obf.analysis.FabricScanner;
import dev.crazy.obf.analysis.GsonScanner;
import dev.crazy.obf.analysis.KotlinCallableRefScanner;
import dev.crazy.obf.analysis.MixinReferenceScanner;
import dev.crazy.obf.analysis.ReflectionScanner;
import dev.crazy.obf.config.ExclusionRules;
import dev.crazy.obf.config.ObfConfig;
import dev.crazy.obf.io.JarContents;
import dev.crazy.obf.io.JarWriter;
import dev.crazy.obf.io.MappingExporter;
import dev.crazy.obf.model.ObfContext;
import dev.crazy.obf.transform.Pipeline;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * One-call entry point: load -> scan -> transform -> write.
 */
public final class CrazyObfuscator {

    public static void run(Path input, Path output, ObfConfig config, PrintStream log) throws Exception {
        if (log == null) log = System.out;
        log.println("[crazy] reading " + input);
        JarContents contents = JarContents.read(input);
        log.println("[crazy] " + contents.classes().size() + " classes, " + contents.resources().size() + " resources");

        ExclusionRules ex = new ExclusionRules();
        ex.importLists(config.excludeClasses, config.excludeMembers);
        new FabricScanner(contents, ex, log).scan();
        new ReflectionScanner(contents, ex, log).scan();
        if (config.renameClasses || config.renameMethods || config.renameFields) {
            new MixinReferenceScanner(contents, ex, log).scan();
        }
        if (config.renameMethods || config.renameFields) {
            new KotlinCallableRefScanner(contents, ex, log).scan();
        }
        if (config.renameFields) {
            new GsonScanner(contents, ex, log).scan();
        }

        ObfContext ctx = new ObfContext(contents, config, ex);
        Pipeline.standard(config, log).run(ctx);

        log.println("[crazy] writing " + output);
        JarWriter.write(output, contents, ctx);

        if (config.mappingOutput != null && !config.mappingOutput.isBlank()) {
            Path mp = Path.of(config.mappingOutput);
            MappingExporter.export(mp, ctx);
            log.println("[crazy] mapping written to " + mp);
        }
        log.println("[crazy] done.");
    }
}
