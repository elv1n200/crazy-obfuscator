package dev.crazy.obf.io;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class JarContents {

    private final Map<String, ClassNode> classes = new LinkedHashMap<>();
    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private final Map<String, Long> entryTimes = new LinkedHashMap<>();

    public static JarContents read(Path jarPath) throws IOException {
        JarContents c = new JarContents();
        try (JarFile jf = new JarFile(jarPath.toFile())) {
            var it = jf.entries();
            while (it.hasMoreElements()) {
                JarEntry e = it.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    byte[] bytes = in.readAllBytes();
                    c.entryTimes.put(e.getName(), e.getTime());
                    if (e.getName().endsWith(".class") && !e.getName().equals("module-info.class")) {
                        try {
                            // Defang the major version so ASM accepts class files newer
                            // than the version it knows about. We restore on write via
                            // ClassWriter.toByteArray (it uses the version it sees, so
                            // we keep the original here).
                            byte[] toParse = bytes;
                            int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
                            if (major > 67) { // ASM 9.8 supports up to v68 (Java 24); be safe
                                toParse = bytes.clone();
                                toParse[6] = 0;
                                toParse[7] = 67;
                            }
                            ClassReader cr = new ClassReader(toParse);
                            ClassNode cn = new ClassNode();
                            cr.accept(cn, 0);
                            if (toParse != bytes) {
                                cn.version = major; // restore original target version on output
                            }
                            c.classes.put(cn.name, cn);
                        } catch (Throwable t) {
                            // unparseable .class — keep as resource so we don't lose it
                            System.err.println("[crazy] could not parse " + e.getName() + ": " + t.getMessage());
                            c.resources.put(e.getName(), bytes);
                        }
                    } else {
                        c.resources.put(e.getName(), bytes);
                    }
                }
            }
        }
        return c;
    }

    public Map<String, ClassNode> classes() { return classes; }
    public Map<String, byte[]> resources() { return resources; }
    public Map<String, Long> entryTimes() { return entryTimes; }

    public byte[] resource(String name) { return resources.get(name); }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    public static byte[] readAll(Path p) throws IOException { return Files.readAllBytes(p); }
}
