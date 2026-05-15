package dev.crazy.obf.io;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import dev.crazy.obf.model.ObfContext;

public final class JarWriter {

    private JarWriter() {}

    public static void write(Path output, JarContents contents, ObfContext ctx) throws IOException {
        Files.createDirectories(output.getParent() == null ? Path.of(".") : output.getParent());
        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(output));
             JarOutputStream jos = new JarOutputStream(fos)) {

            // Directories first so jar -t shows them and so old enumeration
            // tools (Class.getResources() on some loaders) still find packages.
            Set<String> dirs = new TreeSet<>();
            for (var cn : contents.classes().values()) addDirsFor(dirs, cn.name + ".class");
            for (String name : contents.resources().keySet()) addDirsFor(dirs, name);
            Set<String> writtenDirs = new HashSet<>();
            for (String d : dirs) {
                if (!writtenDirs.add(d)) continue;
                JarEntry je = new JarEntry(d);
                Long t = contents.entryTimes().get(d);
                if (t != null) je.setTime(t);
                jos.putNextEntry(je);
                jos.closeEntry();
            }

            // classes
            for (ClassNode cn : contents.classes().values()) {
                ClassWriter cw = new SafeClassWriter(ctx);
                cn.accept(cw);
                byte[] bytes = cw.toByteArray();
                JarEntry je = new JarEntry(cn.name + ".class");
                Long t = contents.entryTimes().get(cn.name + ".class");
                if (t != null) je.setTime(t);
                jos.putNextEntry(je);
                jos.write(bytes);
                jos.closeEntry();
            }

            // resources
            for (Map.Entry<String, byte[]> e : contents.resources().entrySet()) {
                JarEntry je = new JarEntry(e.getKey());
                Long t = contents.entryTimes().get(e.getKey());
                if (t != null) je.setTime(t);
                jos.putNextEntry(je);
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
    }

    private static void addDirsFor(Set<String> sink, String entryName) {
        int idx = entryName.indexOf('/');
        while (idx >= 0) {
            sink.add(entryName.substring(0, idx + 1));
            idx = entryName.indexOf('/', idx + 1);
        }
    }

    /**
     * ClassWriter that resolves common super-classes through the obfuscation context
     * instead of the system class loader. This is required because renamed classes
     * are not on the classpath at write time, and the default getCommonSuperClass
     * implementation tries to load them and fails.
     */
    static final class SafeClassWriter extends ClassWriter {
        private final ObfContext ctx;
        SafeClassWriter(ObfContext ctx) { super(ClassWriter.COMPUTE_FRAMES); this.ctx = ctx; }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Throwable t) {
                return ctx.commonSuperClass(type1, type2);
            }
        }
    }
}
