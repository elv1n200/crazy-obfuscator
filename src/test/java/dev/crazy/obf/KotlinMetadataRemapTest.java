package dev.crazy.obf;

import dev.crazy.obf.model.Remapper;
import dev.crazy.obf.transform.KotlinMetadataRemapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class KotlinMetadataRemapTest {

    private Remapper r() {
        Remapper r = new Remapper();
        r.classes.put("cop/module/impl/render/ForceDungeons", "cop/module/impl/render/bvp");
        r.classes.put("cop/Foo$Bar", "cop/x");
        r.classes.put("cop/Outer", "cop/o");
        return r;
    }

    @Test
    void topLevelClassName() {
        assertEquals("cop/module/impl/render/bvp",
            KotlinMetadataRemapper.remapClassName("cop/module/impl/render/ForceDungeons", r()));
    }

    @Test
    void nestedClassNameUsesDotSeparator() {
        // kotlin-metadata ClassName uses '.' for nesting: cop/Foo.Bar  <-> JVM cop/Foo$Bar
        assertEquals("cop/x", KotlinMetadataRemapper.remapClassName("cop/Foo.Bar", r()));
    }

    @Test
    void unmappedNameUnchanged() {
        assertEquals("kotlin/collections/List",
            KotlinMetadataRemapper.remapClassName("kotlin/collections/List", r()));
    }

    @Test
    void localClassPrefixPreserved() {
        Remapper rr = r();
        rr.classes.put("cop/Outer$1", "cop/q");
        assertEquals(".cop/q", KotlinMetadataRemapper.remapClassName(".cop/Outer.1", rr));
    }

    @Test
    void callableRefSignatureWithLInMethodName() {
        // Regression: getter name "getLeapMode" contains an uppercase 'L'.
        // A naive L..; scan mis-parses; must split at '(' first.
        Remapper rr = new Remapper();
        rr.classes.put("cop/module/settings/impl/SelectorComponent", "cop/module/settings/impl/jk");
        String in  = "getLeapMode()Lcop/module/settings/impl/SelectorComponent;";
        String out = "getLeapMode()Lcop/module/settings/impl/jk;";
        assertEquals(out, dev.crazy.obf.transform.KotlinCallableRefRemapper.remapEmbeddedTypes(in, rr));
    }

    @Test
    void callableRefSignatureLeavesUnmappedAndPrimitives() {
        Remapper rr = new Remapper();
        assertEquals("getClearName()Ljava/lang/String;",
            dev.crazy.obf.transform.KotlinCallableRefRemapper.remapEmbeddedTypes(
                "getClearName()Ljava/lang/String;", rr));
        assertEquals("setLevel(I)V",
            dev.crazy.obf.transform.KotlinCallableRefRemapper.remapEmbeddedTypes("setLevel(I)V", rr));
    }

    @Test
    void descriptorRemapsObjectTypes() {
        String in  = "(Lcop/Outer;ILjava/lang/String;)Lcop/Foo$Bar;";
        String out = "(Lcop/o;ILjava/lang/String;)Lcop/x;";
        assertEquals(out, KotlinMetadataRemapper.remapDescriptor(in, r()));
    }

    @Test
    void descriptorWithArrayAndPrimitivesUnchangedWhereNotMapped() {
        String in = "([ILjava/util/List;)Z";
        assertEquals(in, KotlinMetadataRemapper.remapDescriptor(in, r()));
    }

    @Test
    void nullAndEmptySafe() {
        assertNull(KotlinMetadataRemapper.remapClassName(null, r()));
        assertEquals("", KotlinMetadataRemapper.remapClassName("", r()));
        assertEquals("()V", KotlinMetadataRemapper.remapDescriptor("()V", r()));
    }
}
