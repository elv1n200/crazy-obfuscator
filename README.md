# Crazy Obfuscator

Java/Fabric `.jar` obfuscator. Built for the **COP** Skyblock mod but works on any
plain Java jar. Inspired by Zelix KlassMaster — does the same kinds of transforms
on a smaller scale.

## What it does

| Pass       | What it does                                                                       |
|------------|------------------------------------------------------------------------------------|
| resenc     | XOR-encrypts matching resources, injects `crazy.R.read(path)` helper               |
| strings    | Per-class polymorphic decoder. 3 shapes × random (mult,add) — defeats pattern match |
| numbers    | Replaces int/long constants with `(N^k1)^k1 + k2 - k2` expressions                 |
| flow       | Opaque predicate at every method entry. Level 2: 3 shapes + scattered GOTO chain   |
| junk       | Adds 2-5 synthetic dead methods per class                                          |
| names      | Renames classes, methods, fields in your packages to short opaque IDs              |
| antidebug  | Optional: throws if JDWP/javaagent flags are present at startup                    |
| watermark  | Embeds `crazy/W` class + `META-INF/crazy-build.txt` with tag, seed, timestamp      |
| strip      | Removes SourceFile, LineNumberTable, LocalVariableTable, parameters                |
| (mapping)  | Writes Proguard-format mapping file for stack-trace deobfuscation                  |

Fabric-aware: scans `fabric.mod.json`, every `*.mixins.json`, every `@Mixin`
class, the manifest `Main-Class`, every `META-INF/services/*`, and every
`Class.forName(...) / getDeclaredField(...) / getDeclaredMethod(...)` literal —
and automatically excludes anything those resolve to.

## Build

```powershell
cd "C:\Users\elvin\Downloads\Crazy Java tool"
.\gradlew.bat fatJar
# -> build\libs\crazy-obfuscator-0.1.0-all.jar   (CLI)
# -> build\libs\crazy-obfuscator-0.1.0-plain.jar (Gradle plugin)
```

## CLI usage

```powershell
java -jar build\libs\crazy-obfuscator-0.1.0-all.jar `
     input.jar output.jar `
     --root-package com.your.mod `
     --seed 12345
```

Common flags:

| Flag                          | Meaning                                          |
|-------------------------------|--------------------------------------------------|
| `-p, --root-package <pkg>`    | Your root package(s). Required for renaming.     |
| `-c, --config <file>`         | JSON config (every option settable)              |
| `--no-names` `--no-strings` ` --no-numbers` `--no-flow` `--no-strip` | Per-pass kill-switches |
| `--no-flatten`                | Keep original package structure                  |
| `--name-style ALPHA|CONFUSE|UNICODE` | Identifier style for new names            |
| `--seed <long>`               | Reproducible builds                              |
| `-v, --verbose`               | Extra logging                                    |
| `-m, --mapping <path>`        | Write Proguard mapping file                      |
| `--watermark <tag>`           | Embed tag in jar for leak tracking               |
| `--no-junk`                   | Disable junk-method injection                    |
| `--anti-debug`                | Inject JDWP/agent detection (off by default)     |
| `--flow-level 0|1|2`          | Off / uniform predicate / polymorphic + GOTO     |
| `--encrypt-resource <glob>`   | Encrypt jar resources. Repeatable                |

Example config (`crazy.json`):

```json
{
  "rootPackages": ["cop"],
  "renameClasses": true,
  "renameMethods": true,
  "renameFields": true,
  "encryptStrings": true,
  "obfuscateNumbers": true,
  "obfuscateFlow": true,
  "stripMetadata": true,
  "flattenPackages": true,
  "flattenedPackage": "a",
  "nameStyle": "ALPHA",
  "stringEncryptionChance": 100,
  "numberObfuscationChance": 70,
  "flowLevel": 1,
  "excludeClasses": [
    "dev/cop/api/**"
  ],
  "excludeMembers": [
    "dev/cop/config/Settings#serialize"
  ]
}
```

## Gradle plugin usage (COP)

In `C:\Users\elvin\Downloads\COP\settings.gradle.kts` add an `includeBuild`:

```kotlin
includeBuild("../Crazy Java tool") {
    dependencySubstitution {
        substitute(module("dev.crazy:crazy-obfuscator")).using(project(":"))
    }
}
```

Or install the plugin into a local maven and use `id("dev.crazy.obfuscator")`.
Simpler day-to-day approach: just run the CLI after `gradle build`. Add this
task to your COP `build.gradle.kts`:

```kotlin
val obfuscatorJar = file("../Crazy Java tool/build/libs/crazy-obfuscator-0.1.0-all.jar")

tasks.register<JavaExec>("obfuscateJar") {
    group = "build"
    description = "Run Crazy Obfuscator on the built mod jar"
    dependsOn("build")
    classpath = files(obfuscatorJar)
    val input  = layout.buildDirectory.file("libs/${project.name}-${version}.jar")
    val output = layout.buildDirectory.file("libs/${project.name}-${version}-obf.jar")
    args(input.get().asFile.absolutePath,
         output.get().asFile.absolutePath,
         "--root-package", "cop",
         "--seed", "42")
}
```

Then `gradlew obfuscateJar` produces an obfuscated mod jar.

## Fabric / Mixin caveats

This is the part that ZKM doesn't have to worry about because it's not modding
Minecraft. Read this before you ship.

1. **Mixin classes are skipped entirely.** Anything annotated `@Mixin` (or
   listed in a `*.mixins.json`) is no-touch — mixin processor needs the original
   bytecode. Strings/numbers/flow are not transformed inside them either.
2. **Fabric entry points are not renamed**, but their internals (strings,
   numbers, flow) are still obfuscated. The class name stays so the loader can
   find them.
3. **Reflection on your own classes** that uses a *non-literal* string (e.g.
   field name built from concatenation) will NOT be detected. If you reflect on
   your own fields, either:
   - add the field name to `excludeMembers`, or
   - keep the reflection target string as a single LDC literal in your code so
     the scanner sees it.
4. **GSON-serialized config classes** must have `excludeClasses` entries — GSON
   uses the original field names as JSON keys.
5. **First run will probably break something.** That's expected. Run, see what
   blows up, add to exclusion list, repeat. There is no obfuscator on the planet
   that handles a Fabric mod with zero manual tuning.
6. **You must keep an unobfuscated copy** of every release. If a user reports a
   stack trace from an obfuscated jar, you'll need the mapping to read it. The
   tool prints all mappings to stdout — pipe to a file and store it next to the
   jar.

## What this does NOT do

- Heavy CFG flattening (switch-encoded jumps with stack-machine dispatcher)
- Native method extraction
- Class-file splitting / lazy loading
- Mixin processor integration (we skip mixin classes entirely)

These are real ZKM features. If you actually need them, tell me which and I'll
add them. They aren't necessary for the "anti-leak / anti-copy" use case — the
five passes above already produce output that is unpleasant to read.

## License

The obfuscator itself is yours to use. Mind the licenses of dependencies:
ASM (BSD-3), Gson (Apache-2.0), Picocli (Apache-2.0).
