package dev.crazy.obf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the encrypt/decrypt math for the polymorphic decoder shapes.
 * Formula:
 *
 *   c[i] = c[i] ^ ((salt * mult + i * add) & 0x7FFF)
 *
 * which is its own inverse — encrypt(encrypt(s,k,m,a),k,m,a) == s for any (k,m,a).
 */
public class StringCryptoTest {

    @Test
    void roundTripAscii() {
        String s = "Hello, world!";
        assertEquals(s, encrypt(encrypt(s, 12345, 31, 17), 12345, 31, 17));
    }

    @Test
    void roundTripEmpty() {
        assertEquals("", encrypt(encrypt("", 7, 5, 3), 7, 5, 3));
    }

    @Test
    void roundTripBmp() {
        String s = "こんにちは / привет / مرحبا / שלום";
        for (int salt = 1; salt < 65535; salt += 4096) {
            for (int mult = 3; mult < 250; mult += 71) {
                for (int add = 3; add < 250; add += 71) {
                    assertEquals(s, encrypt(encrypt(s, salt, mult, add), salt, mult, add));
                }
            }
        }
    }

    @Test
    void roundTripLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append((char)('a' + (i % 26)));
        String s = sb.toString();
        assertEquals(s, encrypt(encrypt(s, 30000, 13, 89), 30000, 13, 89));
    }

    @Test
    void encryptedNeverContainsSurrogates() {
        String s = "abcdefghijklmnop";
        for (int salt = 1; salt < 65535; salt += 1024) {
            String enc = encrypt(s, salt, 41, 23);
            for (int i = 0; i < enc.length(); i++) {
                char c = enc.charAt(i);
                assertFalse(Character.isSurrogate(c),
                    "surrogate at salt=" + salt + " idx=" + i + " char=" + Integer.toHexString(c));
            }
        }
    }

    private static String encrypt(String s, int salt, int mult, int add) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) {
            int k = (salt * mult + i * add) & 0x7FFF;
            c[i] = (char) (c[i] ^ k);
        }
        return new String(c);
    }
}
