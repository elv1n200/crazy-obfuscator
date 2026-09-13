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
    version = "Crazy Obfuscator 0.8.1",
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
    @Option(names = {"--encrypt-string"}, description = "Encrypt ONLY this exact string literal. Repeatable.", arity = "1..*") String[] encryptStringExact;
    @Option(names = {"--encrypt-string-regex"}, description = "Encrypt ONLY string literals matching this regex. Repeatable.", arity = "1..*") String[] encryptStringRegex;
    @Option(names = {"--flow-level"}, description = "Control-flow level: 0|1|2") Integer flowLevel;
    @Option(names = {"--flatten"}, description = "Enable control-flow flattening (dispatcher loop)") boolean flatten;
    @Option(names = {"--flatten-chance"}, description = "0-100: chance an eligible method is flattened") Integer flattenChance;
    @Option(names = {"--rewrite-kotlin-metadata"}, description = "Rewrite Kotlin metadata to match renames (required for Kotlin codebases)") boolean rewriteKotlinMetadata;
    @Option(names = {"--anti-decompile"}, description = "Decompiler-confusion: wrap methods in opaque rethrow handlers (behavior-neutral; breaks CFR/Vineflower, not javap)") boolean antiDecompile;
    @Option(names = {"--anti-decompile-chance"}, description = "0-100: chance an eligible method gets the anti-decompile wrap") Integer antiDecompileChance;
    @Option(names = {"--hide-strings-condy"}, description = "Hide string literals behind CONSTANT_Dynamic (condy) instead of an inline decoder") boolean hideStringsCondy;
    @Option(names = {"--hide-references"}, description = "Hide internal call graph behind invokedynamic (crazy/Indy bootstrap)") boolean hideReferences;
    @Option(names = {"--hide-fields"}, description = "Hide field access behind invokedynamic (crazy/FIndy bootstrap)") boolean hideFields;
    @Option(names = {"--junk-attributes"}, description = "Byte injection: write junk class-file attributes the JVM ignores (defeats raw-byte signature scanners)") boolean junkAttributes;
    @Option(names = {"--junk-attribute-chance"}, description = "0-100: chance an eligible method/field gets a junk attribute") Integer junkAttributeChance;
    @Option(names = {"--mba"}, description = "Mixed Boolean-Arithmetic: rewrite int/long +,-,^,|,& ops as equivalent bit/arith identities") boolean mba;
    @Option(names = {"--hide-numbers-condy"}, description = "Hide int/long constants behind CONSTANT_Dynamic (crazy/NC bootstrap)") boolean hideNumbersCondy;
    @Option(names = {"--mba-chance"}, description = "0-100: chance an eligible int op gets the MBA rewrite") Integer mbaChance;
    @Option(names = {"--opaque-predicates"}, description = "Insert argument-driven opaque predicates (always-true guards from real int args)") boolean opaquePredicates;
    @Option(names = {"--opaque-predicate-chance"}, description = "0-100: chance an eligible method gets an opaque predicate") Integer opaquePredicateChance;
    @Option(names = {"--anti-tamper"}, description = "Runtime self-integrity check (CRC of class bytes via crazy/IT). NOT in --crazy; incompatible with Java agents/load-time transformers") boolean antiTamper;
    @Option(names = {"--crazy"}, description = "CRAZY MODE: enable the whole aggressive stack at max settings (names+condy strings+numbers+flow2+flatten+anti-decompile+indy refs+indy fields+byte injection+strip+watermark). Individual --no-* / chance flags still override.") boolean crazy;

    @Override
    public Integer call() throws Exception {
        ObfConfig cfg = ObfConfig.load(configFile);

        if (rootPackages != null) {
            cfg.rootPackages.clear();
            for (String p : rootPackages) cfg.rootPackages.add(p);
        }

        // Crazy mode sets the aggressive baseline; the explicit flags below
        // still override it (so `--crazy --no-flow` is a valid, coherent combo).
        if (crazy) cfg.applyCrazyPreset();

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
        if (encryptStringExact != null) {
            cfg.encryptStringsExact = new java.util.ArrayList<>(java.util.Arrays.asList(encryptStringExact));
        }
        if (encryptStringRegex != null) {
            cfg.encryptStringsMatching = new java.util.ArrayList<>(java.util.Arrays.asList(encryptStringRegex));
        }
        if (flowLevel != null)   cfg.flowLevel = flowLevel;
        if (flatten)             cfg.flattenControlFlow = true;
        if (flattenChance != null) cfg.flattenChance = flattenChance;
        if (rewriteKotlinMetadata) cfg.rewriteKotlinMetadata = true;
        if (antiDecompile)         cfg.antiDecompile = true;
        if (antiDecompileChance != null) cfg.antiDecompileChance = antiDecompileChance;
        if (hideStringsCondy)      cfg.hideStringsCondy = true;
        if (hideReferences)        cfg.hideReferences = true;
        if (hideFields)            cfg.hideFields = true;
        if (junkAttributes)        cfg.injectJunkAttributes = true;
        if (junkAttributeChance != null) cfg.junkAttributeChance = junkAttributeChance;
        if (mba)                   cfg.mbaArithmetic = true;
        if (mbaChance != null)     cfg.mbaChance = mbaChance;
        if (hideNumbersCondy)      cfg.hideNumbersCondy = true;
        if (opaquePredicates)      cfg.opaquePredicates = true;
        if (opaquePredicateChance != null) cfg.opaquePredicateChance = opaquePredicateChance;
        if (antiTamper)            cfg.antiTamper = true;

        CrazyObfuscator.run(input, output, cfg, System.out);
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
