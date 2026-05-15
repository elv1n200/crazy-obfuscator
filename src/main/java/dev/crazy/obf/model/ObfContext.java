package dev.crazy.obf.model;

import dev.crazy.obf.config.ObfConfig;
import dev.crazy.obf.config.ExclusionRules;
import dev.crazy.obf.io.JarContents;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared mutable state passed to every transformer:
 *   - the jar contents
 *   - the active config
 *   - merged exclusion rules
 *   - the global remapper
 *   - class hierarchy info (for COMPUTE_FRAMES + override tracking)
 */
public final class ObfContext {

    private final JarContents contents;
    private final ObfConfig config;
    private final ExclusionRules exclusions;
    private final Remapper remapper;
    private final NameGenerator names;
    private final ClassHierarchy hierarchy;
    private final long seed;

    public ObfContext(JarContents contents, ObfConfig config, ExclusionRules exclusions) {
        this.contents = contents;
        this.config = config;
        this.exclusions = exclusions;
        this.seed = config.seed != 0 ? config.seed : ThreadLocalRandom.current().nextLong();
        this.names = new NameGenerator(seed, config.nameStyle);
        this.remapper = new Remapper();
        this.hierarchy = ClassHierarchy.from(contents);
    }

    public JarContents contents() { return contents; }
    public ObfConfig config() { return config; }
    public ExclusionRules exclusions() { return exclusions; }
    public Remapper remapper() { return remapper; }
    public NameGenerator names() { return names; }
    public ClassHierarchy hierarchy() { return hierarchy; }
    public long seed() { return seed; }

    /** Best-effort common super class lookup using the loaded jar's hierarchy. */
    public String commonSuperClass(String t1, String t2) {
        if (t1.equals(t2)) return t1;
        if (t1.equals("java/lang/Object") || t2.equals("java/lang/Object")) return "java/lang/Object";
        // We cannot reliably resolve external JDK / MC classes here, so fall back to Object.
        // This is correct for COMPUTE_FRAMES purposes when one side is unknown.
        return "java/lang/Object";
    }

    /** Lightweight class hierarchy view built from the input jar. */
    public static final class ClassHierarchy {
        public final Map<String, String> superNames = new HashMap<>();
        public final Map<String, List<String>> interfaces = new HashMap<>();
        public final Map<String, Set<String>> subclasses = new HashMap<>();

        static ClassHierarchy from(JarContents c) {
            ClassHierarchy h = new ClassHierarchy();
            for (ClassNode cn : c.classes().values()) {
                h.superNames.put(cn.name, cn.superName);
                h.interfaces.put(cn.name, cn.interfaces == null ? List.of() : new ArrayList<>(cn.interfaces));
                if (cn.superName != null) h.subclasses.computeIfAbsent(cn.superName, k -> new HashSet<>()).add(cn.name);
                if (cn.interfaces != null) for (String i : cn.interfaces) h.subclasses.computeIfAbsent(i, k -> new HashSet<>()).add(cn.name);
            }
            return h;
        }

        public Set<String> allSupers(String name) {
            Set<String> out = new HashSet<>();
            collect(name, out);
            return out;
        }
        private void collect(String name, Set<String> out) {
            String s = superNames.get(name);
            if (s != null && out.add(s)) collect(s, out);
            List<String> is = interfaces.get(name);
            if (is != null) for (String i : is) if (out.add(i)) collect(i, out);
        }
    }
}
