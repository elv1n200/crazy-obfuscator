package dev.crazy.obf.gradle;

import dev.crazy.obf.model.NameGenerator;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public abstract class CrazyObfuscatorExtension {

    @Inject
    public CrazyObfuscatorExtension(Project project) {
        getRenameClasses().convention(true);
        getRenameMethods().convention(true);
        getRenameFields().convention(true);
        getEncryptStrings().convention(true);
        getObfuscateNumbers().convention(true);
        getObfuscateFlow().convention(true);
        getStripMetadata().convention(true);
        getFlattenPackages().convention(true);
        getFlattenedPackage().convention("a");
        getNameStyle().convention(NameGenerator.Style.ALPHA);
        getStringEncryptionChance().convention(100);
        getNumberObfuscationChance().convention(70);
        getFlowLevel().convention(1);
        getSeed().convention(0L);
        getInjectJunk().convention(true);
        getAntiDebug().convention(false);
    }

    public abstract RegularFileProperty getInput();
    public abstract RegularFileProperty getOutput();
    public abstract ListProperty<String> getRootPackages();
    public abstract ListProperty<String> getExcludeClasses();
    public abstract ListProperty<String> getExcludeMembers();

    public abstract Property<Boolean> getRenameClasses();
    public abstract Property<Boolean> getRenameMethods();
    public abstract Property<Boolean> getRenameFields();
    public abstract Property<Boolean> getEncryptStrings();
    public abstract Property<Boolean> getObfuscateNumbers();
    public abstract Property<Boolean> getObfuscateFlow();
    public abstract Property<Boolean> getStripMetadata();
    public abstract Property<Boolean> getFlattenPackages();
    public abstract Property<String>  getFlattenedPackage();
    public abstract Property<NameGenerator.Style> getNameStyle();
    public abstract Property<Integer> getStringEncryptionChance();
    public abstract Property<Integer> getNumberObfuscationChance();
    public abstract Property<Integer> getFlowLevel();
    public abstract Property<Long>    getSeed();

    public abstract RegularFileProperty getMappingOutput();
    public abstract Property<String>   getWatermark();
    public abstract Property<Boolean>  getInjectJunk();
    public abstract Property<Boolean>  getAntiDebug();
    public abstract ListProperty<String> getEncryptResources();
}
