package dev.crazy.obf;

import dev.crazy.obf.config.ExclusionRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ExclusionRulesTest {

    @Test
    void exactClassMatch() {
        ExclusionRules r = new ExclusionRules();
        r.addClass("cop/Main");
        assertTrue(r.isClassExcluded("cop/Main"));
        assertFalse(r.isClassExcluded("cop/Other"));
    }

    @Test
    void singleStarStopsAtSlash() {
        ExclusionRules r = new ExclusionRules();
        r.addClass("cop/*");
        assertTrue(r.isClassExcluded("cop/Foo"));
        assertFalse(r.isClassExcluded("cop/sub/Foo"));
    }

    @Test
    void doubleStarCrossesSlash() {
        ExclusionRules r = new ExclusionRules();
        r.addClass("cop/**");
        assertTrue(r.isClassExcluded("cop/Foo"));
        assertTrue(r.isClassExcluded("cop/sub/Foo"));
        assertTrue(r.isClassExcluded("cop/very/deep/Foo"));
        assertFalse(r.isClassExcluded("other/Foo"));
    }

    @Test
    void suffixGlob() {
        ExclusionRules r = new ExclusionRules();
        r.addClass("**Mixin");
        assertTrue(r.isClassExcluded("cop/mixins/CameraMixin"));
        assertTrue(r.isClassExcluded("OtherMixin"));
        assertFalse(r.isClassExcluded("Camera"));
    }

    @Test
    void memberGlobMatchesAnyOwner() {
        ExclusionRules r = new ExclusionRules();
        r.addMember("**#serialize");
        assertTrue(r.isMemberExcluded("cop/config/Settings", "serialize"));
        assertTrue(r.isMemberExcluded("other/Foo", "serialize"));
        assertFalse(r.isMemberExcluded("cop/config/Settings", "deserialize"));
    }

    @Test
    void noTouchAlsoCountsAsNoRename() {
        ExclusionRules r = new ExclusionRules();
        r.addNoTouch("cop/mixins/Foo");
        assertTrue(r.isClassNoTouch("cop/mixins/Foo"));
        assertTrue(r.isClassExcluded("cop/mixins/Foo"));
    }

    @Test
    void preserveStringsRoundTrip() {
        ExclusionRules r = new ExclusionRules();
        r.preserveString("foo.bar.Baz");
        assertTrue(r.shouldPreserveString("foo.bar.Baz"));
        assertFalse(r.shouldPreserveString("other"));
    }
}
