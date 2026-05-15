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

    /** How aggressive the flow pass is. 0 = off, 1 = light, 2 = medium. >2 not currently used. */
    public int flowLevel = 1;

    /** Chance (0-100) that a given string literal gets encrypted. */
    public int stringEncryptionChance = 100;

    /** Chance (0-100) that a given number constant gets transformed. */
    public int numberObfuscationChance = 70;

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
     * Strip Kotlin metadata annotations (kotlin.Metadata, DebugMetadata,
     * SourceDebugExtension) from any class touched by a rename. Required for
     * safe class/method renaming of Kotlin code — Kotlin's reflection embeds
     * names as strings in those annotations, so leaving them in would point
     * at the OLD names. Stripping them turns the class into a "Java view" for
     * reflective code (KClass.simpleName falls back to the JVM name).
     */
    public boolean stripKotlinMetadata = false;

    /**
     * Rewrite (rather than strip) Kotlin @Metadata so the names inside it stay
     * consistent with renames. This is the correct option for renaming a
     * Kotlin codebase that uses reflection on itself (KProperty/KClass). If
     * both this and stripKotlinMetadata are set, rewriting wins.
     */
    public boolean rewriteKotlinMetadata = false;

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
