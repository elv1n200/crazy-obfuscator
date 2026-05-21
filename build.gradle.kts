plugins {
    `java-library`
    `java-gradle-plugin`
    application
}

group = "dev.crazy"
version = "0.7.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.7.1")
    implementation("org.ow2.asm:asm-commons:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")
    implementation("org.ow2.asm:asm-analysis:9.7.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.21")
    implementation("com.formdev:flatlaf:3.5.4")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("dev.crazy.obf.Launcher")
}

gradlePlugin {
    plugins {
        create("crazyObfuscator") {
            id = "dev.crazy.obfuscator"
            implementationClass = "dev.crazy.obf.gradle.CrazyObfuscatorPlugin"
        }
    }
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "dev.crazy.obf.Launcher",
            "Implementation-Title" to "Crazy Obfuscator",
            "Implementation-Version" to project.version
        )
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "dev.crazy.obf.Launcher")
    }
    from(sourceSets.main.get().output) {
        // Exclude the gradle-plugin glue from the CLI fat jar — it depends on Gradle
        // which we don't want to embed for the CLI use case.
        exclude("dev/crazy/obf/gradle/**")
    }
    // Pick the runtime jars we actually need for the CLI.
    val keepNames = listOf("asm", "gson", "picocli", "kotlin-metadata-jvm", "kotlin-stdlib", "annotations", "flatlaf")
    val keepFiles = configurations.runtimeClasspath.get().filter { f ->
        keepNames.any { f.name.startsWith(it) } && f.name.endsWith("jar")
    }
    dependsOn(configurations.runtimeClasspath)
    from(keepFiles.map { zipTree(it) })
}

tasks.named("build") { dependsOn("fatJar") }

// ---------------------------------------------------------------------------
// Native packaging via jpackage (bundles a trimmed Java runtime; the end user
// needs no Java installed). app-image = portable folder + .exe (no extra
// tooling). msi = Windows installer (requires WiX Toolset on PATH).
// ---------------------------------------------------------------------------

val jpkgInput = layout.buildDirectory.dir("jpackage-input")
val jpkgDest  = layout.buildDirectory.dir("dist")

val prepareJpackageInput by tasks.registering(Copy::class) {
    dependsOn("fatJar")
    from(layout.buildDirectory.file("libs/crazy-obfuscator-${project.version}-all.jar"))
    into(jpkgInput)
    rename { "crazy-obfuscator.jar" }
}

fun jpackageArgs(type: String, dest: java.io.File): List<String> {
    val a = mutableListOf(
        "--type", type,
        "--name", "CrazyObfuscator",
        "--app-version", project.version.toString(),
        "--vendor", "elv1n200",
        "--description", "Crazy Obfuscator — Java/Kotlin/Fabric .jar obfuscator",
        "--input", jpkgInput.get().asFile.absolutePath,
        "--main-jar", "crazy-obfuscator.jar",
        "--main-class", "dev.crazy.obf.Launcher",
        "--dest", dest.absolutePath,
        "--java-options", "-XX:+UseParallelGC",
        "--add-modules", "java.base,java.desktop",
    )
    if (type == "msi") {
        a += listOf("--win-menu", "--win-shortcut", "--win-dir-chooser",
                    "--win-menu-group", "Crazy Obfuscator")
    }
    return a
}

val jpackageAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Portable standalone .exe folder (no WiX, no Java needed by the user)"
    dependsOn(prepareJpackageInput)
    val dest = jpkgDest.get().dir("app-image").asFile
    doFirst { delete(dest); dest.mkdirs() }   // jpackage refuses to overwrite
    commandLine(listOf("jpackage") + jpackageArgs("app-image", dest))
}

val jpackageMsi by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Windows .msi installer (requires WiX 3 candle/light on PATH)"
    dependsOn(prepareJpackageInput)
    val dest = jpkgDest.get().asFile
    doFirst { dest.mkdirs() }
    commandLine(listOf("jpackage") + jpackageArgs("msi", dest))
}

// Authenticode-sign the .msi. Real trust needs a CA-issued code-signing
// certificate (you supply it) and signtool.exe (Windows SDK). Without
// both this no-ops with guidance — it never fails the build. Usage:
//   gradlew signMsi -Pcert=path\to\cert.pfx -PcertPass=secret \
//                    [-Psigntool=path\to\signtool.exe]
val signMsi by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Authenticode-sign the .msi (needs -Pcert/-PcertPass + signtool)"
    dependsOn(jpackageMsi)
    isIgnoreExitValue = true
    doFirst {
        val cert = (project.findProperty("cert") ?: System.getenv("CRAZY_CERT")) as String?
        val pass = (project.findProperty("certPass") ?: System.getenv("CRAZY_CERT_PASS")) as String?
        val st   = (project.findProperty("signtool") ?: "signtool") as String
        val msi  = jpkgDest.get().file("CrazyObfuscator-${project.version}.msi").asFile
        if (cert == null || pass == null) {
            logger.lifecycle("signMsi: no -Pcert/-PcertPass (or CRAZY_CERT/_PASS) — " +
                "skipping. The .msi is built but unsigned; SmartScreen will warn users. " +
                "Supply a CA-issued code-signing .pfx to sign.")
            commandLine("cmd", "/c", "echo skipped")
        } else {
            commandLine(st, "sign", "/fd", "SHA256",
                "/f", cert, "/p", pass,
                "/tr", "http://timestamp.digicert.com", "/td", "SHA256",
                msi.absolutePath)
        }
    }
}

// One-file distributable: zip the portable GUI app (no installer / WiX
// needed). User unzips and runs CrazyObfuscator.exe — opens the window.
val packageZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zip the portable GUI app for distribution"
    dependsOn(jpackageAppImage)
    from(jpkgDest.get().dir("app-image"))
    archiveFileName.set("CrazyObfuscator-${project.version}-windows.zip")
    destinationDirectory.set(jpkgDest)
}
