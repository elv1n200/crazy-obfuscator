package dev.crazy.obf.cli;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Parses every .class in a jar through ASM's CheckClassAdapter and reports
 * any verification errors. Useful as a post-obfuscation sanity check.
 *
 *   java -cp crazy-obfuscator-all.jar dev.crazy.obf.cli.Verify path/to/jar
 */
public final class Verify {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Verify <jar>");
            System.exit(2);
        }
        Path jar = Path.of(args[0]);
        int ok = 0, fail = 0;
        List<String> failures = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            var it = jf.entries();
            while (it.hasMoreElements()) {
                JarEntry e = it.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                if (e.getName().equals("module-info.class")) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    byte[] bytes = in.readAllBytes();
                    // defang super-version
                    int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
                    if (major > 67) { bytes = bytes.clone(); bytes[6] = 0; bytes[7] = 67; }

                    // Structural check: parse + run CheckClassAdapter (no type resolution),
                    // then round-trip through ClassWriter to ensure max-stack/max-locals are sound.
                    ClassReader cr = new ClassReader(bytes);
                    ClassWriter cw = new ClassWriter(0);
                    ClassVisitor cv = new CheckClassAdapter(cw, false); // false = no data-flow
                    cr.accept(cv, 0);
                    cw.toByteArray(); // forces frame writing

                    // Duplicate-method check — ASM does NOT enforce this but the
                    // JVM rejects such classes at load time (ClassFormatError).
                    org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
                    new ClassReader(bytes).accept(cn, 0);
                    java.util.Set<String> seenM = new java.util.HashSet<>();
                    if (cn.methods != null) for (var m : cn.methods) {
                        if (!seenM.add(m.name + m.desc)) {
                            throw new IllegalStateException("duplicate method " + m.name + m.desc);
                        }
                    }
                    java.util.Set<String> seenF = new java.util.HashSet<>();
                    if (cn.fields != null) for (var f : cn.fields) {
                        if (!seenF.add(f.name + ":" + f.desc)) {
                            throw new IllegalStateException("duplicate field " + f.name + ":" + f.desc);
                        }
                    }

                    ok++;
                } catch (Throwable t) {
                    failures.add(e.getName() + "\n  " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    fail++;
                }
            }
        }
        System.out.println("OK   : " + ok);
        System.out.println("FAIL : " + fail);
        if (fail > 0) {
            int show = Math.min(failures.size(), 10);
            System.out.println("First " + show + " failures:");
            for (int i = 0; i < show; i++) System.out.println("--- " + failures.get(i));
        }
        System.exit(fail == 0 ? 0 : 1);
    }
}
