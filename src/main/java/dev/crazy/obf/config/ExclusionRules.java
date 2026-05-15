package dev.crazy.obf.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Merged exclusion table built from:
 *   - the user's ObfConfig
 *   - the FabricScanner (mod entry points, mixin classes, refmap-referenced names)
 *   - the ReflectionScanner (string-literal Class.forName / getDeclaredField targets)
 *
 * Exclusions are applied at every layer: a class can be excluded as a whole, or
 * a single member can be excluded while the rest of the class is renamed.
 *
 * Pattern syntax: glob over the internal name with '/'.
 *   "net/example/Foo"        — exact class
 *   "net/example/**"          — class + subpackages
 *   "net/example/*"           — direct children only
 *   "**$Mixin"                — suffix match
 *
 * Member patterns are "owner#name" or "owner#name:desc".
 */
public final class ExclusionRules {

    private final Set<Pattern> classPatterns = new HashSet<>();
    private final Set<Pattern> memberPatterns = new HashSet<>();
    private final Set<String>  exactClasses = new HashSet<>();
    private final Set<String>  exactMembers = new HashSet<>();
    private final Set<String>  preserveStrings = new HashSet<>();

    /** "no-touch" classes: every transformer must skip them entirely (mixin targets, generated code). */
    private final Set<String>  noTouchExact = new HashSet<>();
    private final Set<Pattern> noTouchPatterns = new HashSet<>();

    public void addClass(String pattern) {
        if (pattern == null || pattern.isEmpty()) return;
        if (isPlain(pattern)) exactClasses.add(pattern);
        else classPatterns.add(globToRegex(pattern));
    }

    public void addMember(String pattern) {
        if (pattern == null || pattern.isEmpty()) return;
        if (isPlain(pattern)) exactMembers.add(pattern);
        else memberPatterns.add(globToRegex(pattern));
    }

    public void preserveString(String s) { preserveStrings.add(s); }
    public boolean shouldPreserveString(String s) { return preserveStrings.contains(s); }

    public void addNoTouch(String pattern) {
        if (pattern == null || pattern.isEmpty()) return;
        if (isPlain(pattern)) noTouchExact.add(pattern);
        else noTouchPatterns.add(globToRegex(pattern));
        // no-touch also implies no-rename
        addClass(pattern);
    }

    public boolean isClassNoTouch(String internalName) {
        if (noTouchExact.contains(internalName)) return true;
        for (Pattern p : noTouchPatterns) if (p.matcher(internalName).matches()) return true;
        return false;
    }

    public boolean isClassExcluded(String internalName) {
        if (exactClasses.contains(internalName)) return true;
        for (Pattern p : classPatterns) if (p.matcher(internalName).matches()) return true;
        return false;
    }

    public boolean isMemberExcluded(String owner, String name) {
        String k = owner + "#" + name;
        if (exactMembers.contains(k)) return true;
        for (Pattern p : memberPatterns) if (p.matcher(k).matches()) return true;
        return false;
    }

    public boolean isMemberExcluded(String owner, String name, String desc) {
        if (isMemberExcluded(owner, name)) return true;
        String k = owner + "#" + name + ":" + desc;
        if (exactMembers.contains(k)) return true;
        for (Pattern p : memberPatterns) if (p.matcher(k).matches()) return true;
        return false;
    }

    public void importLists(List<String> classes, List<String> members) {
        if (classes != null) classes.forEach(this::addClass);
        if (members != null) members.forEach(this::addMember);
    }

    private static boolean isPlain(String s) {
        return s.indexOf('*') < 0 && s.indexOf('?') < 0 && s.indexOf('[') < 0;
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') { sb.append(".*"); i++; }
                    else sb.append("[^/]*");
                    break;
                case '?': sb.append('.'); break;
                case '.': case '\\': case '+': case '(': case ')': case '[': case ']':
                case '{': case '}': case '|': case '^': case '$':
                    sb.append('\\').append(c); break;
                default: sb.append(c);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }
}
