package dev.crazy.obf;

import dev.crazy.obf.config.ObfConfig;
import dev.crazy.obf.transform.StringEncryptionTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/** Targeted string encryption: only matching literals are hidden. */
public class TargetedStringTest {

    @Test
    void predicate_exactAndRegex() {
        var exact = Set.of("LITERAL");
        var pats  = List.of(Pattern.compile("^https?://"), Pattern.compile("token"));
        assertTrue(StringEncryptionTransformer.isTargeted("LITERAL", exact, pats));
        assertTrue(StringEncryptionTransformer.isTargeted("https://api.x/y", exact, pats));
        assertTrue(StringEncryptionTransformer.isTargeted("my secret token here", exact, pats));
        assertFalse(StringEncryptionTransformer.isTargeted("hello world", exact, pats));
        assertFalse(StringEncryptionTransformer.isTargeted("ftp://x", exact, pats));
    }

    @Test
    void onlyTheUrlIsEncrypted(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        final String URL   = "https://secret.example.com/api";
        final String PLAIN = "just a normal message";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "t2/Foo", null, "java/lang/Object", null);
        var ct = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ct.visitVarInsn(Opcodes.ALOAD, 0);
        ct.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ct.visitInsn(Opcodes.RETURN); ct.visitMaxs(0, 0);
        var u = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "url", "()Ljava/lang/String;", null, null);
        u.visitLdcInsn(URL); u.visitInsn(Opcodes.ARETURN); u.visitMaxs(0, 0);
        var p = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "plain", "()Ljava/lang/String;", null, null);
        p.visitLdcInsn(PLAIN); p.visitInsn(Opcodes.ARETURN); p.visitMaxs(0, 0);
        cw.visitEnd();

        Path in = tmp.resolve("in.jar");
        try (var os = Files.newOutputStream(in); JarOutputStream j = new JarOutputStream(os, new Manifest())) {
            j.putNextEntry(new JarEntry("t2/Foo.class")); j.write(cw.toByteArray()); j.closeEntry();
        }

        Path out = tmp.resolve("out.jar");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = List.of("t2");
        cfg.renameClasses = false; cfg.renameMethods = false; cfg.flattenPackages = false;
        cfg.encryptStrings = true;
        cfg.stringEncryptionChance = 0;                 // global off — targeted only
        cfg.encryptStringsMatching = List.of("^https?://");
        cfg.seed = 3L;
        CrazyObfuscator.run(in, out, cfg, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        // Inspect the DECOMPRESSED class entry (jar entries are deflated, so
        // scanning raw jar bytes would never find any plaintext).
        byte[] cls;
        try (var jf = new java.util.jar.JarFile(out.toFile());
             var is = jf.getInputStream(jf.getEntry("t2/Foo.class"))) {
            cls = is.readAllBytes();
        }
        String raw = new String(cls, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(URL),   "the URL literal must be gone from the class");
        assertTrue (raw.contains(PLAIN), "non-targeted strings must stay plaintext");

        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{out.toUri().toURL()},
                                                  TargetedStringTest.class.getClassLoader())) {
            Class<?> foo = cl.loadClass("t2.Foo");
            assertEquals(URL,   foo.getMethod("url").invoke(null),   "URL must decode back at runtime");
            assertEquals(PLAIN, foo.getMethod("plain").invoke(null), "plain string unchanged");
        }
    }
}
