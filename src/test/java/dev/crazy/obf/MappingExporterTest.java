package dev.crazy.obf;

import dev.crazy.obf.config.ExclusionRules;
import dev.crazy.obf.config.ObfConfig;
import dev.crazy.obf.io.JarContents;
import dev.crazy.obf.io.MappingExporter;
import dev.crazy.obf.model.ObfContext;
import dev.crazy.obf.model.Remapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class MappingExporterTest {

    @Test
    void emitsProguardFormat(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        ObfContext ctx = new ObfContext(new JarContents(), new ObfConfig(), new ExclusionRules());
        Remapper r = ctx.remapper();
        r.classes.put("cop/Foo", "a/A");
        r.methods.put(Remapper.methodKey("cop/Foo", "doThing", "(ILjava/lang/String;)Ljava/util/List;"), "x");
        r.fields.put(Remapper.fieldKey("cop/Foo", "counter", "I"), "y");

        Path out = tmp.resolve("mapping.txt");
        MappingExporter.export(out, ctx);

        String txt = Files.readString(out);
        assertTrue(txt.contains("cop.Foo -> a.A:"), txt);
        assertTrue(txt.contains("int counter -> y"), txt);
        assertTrue(txt.contains("java.util.List doThing(int,java.lang.String) -> x"), txt);
    }
}
