package dev.crazy.obf.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.crazy.obf.config.ExclusionRules;
import dev.crazy.obf.io.JarContents;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Adds Fabric / Mixin specific exclusions:
 *   - fabric.mod.json entrypoints (main, client, server, modmenu, etc.)
 *   - fabric.mod.json mixins config refs
 *   - any *.mixins.json: package + listed classes
 *   - any class annotated with @Mixin (org.spongepowered.asm.mixin.Mixin)
 *   - @Inject/@Redirect/@Overwrite handler methods inside mixin classes
 *   - @Shadow fields/methods
 *   - any class implementing fabricloader Pre/Post-Launch / ClientModInitializer / ModInitializer (entry-point types)
 *   - Manifest Main-Class
 */
public final class FabricScanner {

    private final JarContents contents;
    private final ExclusionRules ex;
    private final PrintStream log;

    public FabricScanner(JarContents contents, ExclusionRules ex, PrintStream log) {
        this.contents = contents;
        this.ex = ex;
        this.log = log;
    }

    public void scan() {
        scanFabricModJson();
        scanMixinConfigs();
        scanClassAnnotations();
        scanManifest();
        scanFabricSpi();
    }

    private void scanFabricModJson() {
        byte[] data = contents.resource("fabric.mod.json");
        if (data == null) return;
        try {
            JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();

            // entrypoints — every value's string form is `[adapter:]ClassName[::method|field]`
            JsonElement eps = root.get("entrypoints");
            if (eps != null && eps.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : eps.getAsJsonObject().entrySet()) {
                    JsonElement v = e.getValue();
                    if (v.isJsonArray()) for (JsonElement el : v.getAsJsonArray()) addEntryPoint(el);
                    else addEntryPoint(v);
                }
            }

            // accessWidener
            JsonElement aw = root.get("accessWidener");
            if (aw != null && aw.isJsonPrimitive()) {
                byte[] awData = contents.resource(aw.getAsString());
                if (awData != null) scanAccessWidener(awData);
            }

            // mixins
            JsonElement mixins = root.get("mixins");
            if (mixins != null) {
                if (mixins.isJsonArray()) for (JsonElement el : mixins.getAsJsonArray()) scanMixinRef(el);
                else scanMixinRef(mixins);
            }
        } catch (Exception ex) {
            log.println("[crazy] warning: could not parse fabric.mod.json: " + ex.getMessage());
        }
    }

    private void addEntryPoint(JsonElement el) {
        if (el == null) return;
        String spec;
        if (el.isJsonObject()) {
            JsonElement v = el.getAsJsonObject().get("value");
            if (v == null) return;
            spec = v.getAsString();
        } else spec = el.getAsString();

        // optional `adapter:` prefix
        int colon = spec.indexOf(':');
        String body = colon < 0 ? spec : spec.substring(colon + 1);
        // optional `::member` suffix
        int dd = body.indexOf("::");
        String cls = (dd < 0 ? body : body.substring(0, dd)).replace('.', '/');
        ex.addClass(cls);
        if (dd >= 0) ex.addMember(cls + "#" + body.substring(dd + 2));
        log.println("[crazy] excluding fabric entrypoint: " + cls);
    }

    private void scanMixinRef(JsonElement el) {
        String name;
        if (el.isJsonObject()) {
            JsonElement v = el.getAsJsonObject().get("config");
            if (v == null) return;
            name = v.getAsString();
        } else name = el.getAsString();
        byte[] data = contents.resource(name);
        if (data == null) return;
        scanMixinConfig(name, data);
    }

    private void scanMixinConfigs() {
        for (Map.Entry<String, byte[]> e : contents.resources().entrySet()) {
            if (e.getKey().endsWith(".mixins.json") || e.getKey().endsWith("mixin.json")) {
                scanMixinConfig(e.getKey(), e.getValue());
            }
        }
    }

    private void scanMixinConfig(String resName, byte[] data) {
        try {
            JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            String pkg = root.has("package") ? root.get("package").getAsString().replace('.', '/') : null;

            for (String key : List.of("mixins", "client", "server")) {
                JsonElement el = root.get(key);
                if (el == null || !el.isJsonArray()) continue;
                JsonArray arr = el.getAsJsonArray();
                for (JsonElement m : arr) {
                    String name = m.getAsString().replace('.', '/');
                    String full = pkg == null ? name : pkg + "/" + name;
                    ex.addNoTouch(full);
                    log.println("[crazy] mixin config target (no-touch): " + full);
                }
            }

            // Mixin plugin
            JsonElement plugin = root.get("plugin");
            if (plugin != null && plugin.isJsonPrimitive()) {
                ex.addClass(plugin.getAsString().replace('.', '/'));
            }

            // Preserve all strings that name configs (mixin loader reads them)
            ex.preserveString(resName);
            ex.preserveString(resName.replace('/', '.'));
        } catch (Exception ex) {
            log.println("[crazy] warning: could not parse " + resName + ": " + ex.getMessage());
        }
    }

    private void scanAccessWidener(byte[] data) {
        // Each non-comment line: "accessible/extendable/mutable class|method|field <ref>"
        String txt = new String(data, StandardCharsets.UTF_8);
        for (String line : txt.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            // We don't rename Minecraft classes anyway, but if any of these refer
            // to our own classes, exclude them.
            for (String p : parts) {
                if (p.contains("/") || p.contains(".")) ex.addClass(p.replace('.', '/'));
            }
        }
    }

    private void scanClassAnnotations() {
        for (ClassNode cn : contents.classes().values()) {
            boolean isMixin = hasAnn(cn.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;")
                           || hasAnn(cn.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
            if (isMixin) {
                // mixin processor entirely rewrites these classes — no transformer should touch them
                ex.addNoTouch(cn.name);
                log.println("[crazy] mixin (no-touch): " + cn.name);
                continue;
            }

            // Mixin extras: @Shadow / @Accessor / @Invoker stay even outside @Mixin (subclasses)
            if (cn.methods != null) for (MethodNode m : cn.methods) {
                if (hasMixinHandlerAnn(m)) {
                    ex.addMember(cn.name + "#" + m.name);
                }
            }
            if (cn.fields != null) for (FieldNode f : cn.fields) {
                if (hasShadowAnn(f.visibleAnnotations) || hasShadowAnn(f.invisibleAnnotations)) {
                    ex.addMember(cn.name + "#" + f.name);
                }
            }
        }
    }

    private static boolean hasAnn(List<AnnotationNode> anns, String desc) {
        if (anns == null) return false;
        for (AnnotationNode a : anns) if (desc.equals(a.desc)) return true;
        return false;
    }
    private static boolean hasShadowAnn(List<AnnotationNode> anns) {
        if (anns == null) return false;
        for (AnnotationNode a : anns) if (a.desc != null && a.desc.startsWith("Lorg/spongepowered/asm/mixin/")) return true;
        return false;
    }
    private static boolean hasMixinHandlerAnn(MethodNode m) {
        return hasShadowAnn(m.visibleAnnotations) || hasShadowAnn(m.invisibleAnnotations);
    }

    private void scanManifest() {
        byte[] data = contents.resource("META-INF/MANIFEST.MF");
        if (data == null) return;
        String s = new String(data, StandardCharsets.UTF_8);
        for (String line : s.split("\n")) {
            line = line.trim();
            int idx = line.indexOf(':');
            if (idx < 0) continue;
            String k = line.substring(0, idx).trim();
            String v = line.substring(idx + 1).trim();
            if (k.equalsIgnoreCase("Main-Class") || k.equalsIgnoreCase("Premain-Class") || k.equalsIgnoreCase("Launcher-Agent-Class")) {
                ex.addClass(v.replace('.', '/'));
                log.println("[crazy] excluding manifest " + k + ": " + v);
            }
        }
    }

    private void scanFabricSpi() {
        // Some loader hooks resolve types from META-INF/services
        for (Map.Entry<String, byte[]> e : contents.resources().entrySet()) {
            if (!e.getKey().startsWith("META-INF/services/")) continue;
            String s = new String(e.getValue(), StandardCharsets.UTF_8);
            for (String line : s.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                ex.addClass(line.replace('.', '/'));
            }
        }
    }
}
