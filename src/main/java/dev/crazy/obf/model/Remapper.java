package dev.crazy.obf.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds the full mapping table built during the planning phase and consumed by
 * an ASM ClassRemapper at apply time.
 */
public final class Remapper {

    /** internal class name -> new internal class name */
    public final Map<String, String> classes = new HashMap<>();

    /** "owner.field:desc" -> new field name */
    public final Map<String, String> fields = new HashMap<>();

    /** "owner.method desc" -> new method name */
    public final Map<String, String> methods = new HashMap<>();

    public String mapClass(String internalName) {
        return classes.getOrDefault(internalName, internalName);
    }

    public String mapField(String owner, String name, String desc) {
        return fields.getOrDefault(fieldKey(owner, name, desc), name);
    }

    public String mapMethod(String owner, String name, String desc) {
        return methods.getOrDefault(methodKey(owner, name, desc), name);
    }

    public static String fieldKey(String owner, String name, String desc) { return owner + "." + name + ":" + desc; }
    public static String methodKey(String owner, String name, String desc) { return owner + "." + name + " " + desc; }
}
