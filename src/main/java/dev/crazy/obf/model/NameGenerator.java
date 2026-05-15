package dev.crazy.obf.model;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** Generates short, JVM-legal, hard-to-read identifiers. */
public final class NameGenerator {

    public enum Style {
        /** a, b, c, ... aa, ab, ... — short and clean */
        ALPHA,
        /** Visually confusable lookalikes: l, I, 1, O, 0, o using lI1O0o */
        CONFUSE,
        /** Single non-printing-ish unicode chars (deobfuscator-hostile, may break some tools) */
        UNICODE
    }

    private final Random rng;
    private final Style style;
    private final Set<String> used = new HashSet<>();
    private long counter;

    public NameGenerator(long seed, Style style) {
        this.rng = new Random(seed);
        this.style = style == null ? Style.ALPHA : style;
    }

    public synchronized String next(String scope) {
        for (int i = 0; i < 1_000_000; i++) {
            String n = generate();
            String key = scope + "::" + n;
            if (used.add(key)) return n;
        }
        throw new IllegalStateException("name space exhausted");
    }

    public synchronized String nextPackage() { return next("PKG"); }
    public synchronized String nextClass() { return next("CLS"); }
    public synchronized String nextMethod() { return next("MTH"); }
    public synchronized String nextField() { return next("FLD"); }

    private String generate() {
        return switch (style) {
            case ALPHA -> alpha(counter++);
            case CONFUSE -> confuse(6);
            case UNICODE -> unicode(3);
        };
    }

    private static String alpha(long n) {
        StringBuilder sb = new StringBuilder();
        n++;
        while (n > 0) {
            n--;
            sb.append((char) ('a' + (n % 26)));
            n /= 26;
        }
        return sb.reverse().toString();
    }

    private String confuse(int len) {
        char[] alpha = {'l', 'I', 'O', 'o'}; // first char can't be digit
        char[] all   = {'l', 'I', '1', 'O', '0', 'o'};
        StringBuilder sb = new StringBuilder(len);
        sb.append(alpha[rng.nextInt(alpha.length)]);
        for (int i = 1; i < len; i++) sb.append(all[rng.nextInt(all.length)]);
        return sb.toString();
    }

    private String unicode(int len) {
        StringBuilder sb = new StringBuilder(len);
        // Cherokee letter range — all are valid Java identifier chars and look identical to many fonts.
        int base = 0x13A0;
        for (int i = 0; i < len; i++) sb.append((char) (base + rng.nextInt(85)));
        return sb.toString();
    }
}
