package dev.crazy.obf.cli;

import kotlin.Metadata;
import kotlin.metadata.KmClass;
import kotlin.metadata.jvm.KotlinClassMetadata;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.util.List;
import java.util.jar.JarFile;

/** Debug helper: prints the Kotlin metadata class-name of one class in a jar. */
public final class DumpKm {
    public static void main(String[] args) throws Exception {
        try (JarFile jf = new JarFile(args[0])) {
            var e = jf.getJarEntry(args[1]);
            try (InputStream in = jf.getInputStream(e)) {
                byte[] b = in.readAllBytes();
                int major = ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
                if (major > 67) { b[6] = 0; b[7] = 67; }
                ClassNode cn = new ClassNode();
                new ClassReader(b).accept(cn, 0);
                AnnotationNode ann = null;
                if (cn.visibleAnnotations != null)
                    for (AnnotationNode a : cn.visibleAnnotations)
                        if ("Lkotlin/Metadata;".equals(a.desc)) ann = a;
                if (ann == null) { System.out.println("no @Metadata on " + cn.name); return; }
                int k = 1, xi = 0; int[] mv = {2,0,0};
                String[] d1 = new String[0], d2 = new String[0]; String xs = "", pn = "";
                List<Object> v = ann.values;
                for (int i = 0; i + 1 < v.size(); i += 2) {
                    String key = (String) v.get(i); Object val = v.get(i + 1);
                    switch (key) {
                        case "k" -> k = (Integer) val;
                        case "xi" -> xi = (Integer) val;
                        case "mv" -> { var l = (List<Integer>) val; mv = new int[l.size()]; for (int j=0;j<mv.length;j++) mv[j]=l.get(j); }
                        case "d1" -> d1 = ((List<String>) val).toArray(new String[0]);
                        case "d2" -> d2 = ((List<String>) val).toArray(new String[0]);
                        case "xs" -> xs = (String) val;
                        case "pn" -> pn = (String) val;
                    }
                }
                Metadata md = kotlin.metadata.jvm.JvmMetadataUtil.Metadata(k, mv, d1, d2, xs, pn, xi);
                KotlinClassMetadata kcm = KotlinClassMetadata.readStrict(md);
                if (kcm instanceof KotlinClassMetadata.Class c) {
                    KmClass km = c.getKmClass();
                    System.out.println("JVM class : " + cn.name);
                    System.out.println("Km   name : " + km.getName());
                    System.out.println("supertypes: " + km.getSupertypes().size()
                        + ", properties: " + km.getProperties().size()
                        + ", functions: " + km.getFunctions().size());
                } else {
                    System.out.println("metadata kind: " + kcm.getClass().getSimpleName());
                }
            }
        }
    }
}
