package dev.crazy.obf.gradle;

import dev.crazy.obf.CrazyObfuscator;
import dev.crazy.obf.config.ObfConfig;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import java.nio.file.Path;

public abstract class ObfuscateJarTask extends DefaultTask {

    @Internal public abstract Property<CrazyObfuscatorExtension> getExtensionRef();

    @TaskAction
    public void run() throws Exception {
        CrazyObfuscatorExtension ext = getExtensionRef().get();
        Path in  = ext.getInput().get().getAsFile().toPath();
        Path out = ext.getOutput().get().getAsFile().toPath();

        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages    = ext.getRootPackages().getOrElse(java.util.List.of());
        cfg.excludeClasses  = ext.getExcludeClasses().getOrElse(java.util.List.of());
        cfg.excludeMembers  = ext.getExcludeMembers().getOrElse(java.util.List.of());
        cfg.renameClasses   = ext.getRenameClasses().get();
        cfg.renameMethods   = ext.getRenameMethods().get();
        cfg.renameFields    = ext.getRenameFields().get();
        cfg.encryptStrings  = ext.getEncryptStrings().get();
        cfg.obfuscateNumbers = ext.getObfuscateNumbers().get();
        cfg.obfuscateFlow   = ext.getObfuscateFlow().get();
        cfg.stripMetadata   = ext.getStripMetadata().get();
        cfg.flattenPackages = ext.getFlattenPackages().get();
        cfg.flattenedPackage = ext.getFlattenedPackage().get();
        cfg.nameStyle       = ext.getNameStyle().get();
        cfg.stringEncryptionChance = ext.getStringEncryptionChance().get();
        cfg.numberObfuscationChance = ext.getNumberObfuscationChance().get();
        cfg.flowLevel       = ext.getFlowLevel().get();
        cfg.seed            = ext.getSeed().get();
        if (ext.getMappingOutput().isPresent()) {
            cfg.mappingOutput = ext.getMappingOutput().get().getAsFile().getAbsolutePath();
        }
        if (ext.getWatermark().isPresent())  cfg.watermark = ext.getWatermark().get();
        cfg.injectJunk      = ext.getInjectJunk().get();
        cfg.antiDebug       = ext.getAntiDebug().get();
        if (ext.getEncryptResources().isPresent()) {
            cfg.encryptResources = new java.util.ArrayList<>(ext.getEncryptResources().get());
        }

        CrazyObfuscator.run(in, out, cfg, System.out);
    }
}
