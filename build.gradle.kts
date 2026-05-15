plugins {
    `java-library`
    `java-gradle-plugin`
    application
}

group = "dev.crazy"
version = "0.1.0"

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
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("dev.crazy.obf.cli.Main")
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
            "Main-Class" to "dev.crazy.obf.cli.Main",
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
        attributes("Main-Class" to "dev.crazy.obf.cli.Main")
    }
    from(sourceSets.main.get().output) {
        // Exclude the gradle-plugin glue from the CLI fat jar — it depends on Gradle
        // which we don't want to embed for the CLI use case.
        exclude("dev/crazy/obf/gradle/**")
    }
    // Pick the runtime jars we actually need for the CLI.
    val keepNames = listOf("asm", "gson", "picocli", "kotlin-metadata-jvm", "kotlin-stdlib", "annotations")
    val keepFiles = configurations.runtimeClasspath.get().filter { f ->
        keepNames.any { f.name.startsWith(it) } && f.name.endsWith("jar")
    }
    dependsOn(configurations.runtimeClasspath)
    from(keepFiles.map { zipTree(it) })
}

tasks.named("build") { dependsOn("fatJar") }
