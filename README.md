# Crazy Obfuscator

An ASM-based `.jar` obfuscator for Java **and Kotlin**, with first-class
support for **Fabric mods**. ZKM-style transform pipeline that has been
verified end-to-end loading and running in Minecraft on a ~2500-class Kotlin
Fabric mod — including class + method + field renaming with config
persistence intact.

> Obfuscation hides implementation detail; it is not a security boundary.
> Always keep the mapping file for every release so you can de-obfuscate
> crash reports.

## Download

Prebuilt Windows app on the [Releases page](../../releases) — `.msi`
installer or portable `.zip` (bundled runtime, no Java needed). Or build
it yourself (see [Build](#build)).

## Intended use

Built to protect **your own** code. Running it on a mod you didn't write
only scrambles someone else's IP and will usually breach that project's
license — don't redistribute obfuscated builds of other people's mods.

## Features

| Pass | What it does |
|------|--------------|
| **Name obfuscation** | Renames classes, methods and fields. Nested classes keep their `Outer$Inner` structure so generic signatures stay valid. Inheritance-aware method grouping. |
| **String encryption** | Per-class polymorphic decoder; nonlinear keyed LCG + xorshift keystream (not recoverable from known plaintext). Random per-class constants and decoder name. |
| **Number obfuscation** | Replaces int/long constants with arithmetic identities. |
| **Control-flow** | Opaque-predicate guards (`flowLevel` 1); level 2 adds polymorphic guards + scattered GOTO chains. |
| **Flatten** (experimental) | Opt-in dispatcher-loop flattening. Sound subset only: skips try/catch, monitors, switches, and methods with written *reference* locals (Kotlin capture cells / `$default` synthetics make those verifier-unsafe). Off by default — **test the obfuscated jar before shipping**. Full reference-local coverage needs a typed-SSA pass and is a deliberate non-goal. |
| **Condy string hiding** (opt-in) | Replaces `ldc "text"` with a `CONSTANT_Dynamic` resolved by an injected bootstrap (`crazy/C`) at link time. No plaintext and no visible decoder call — opaque to `javap -c` and decompilers. Requires class-file v55+ (Java 11); older classes fall back to the inline decoder. Honours targeted-string selection. |
| **Anti-decompile** (opt-in) | Decompiler-confusion pass. Wraps eligible methods (no existing try/catch) in a fake `catch (Throwable) { throw t; }` whose handler is appended at method end. Behaviour-neutral — any exception still propagates with the same trace — but the irreducible exception edge makes CFR/Vineflower/Procyon emit garbage or bail. **Does not hide anything from `javap`**; pair with string/number passes for real secrecy. |
| **Junk code** | Injects unreachable synthetic methods (collision-safe `CRAZY$j` names). |
| **Metadata stripping** | Removes `SourceFile`, line numbers, local-variable tables, parameter names. |
| **Watermarking** | Embeds a build tag + `META-INF/crazy-build.txt` for leak tracing. |
| **Resource encryption** | XOR-encrypts selected jar resources with an injected runtime helper. |
| **Anti-debug** (opt-in) | Injects a JDWP/agent-detection check. |
| **Mapping export** | Proguard-format mapping for crash-report de-obfuscation. |

### Kotlin support

Renaming Kotlin code naively breaks Kotlin reflection. This tool handles it:

- **`@Metadata` rewriting** — parses Kotlin metadata with JetBrains' official
  `kotlin-metadata-jvm` library and remaps every class reference, JVM
  signature descriptor, nested-class and companion name so metadata stays
  consistent with the renamed bytecode.
- **Callable-reference signature patching** — rewrites the hardcoded
  `X::prop` / `X::fun` signature strings the compiler bakes into bytecode.
- **`KotlinCallableRefScanner`** — treats callable-reference targets as
  reflection targets and excludes just those members from renaming.
- **`GsonScanner`** — finds GSON-serialized model classes (TypeToken
  subclasses, `Gson`/`TypeToken.get` call sites) and excludes their fields
  (transitively over field types + superclasses) so persisted JSON keeps
  loading after field renaming.
- **`MixinReferenceScanner`** — excludes own-jar classes that `@Mixin` code
  references (Mixin relocates that code into the target class at load time,
  so a renamed/repackaged helper would become unreachable → `IllegalAccessError`).

### Fabric / Mixin awareness

Auto-excludes from renaming: `@Mixin` classes and `*.mixins.json` targets,
`fabric.mod.json` entry points, manifest `Main-Class`, `META-INF/services`
providers, and `Class.forName` / `getDeclaredField` reflection targets —
resolved through locals/copies by a source-preserving dataflow pass, not
just literal-arg cases. Mixin classes are left byte-for-byte untouched.

## Build

Requires JDK 21.

```bash
./gradlew fatJar      # -> build/libs/crazy-obfuscator-<ver>-all.jar  (runnable CLI)
./gradlew test        # unit tests + an end-to-end obfuscate/verify/run check
```

## CLI usage

```bash
java -jar crazy-obfuscator-all.jar input.jar output.jar \
     --config example.json \
     --root-package com.example.mymod \
     --mapping mapping.txt
```

Flags mirror the config (`--no-strings`, `--flow-level 2`,
`--rewrite-kotlin-metadata`, `--watermark`, `--seed`, `--encrypt-resource`,
…). See `example.json` for every option; CLI flags override the file.

### Verify

```bash
java -cp crazy-obfuscator-all.jar dev.crazy.obf.cli.Verify output.jar
```

Structurally validates every class (ASM `CheckClassAdapter`) and flags
duplicate members — catches malformed output before you ship it.

## Gradle plugin

The jar also exposes a Gradle plugin (`dev.crazy.obfuscator`) with an
`obfuscateJar` task and a `crazyObf { ... }` extension mirroring the config.

## Recommended config for a Kotlin Fabric mod

Start from `example.json` with:

- `rootPackages` = your mod's package(s)
- `rewriteKotlinMetadata: true`
- `renameClasses/Methods/Fields: true`
- `excludeClasses` for any package you reflect into by constructed
  (non-literal) names

First run on a real mod may still surface a reflection pattern the scanners
don't catch — obfuscate, test in-game, add an exclusion if a feature
misbehaves, repeat. There is no obfuscator that handles an arbitrary
reflection-heavy mod with zero tuning.

## Caveats

- Obfuscation ≠ security. Determined attackers can still reverse it.
- Keep the mapping file per release (crash-report de-obfuscation).
- `flowLevel 2` and resource encryption add runtime cost.
- Generic signatures are preserved (needed for GSON `TypeToken`); they do
  leak some type info to a determined reader.

## Desktop app / installer

`jpackage` bundles a trimmed Java runtime, so end users need no Java.

```bash
./gradlew jpackageAppImage   # build/dist/app-image/  — portable, run CrazyObfuscator.exe (GUI)
./gradlew packageZip         # build/dist/CrazyObfuscator-<ver>-windows.zip — one-file distributable
./gradlew jpackageMsi        # build/dist/CrazyObfuscator-<ver>.msi — Windows installer
```

Launched with no arguments the app opens a GUI window; with arguments it
runs as the CLI (`dev.crazy.obf.Launcher` dispatches).

The `.msi` task needs the **WiX 3** toolset (`candle.exe`/`light.exe`) on
PATH — this JDK's `jpackage` does not accept WiX 4/5/7. WiX 3 needs the
.NET 3.5 runtime (already present on most Windows installs). No admin
required if you use the official binary zip instead of the installer:

```powershell
# one-time, no admin:
iwr https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip -OutFile wix3.zip
Expand-Archive wix3.zip tools\wix3
$env:PATH = "$PWD\tools\wix3;$env:PATH"
.\gradlew jpackageMsi
```

The portable zip needs none of this and is the recommended distributable.

### Code signing (optional)

An unsigned `.msi`/`.exe` triggers a Windows SmartScreen warning. To sign
you need a **CA-issued Authenticode code-signing certificate** (a paid
cert from a CA — self-signed gives no trust benefit) and `signtool.exe`
(Windows SDK):

```powershell
.\gradlew signMsi -Pcert=path\to\cert.pfx -PcertPass=secret
```

Without `-Pcert/-PcertPass` the task no-ops (the `.msi` is still built,
just unsigned). The GUI app also supports **drag-and-drop**: drop a
`.jar` onto the window to load it.

## License

MIT — see [LICENSE](LICENSE). Dependency licenses: ASM (BSD-3), Gson
(Apache-2.0), Picocli (Apache-2.0), kotlin-metadata-jvm (Apache-2.0).
