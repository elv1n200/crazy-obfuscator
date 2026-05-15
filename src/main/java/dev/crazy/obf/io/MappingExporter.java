package dev.crazy.obf.io;

import dev.crazy.obf.model.ObfContext;
import dev.crazy.obf.model.Remapper;
import org.objectweb.asm.Type;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a Proguard-format mapping file. Format spec:
 *
 *   <orig.fully.qualified.Class> -> <new.fully.qualified.Class>:
 *       <ret-type> <orig-method>(<arg-types>) -> <new-method>
 *       <field-type> <orig-field> -> <new-field>
 *
 * This file is what you need to translate a crash log from an obfuscated jar
 * back into source-level names. Compatible with ProguardRetracer / Mapping
 * Tool / Retrace.
 */
public final class MappingExporter {

    private MappingExporter() {}

    public static void export(Path out, ObfContext ctx) throws IOException {
        Remapper r = ctx.remapper();

        // Group fields/methods by original-class
        Map<String, List<String>> byClass = new HashMap<>();

        for (Map.Entry<String, String> e : r.fields.entrySet()) {
            // key = "owner.field:desc"
            String k = e.getKey();
            int dot = k.indexOf('.');
            int colon = k.indexOf(':', dot);
            if (dot < 0 || colon < 0) continue;
            String owner = k.substring(0, dot);
            String field = k.substring(dot + 1, colon);
            String desc  = k.substring(colon + 1);
            String type  = humanType(Type.getType(desc));
            byClass.computeIfAbsent(owner, x -> new ArrayList<>())
                   .add("    " + type + " " + field + " -> " + e.getValue());
        }

        for (Map.Entry<String, String> e : r.methods.entrySet()) {
            // key = "owner.method desc"
            String k = e.getKey();
            int dot = k.indexOf('.');
            int sp = k.indexOf(' ', dot);
            if (dot < 0 || sp < 0) continue;
            String owner = k.substring(0, dot);
            String method = k.substring(dot + 1, sp);
            String desc = k.substring(sp + 1);
            Type mt = Type.getMethodType(desc);
            StringBuilder line = new StringBuilder("    ");
            line.append(humanType(mt.getReturnType())).append(' ').append(method).append('(');
            Type[] args = mt.getArgumentTypes();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) line.append(',');
                line.append(humanType(args[i]));
            }
            line.append(") -> ").append(e.getValue());
            byClass.computeIfAbsent(owner, x -> new ArrayList<>()).add(line.toString());
        }

        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("# crazy-obfuscator mapping; seed=" + ctx.seed());
            w.newLine();
            // Classes first, in deterministic order
            List<String> classes = new ArrayList<>(r.classes.keySet());
            // include classes that had only field/method changes (no rename) so retracer can find them
            for (String owner : byClass.keySet()) if (!r.classes.containsKey(owner)) classes.add(owner);
            Collections.sort(classes);
            for (String orig : classes) {
                String newName = r.classes.getOrDefault(orig, orig);
                w.write(human(orig) + " -> " + human(newName) + ":");
                w.newLine();
                List<String> members = byClass.get(orig);
                if (members != null) {
                    Collections.sort(members);
                    for (String m : members) { w.write(m); w.newLine(); }
                }
            }
        }
    }

    private static String human(String internal) { return internal.replace('/', '.'); }

    private static String humanType(Type t) {
        switch (t.getSort()) {
            case Type.VOID:    return "void";
            case Type.BOOLEAN: return "boolean";
            case Type.CHAR:    return "char";
            case Type.BYTE:    return "byte";
            case Type.SHORT:   return "short";
            case Type.INT:     return "int";
            case Type.FLOAT:   return "float";
            case Type.LONG:    return "long";
            case Type.DOUBLE:  return "double";
            case Type.ARRAY:   return humanType(t.getElementType()) + "[]".repeat(t.getDimensions());
            case Type.OBJECT:  return human(t.getInternalName());
            default:           return t.getDescriptor();
        }
    }
}
