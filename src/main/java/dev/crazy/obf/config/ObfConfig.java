package dev.crazy.obf.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.crazy.obf.model.NameGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON config. All fields are public so Gson can populate them directly.
 *
 * Defaults are tuned for a Fabric mod: name obfuscation only renames things below
 * the user's own root package, string encryption is on, control-flow is light.
 */
public final class ObfConfig {

    /** Root package(s) of YOUR code. Anything outside is NEVER renamed. */
    public List<String> rootPackages = new ArrayList<>();

    /** Extra include patterns (glob over internal name with '/' separator). */
    public List<String> includeClasses = new ArrayList<>();

    /** Extra explicit exclusions on top of the auto-detected Fabric/Mixin ones. */
    public List<String> excludeClasses = new ArrayList<>();
    public List<String> excludeMembers = new ArrayList<>();

    public boolean renameClasses = true;
    public boolean renameMethods = true;
    public boolean renameFields  = true;
    public boolean encryptStrings = true;
    public boolean obfuscateNumbers = true;
    public boolean obfuscateFlow = true;
    public boolean stripMetadata = true;
    public boolean hideReferences = false;

    /**
     * Hide field access (GET/PUT FIELD/STATIC) behind invokedynamic bound to an
     * injected self-decrypting bootstrap ({@code crazy/FIndy}), so the read/write
     * graph over your own fields disappears from decompiled output. Conservative:
     * only fields declared in your own classes, accessible from the call site
     * (self or public), non-volatile, and (for writes) non-final; never inside
     * constructors. Steady-state cost is nil (resolved once). Opt-in.
     */
    public boolean hideFields = false;

    /** How aggressive the flow pass is. 0 = off, 1 = light, 2 = medium. >2 not currently used. */
    public int flowLevel = 1;

    /** Chance (0-100) that a given string literal gets encrypted. */
    public int stringEncryptionChance = 100;

    /**
     * Targeted string encryption. If either list is non-empty, ONLY matching
     * string literals are encrypted (everything else stays plaintext) — e.g.
     * hide just a URL/endpoint without touching all strings.
     *   encryptStringsExact    — exact literal values
     *   encryptStringsMatching — regexes (substring match; invalid regex is
     *                            treated as a literal)
     * Works even with stringEncryptionChance = 0.
     */
    public java.util.List<String> encryptStringsExact = new java.util.ArrayList<>();
    public java.util.List<String> encryptStringsMatching = new java.util.ArrayList<>();

    /** Chance (0-100) that a given number constant gets transformed. */
    public int numberObfuscationChance = 70;

    /**
     * Mixed Boolean-Arithmetic: replace int +,-,^,|,& operations with
     * algebraically-equivalent bit/arith identities (bit-for-bit identical under
     * two's-complement). Hides the arithmetic itself, not just constants. Opt-in.
     */
    public boolean mbaArithmetic = false;

    /** Chance (0-100) that an eligible int op gets the MBA rewrite. */
    public int mbaChance = 50;

    /**
     * Hide int/long {@code LDC} constants behind {@code CONSTANT_Dynamic} (condy)
     * resolved by an injected bootstrap ({@code crazy/NC}) — a decompiler sees an
     * opaque dynamic constant instead of the literal. Requires class v55+ (Java
     * 11); older classes are skipped. Opt-in.
     */
    public boolean hideNumbersCondy = false;

    /**
     * Argument-driven opaque predicates: guard method bodies with a predicate
     * that is provably true for every value of one of the method's own int
     * arguments (e.g. {@code (x|1)!=0}), so it can't be constant-folded like a
     * field guard. The impossible branch is dead. Opt-in.
     */
    public boolean opaquePredicates = false;

    /** Chance (0-100) that an eligible method gets an opaque predicate. */
    public int opaquePredicateChance = 50;

    /**
     * Runtime self-integrity check: bake each protected class's CRC32 into an
     * injected verifier ({@code crazy/IT}) and re-check at load; throw if a class
     * was edited. Raises the bar against jar-patching. NOT a security boundary,
     * and incompatible with load-time bytecode transformers (Java agents) — so it
     * is deliberately excluded from the {@code --crazy} preset. Opt-in.
     */
    public boolean antiTamper = false;

    /** Control-flow flattening (dispatcher loop). Off by default — heaviest pass. */
    public boolean flattenControlFlow = false;

    /** Chance (0-100) that an eligible method gets flattened. */
    public int flattenChance = 40;

    /**
     * Decompiler-confusion pass. Wraps eligible method bodies in a fake
     * try/catch whose handler simply rethrows — behaviour-neutral (any
     * exception that would propagate still propagates), but high-level
     * decompilers (CFR, Vineflower, Procyon) emit garbage/incomplete output
     * because the irreducible exception edge defeats their structuring. Does
     * NOT hide anything from {@code javap}; pair with string/number passes for
     * real secrecy. Opt-in. Only methods with no existing try/catch are touched.
     */
    public boolean antiDecompile = false;

    /** Chance (0-100) that an eligible method gets the anti-decompile wrap. */
    public int antiDecompileChance = 60;

    /**
     * Hide string literals behind {@code CONSTANT_Dynamic} (condy) instead of
     * the inline decoder method. The {@code ldc "text"} becomes a condy whose
     * injected bootstrap ({@code crazy/C}) materialises the real string at link
     * time. Decompilers and {@code javap -c} show only an opaque dynamic
     * constant — no plaintext, no obvious decoder call. Requires class-file
     * version &ge; 55 (Java 11); older classes fall back to the inline decoder.
     * Honours the targeted-string selection (encryptStringsExact/Matching).
     */
    public boolean hideStringsCondy = false;

    /**
     * Byte injection — write raw junk class-file attributes into classes,
     * methods and fields. The JVM silently ignores unknown attributes
     * (JVMS §4.7.1), so this never affects execution, but it defeats raw-byte
     * signature/fingerprint scanners and trips naive attribute parsers. The
     * decoy names impersonate real toolchain attributes to waste reverser time.
     * Does NOT hide anything from a structured decompiler. Opt-in.
     */
    public boolean injectJunkAttributes = false;

    /** Chance (0-100) that an eligible method/field gets a junk attribute. */
    public int junkAttributeChance = 60;

    public NameGenerator.Style nameStyle = NameGenerator.Style.ALPHA;

    /** Fixed RNG seed for reproducible builds. 0 = random. */
    public long seed = 0L;

    /** If true, package structure is flattened to a single obfuscated package. */
    public boolean flattenPackages = true;

    /** If flattenPackages is true, the new package name (internal-slash form). */
    public String flattenedPackage = "a";

    public boolean verbose = false;

    /** If non-null, write Proguard-format mapping to this path. */
    public String mappingOutput = null;

    /** Watermark string baked into a synthetic class. Useful for tracking leaks. */
    public String watermark = null;

    /** Inject decoy synthetic methods. */
    public boolean injectJunk = true;

    /** Encrypt embedded resources whose path matches these globs. */
    public java.util.List<String> encryptResources = new java.util.ArrayList<>();

    /** Anti-debug pass — throws at startup if a JDWP/agent flag is detected. */
    public boolean antiDebug = false;

    /**
     * Rewrite Kotlin {@code @Metadata} so the names inside it stay consistent
     * with renames (via the official kotlin-metadata-jvm library). Required
     * when renaming a Kotlin codebase: Kotlin reflection embeds names in that
     * annotation, so without rewriting, KClass/KProperty/KFunction resolve
     * against stale names. Leave on for any Kotlin (e.g. Fabric) mod.
     */
    public boolean rewriteKotlinMetadata = false;

    /**
     * "Crazy mode" — the whole aggressive-but-sound stack at maximum settings,
     * in one call. Every pass it enables is individually verified to keep the
     * class loadable and behaviour-identical; stacking them is covered by the
     * end-to-end "everything on" test.
     *
     * <p>Deliberately left untouched: {@link #rewriteKotlinMetadata} (only
     * meaningful for Kotlin — keep the caller's value), {@link #rootPackages}
     * and the exclusion lists (scope is the user's decision), and {@link #seed}.
     * Renaming still only ever touches classes inside {@code rootPackages}.
     *
     * @return {@code this}, for chaining.
     */
    public ObfConfig applyCrazyPreset() {
        renameClasses = renameMethods = renameFields = true;
        flattenPackages = true;

        encryptStrings = true;
        stringEncryptionChance = 100;
        hideStringsCondy = true;

        obfuscateNumbers = true;
        numberObfuscationChance = 100;

        mbaArithmetic = true;
        mbaChance = 60;
        hideNumbersCondy = true;

        opaquePredicates = true;
        opaquePredicateChance = 60;

        obfuscateFlow = true;
        flowLevel = 2;

        flattenControlFlow = true;
        flattenChance = 60;

        antiDecompile = true;
        antiDecompileChance = 100;

        hideReferences = true;
        hideFields = true;

        injectJunk = true;
        injectJunkAttributes = true;
        junkAttributeChance = 60;

        stripMetadata = true;
        if (watermark == null || watermark.isBlank()) watermark = "crazy";
        return this;
    }

    public static ObfConfig load(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return new ObfConfig();
        String json = Files.readString(p);
        Gson g = new GsonBuilder().setPrettyPrinting().create();
        ObfConfig c = g.fromJson(json, ObfConfig.class);
        return c == null ? new ObfConfig() : c;
    }

    public String toPrettyJson() {
        return new GsonBuilder().setPrettyPrinting().create().toJson(this);
    }
}
