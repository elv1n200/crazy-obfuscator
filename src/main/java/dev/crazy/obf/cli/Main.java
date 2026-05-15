package dev.crazy.obf.cli;

import dev.crazy.obf.CrazyObfuscator;
import dev.crazy.obf.config.ObfConfig;
import dev.crazy.obf.model.NameGenerator;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "crazy-obf",
    mixinStandardHelpOptions = true,
    version = "Crazy Obfuscator 0.1.0",
    description = "Java/Fabric .jar obfuscator (names, strings, numbers, flow, strip)."
)
public final class Main implements Callable<Integer> {

    @Parameters(index = "0", description = "Input .jar")  Path input;
    @Parameters(index = "1", description = "Output .jar") Path output;

    @Option(names = {"-c", "--config"}, description = "JSON config file (optional)") Path configFile;
    @Option(names = {"-p", "--root-package"}, description = "Root package(s) of your code. Repeatable.", arity = "1..*") String[] rootPackages;
    @Option(names = {"--no-names"})    boolean noNames;
    @Option(names = {"--no-strings"})  boolean noStrings;
    @Option(names = {"--no-numbers"})  boolean noNumbers;
    @Option(names = {"--no-flow"})     boolean noFlow;
    @Option(names = {"--no-strip"})    boolean noStrip;
    @Option(names = {"--no-flatten"})  boolean noFlatten;
    @Option(names = {"--name-style"}, description = "ALPHA | CONFUSE | UNICODE") NameGenerator.Style nameStyle;
    @Option(names = {"--seed"})        long seed;
    @Option(names = {"-v", "--verbose"}) boolean verbose;

    @Option(names = {"-m", "--mapping"}, description = "Write Proguard-format mapping file to PATH") Path mappingPath;
    @Option(names = {"--watermark"}, description = "Embed this tag in the jar for leak tracking") String watermark;
    @Option(names = {"--no-junk"}, description = "Disable junk-method injection") boolean noJunk;
    @Option(names = {"--anti-debug"}, description = "Inject anti-debug check class") boolean antiDebug;
    @Option(names = {"--encrypt-resource"}, description = "Glob of resources to encrypt. Repeatable.", arity = "1..*") String[] encryptResources;
    @Option(names = {"--flow-level"}, description = "Control-flow level: 0|1|2") Integer flowLevel;
    @Option(names = {"--rewrite-kotlin-metadata"}, description = "Rewrite Kotlin metadata to match renames (required for Kotlin codebases)") boolean rewriteKotlinMetadata;

    @Override
    public Integer call() throws Exception {
        ObfConfig cfg = ObfConfig.load(configFile);

        if (rootPackages != null) {
            cfg.rootPackages.clear();
            for (String p : rootPackages) cfg.rootPackages.add(p);
        }
        if (noNames)    { cfg.renameClasses = cfg.renameMethods = cfg.renameFields = false; }
        if (noStrings)  cfg.encryptStrings  = false;
        if (noNumbers)  cfg.obfuscateNumbers = false;
        if (noFlow)     cfg.obfuscateFlow    = false;
        if (noStrip)    cfg.stripMetadata    = false;
        if (noFlatten)  cfg.flattenPackages  = false;
        if (nameStyle != null) cfg.nameStyle = nameStyle;
        if (seed != 0)  cfg.seed = seed;
        cfg.verbose = verbose;

        if (mappingPath != null) cfg.mappingOutput = mappingPath.toString();
        if (watermark != null)   cfg.watermark = watermark;
        if (noJunk)              cfg.injectJunk = false;
        if (antiDebug)           cfg.antiDebug = true;
        if (encryptResources != null) {
            cfg.encryptResources = new java.util.ArrayList<>(java.util.Arrays.asList(encryptResources));
        }
        if (flowLevel != null)   cfg.flowLevel = flowLevel;
        if (rewriteKotlinMetadata) cfg.rewriteKotlinMetadata = true;

        CrazyObfuscator.run(input, output, cfg, System.out);
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
