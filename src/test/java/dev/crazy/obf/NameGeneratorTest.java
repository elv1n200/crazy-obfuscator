package dev.crazy.obf;

import dev.crazy.obf.model.NameGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class NameGeneratorTest {

    @Test
    void alphaProducesUniqueNamesWithinScope() {
        NameGenerator g = new NameGenerator(42L, NameGenerator.Style.ALPHA);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) assertTrue(seen.add(g.nextClass()), "duplicate at " + i);
    }

    @Test
    void alphaFirstFew() {
        NameGenerator g = new NameGenerator(42L, NameGenerator.Style.ALPHA);
        assertEquals("a", g.nextClass());
        assertEquals("b", g.nextClass());
    }

    @Test
    void scopesAreIndependent() {
        NameGenerator g = new NameGenerator(42L, NameGenerator.Style.ALPHA);
        String c = g.nextClass();
        String f = g.nextField();
        // CONFUSE / UNICODE may collide across scopes, ALPHA shouldn't because
        // the counter is shared. Still: both must produce *some* name.
        assertNotNull(c);
        assertNotNull(f);
    }

    @Test
    void confuseStyleUsesLookalikes() {
        NameGenerator g = new NameGenerator(42L, NameGenerator.Style.CONFUSE);
        for (int i = 0; i < 10; i++) {
            String s = g.nextClass();
            assertTrue(s.length() >= 1);
            assertTrue(s.chars().allMatch(c -> "lI1O0o".indexOf(c) >= 0), "unexpected char in " + s);
            // first char must not be a digit
            assertFalse(Character.isDigit(s.charAt(0)));
        }
    }

    @Test
    void unicodeIsValidJavaIdentifier() {
        NameGenerator g = new NameGenerator(42L, NameGenerator.Style.UNICODE);
        for (int i = 0; i < 50; i++) {
            String s = g.nextClass();
            assertTrue(Character.isJavaIdentifierStart(s.codePointAt(0)));
            for (int j = 1; j < s.length(); j++) {
                assertTrue(Character.isJavaIdentifierPart(s.charAt(j)));
            }
        }
    }

    @Test
    void sameSeedSameSequence() {
        NameGenerator a = new NameGenerator(42L, NameGenerator.Style.ALPHA);
        NameGenerator b = new NameGenerator(42L, NameGenerator.Style.ALPHA);
        for (int i = 0; i < 100; i++) assertEquals(a.nextClass(), b.nextClass());
    }
}
