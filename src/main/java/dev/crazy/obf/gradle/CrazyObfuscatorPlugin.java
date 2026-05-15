package dev.crazy.obf.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public final class CrazyObfuscatorPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        CrazyObfuscatorExtension ext = project.getExtensions()
            .create("crazyObf", CrazyObfuscatorExtension.class, project);

        project.getTasks().register("obfuscateJar", ObfuscateJarTask.class, t -> {
            t.setGroup("crazy-obfuscator");
            t.setDescription("Run crazy-obfuscator on the produced .jar (input/output settable on the extension).");
            t.getExtensionRef().set(ext);
        });
    }
}
