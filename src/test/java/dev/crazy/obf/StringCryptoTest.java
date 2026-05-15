package dev.crazy.obf;

import dev.crazy.obf.transform.StringEncryptionTransformer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the nonlinear LCG+xorshift keystream is its own inverse for any
 * (salt, a, c) — i.e. transform(transform(s)) == s — and that the ciphertext
 * never contains UTF-16 surrogates (so it round-trips through the constant
 * pool). Calls the real {@link StringEncryptionTransformer#transform} so any
 * drift between it and the injected bytecode decoder shows up as a failing
 * round-trip in {@code EndToEndTest} (which loads + runs an obfuscated jar).
 */
public class StringCryptoTest {

    private static String t(String s, int salt, int a, int c) {
        return StringEncryptionTransformer.transform(s, salt, a, c);
    }

    @Test
    void roundTripAscii() {
        String s = "Hello, world!";
        assertEquals(s, t(t(s, 12345, 0x9E3779B1, 0x6D2B79F5), 12345, 0x9E3779B1, 0x6D2B79F5));
    }

    @Test
    void roundTripEmpty() {
        assertEquals("", t(t("", 7, 5 | 1, 3), 7, 5 | 1, 3));
    }

    @Test
    void roundTripBmpAcrossManyKeys() {
        String s = "こんにちは / привет / مرحبا / שלום / 0123";
        int[] salts = { Integer.MIN_VALUE, -1, 0, 1, 1337, Integer.MAX_VALUE };
        for (int salt : salts) {
            for (int a = 3; a > 0 && a < 2_000_000_000; a += 99_999_991) {
                int odd = a | 1;
                for (int c = -5; c <= 5; c++) {
                    assertEquals(s, t(t(s, salt, odd, c), salt, odd, c),
                        "salt=" + salt + " a=" + odd + " c=" + c);
                }
            }
        }
    }

    @Test
    void roundTripLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append((char) ('a' + (i % 26)));
        String s = sb.toString();
        assertEquals(s, t(t(s, 30000, 0x85EBCA77, 0xC2B2AE3D), 30000, 0x85EBCA77, 0xC2B2AE3D));
    }

    @Test
    void encryptedNeverContainsSurrogates() {
        String s = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int salt = -3; salt <= 3; salt++) {
            String enc = t(s, salt, 0x27D4EB2F | 1, 0x165667B1);
            for (int i = 0; i < enc.length(); i++) {
                assertFalse(Character.isSurrogate(enc.charAt(i)),
                    "surrogate at salt=" + salt + " idx=" + i);
            }
        }
    }
}
