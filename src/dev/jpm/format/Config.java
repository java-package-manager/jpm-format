package dev.jpm.format;

import dev.jpm.format.api.Formatter;
import dev.jpm.format.api.Lint;
import dev.jpm.format.api.Rules;
import dev.jpm.format.api.Severity;
import dev.jpm.format.intellij.IntelliJRule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The format config: {@code "extensions": {"format": {...}}} in {@code project.jsonc}, or a separate
 * {@code jpm-format.jsonc} at the project root. A module's own {@code project.jsonc} applies to the files
 * under it, over the root's. The shape follows Biome, with Prettier's option names:
 *
 * <pre>
 * {
 *   "extends": ["config/format.jsonc"],
 *   "ignore": ["src/generated/**"],
 *   "java": { "enabled": true, "lineWidth": 120, "indentWidth": 4, "continuationIndentWidth": 8, "useTabs": false,
 *                  "licenseHeader": "/* (c) $YEAR Me *&#47;" },
 *   "imports": { "removeUnused": true, "sort": true, "groups": ["*", ["java.*", "javax.*"], "static *"],
 *                "wildcardThreshold": 0 },
 *   "linter": { "enabled": true, "recommended": true,
 *               "rules": { "style": { "MissingOverrideAnnotation": "error" },
 *                          "custom": { "MyRule": { "level": "warn", "options": { "max": 3 } } } } },
 *   "overrides": [ { "include": ["test/**"], "linter": { "rules": { "correctness": { "UnusedPrivateMember": "off" } } } } ]
 * }
 * </pre>
 */
public final class Config {
    public static final String FILE = "jpm-format.jsonc";

    static final List<String> DEFAULT_IGNORES = List.of(
            ".git/**",
            ".cache/**",
            "dist/**",
            "build/**",
            "target/**",
            "out/**",
            "node_modules/**",
            ".idea/**",
            ".gradle/**",
            "rules/test/**");

    /**
     * IntelliJ's default layout: all other imports, then java.* and javax.*, then static imports.
     */
    static final List<List<String>> DEFAULT_GROUPS =
            List.of(List.of("*"), List.of("java.*", "javax.*"), List.of("static *"));

    /**
     * A rule's level and options.
     */
    public record RuleSetting(Severity level, Map<String, Object> options) {
    }

    /**
     * The settings for one file.
     */
    public record Settings(
            boolean formatterEnabled,
            int lineWidth,
            int indentWidth,
            int continuationIndentWidth,
            boolean intellijStyle,
            int maxBlankLines,
            boolean useTabs,
            String licenseHeader,
            boolean removeUnusedImports,
            boolean sortImports,
            List<List<String>> importGroups,
            int wildcardThreshold,
            boolean linterEnabled,
            boolean recommended,
            Map<String, RuleSetting> rules,
            String key,
            ExternalFormatter.Spec externalFormatter) {

        /**
         * The level a rule runs at for this file; OFF if it does not run.
         */
        public Severity level(Lint rule, boolean builtin) {
            RuleSetting s = rules.get(rule.id());
            boolean formatting = rule instanceof Formatter;
            boolean on = switch (rule.id()) {
                case "JavaFormat" -> formatterEnabled;
                case "LicenseHeader" -> formatterEnabled && licenseHeader != null;
                case "ImportOrder" -> sortImports;
                case "UnusedImport" -> removeUnusedImports;
                default -> rule instanceof IntelliJRule
                        ? formatterEnabled && intellijStyle
                        : formatting || (linterEnabled && (recommended || !builtin));
            };
            if (s != null) {
                if (s.level() == Severity.OFF) return Severity.OFF;
                if (rule.id().equals("LicenseHeader") && licenseHeader == null) return Severity.OFF;
                if (rule instanceof IntelliJRule && !(formatterEnabled && intellijStyle)) return Severity.OFF;
                if (formatting || linterEnabled) return s.level();
                return Severity.OFF;
            }
            return on ? rule.severity() : Severity.OFF;
        }

        public Map<String, Object> options(String id) {
            RuleSetting s = rules.get(id);
            return s == null ? Map.of() : s.options();
        }
    }

    private record Scoped(Path dir, Map<String, Object> config) {
    }

    public final Path root;
    private final Map<String, Object> rootConfig;
    private final List<Scoped> modules;
    private final List<PathMatcher> ignores = new ArrayList<>();
    private final Rules rules;
    private final Map<String, Settings> settingsByKey = new ConcurrentHashMap<>();
    private final List<SpotlessSteps.Entry> files;
    /**
     * A hash of everything that affects results, for the cache.
     */
    public final String hash;

    private Config(Path root, Map<String, Object> rootConfig, List<Scoped> modules, Rules rules, String extraHash) {
        this.root = root;
        this.rootConfig = rootConfig;
        this.modules = modules;
        this.rules = rules;
        List<String> ign = new ArrayList<>(DEFAULT_IGNORES);
        for (Object o : list(rootConfig.get("ignore"), "ignore")) ign.add(str(o, "ignore"));
        for (String g : ign) ignores.addAll(matchers(g));
        StringBuilder h = new StringBuilder(Json.write(rootConfig));
        for (Scoped s : modules) h.append(s.dir()).append(Json.write(s.config()));
        h.append(extraHash);
        this.hash = Hashing.sha256(h.toString());
        validate(rootConfig, "config");
        this.files = SpotlessSteps.parse(rootConfig.get("files"));
        for (Scoped s : modules) {
            if (s.config().containsKey("files")) {
                throw new FormatException(s.dir().resolve("project.jsonc") + ": \"files\" goes in the root config, which applies it to the whole project");
            }
        }
        for (Scoped s : modules)
            validate(s.config(), s.dir().resolve("project.jsonc").toString());
    }

    /**
     * Loads the config of the project at {@code root}, checking rule names against {@code rules}.
     */
    public static Config load(Path root, Rules rules, String extraHash) {
        return new Config(root, rootConfig(root), modules(root), rules, extraHash);
    }

    /**
     * Whether any part of the project's config picks a {@code java.style} other than {@code "intellij"}: those
     * formatters (Palantir Java Format, google-java-format, a jar) may read javac's internals, which then have to be
     * exported, on a JDK they support (ADR 0003). The IntelliJ style uses javac's public API only.
     */
    public static boolean otherStyle(Path root) {
        if (otherStyle((Object) rootConfig(root))) return true;
        for (Scoped s : modules(root)) if (otherStyle((Object) s.config())) return true;
        return false;
    }

    private static boolean otherStyle(Object o) {
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if ("java".equals(e.getKey()) && e.getValue() instanceof Map<?, ?> f
                        && f.get("style") instanceof String st && !st.equals("intellij")) {
                    return true;
                }
                if (otherStyle(e.getValue())) return true;
            }
        } else if (o instanceof List<?> l) {
            for (Object x : l) if (otherStyle(x)) return true;
        }
        return false;
    }

    private static List<Scoped> modules(Path root) {
        List<Scoped> mods = new ArrayList<>();
        try (var walk = Files.walk(root, 6)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!p.getFileName().toString().equals("project.jsonc")
                        || p.getParent().equals(root)) continue;
                String rel = rel(root, p);
                if (rel.startsWith(".")
                        || rel.contains("/.")
                        || rel.startsWith("dist/")
                        || rel.startsWith("node_modules/")) continue;
                Map<String, Object> c = formatSection(read(p), p.toString());
                if (c != null)
                    mods.add(new Scoped(p.getParent(), resolveExtends(c, p.getParent(), new LinkedHashSet<>())));
            }
        } catch (IOException e) {
            throw new FormatException("Could not read " + root + ": " + e.getMessage());
        }
        mods.sort((a, b) -> b.dir().getNameCount() - a.dir().getNameCount());
        return mods;
    }

    /**
     * A config from JSON, for tests and the test kit.
     */
    public static Config of(Path root, Map<String, Object> config, Rules rules) {
        return new Config(root, resolveExtends(config, root, new LinkedHashSet<>()), List.of(), rules, "");
    }

    private static Map<String, Object> rootConfig(Path root) {
        Path manifest = root.resolve("project.jsonc");
        Map<String, Object> fromManifest = null;
        String envManifest = System.getenv("JPM_MANIFEST");
        String envConfig = System.getenv("JPM_CONFIG");
        if (envConfig != null
                && !envConfig.isBlank()
                && envManifest != null
                && Path.of(envManifest).toAbsolutePath().normalize().equals(manifest)) {
            fromManifest = formatSection(Json.object(envConfig, "JPM_CONFIG"), "JPM_CONFIG");
        } else if (Files.isRegularFile(manifest)) {
            fromManifest = formatSection(read(manifest), manifest.toString());
        }
        Path own = root.resolve(FILE);
        Map<String, Object> fromFile = Files.isRegularFile(own) ? Json.object(readString(own), own.toString()) : null;
        if (fromManifest != null && fromFile != null) {
            throw new FormatException(
                    "Both project.jsonc (\"extensions.format\") and " + FILE + " configure jpm format; keep one");
        }
        Map<String, Object> cfg =
                fromFile != null ? fromFile : fromManifest != null ? fromManifest : new LinkedHashMap<>();
        return resolveExtends(cfg, root, new LinkedHashSet<>());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> formatSection(Map<String, Object> manifest, String where) {
        Object ext = manifest.get("extensions");
        if (!(ext instanceof Map<?, ?> m)) return null;
        Object f = m.get("format");
        if (f == null) return null;
        if (!(f instanceof Map)) throw new FormatException(where + ": \"extensions.format\" must be an object");
        return (Map<String, Object>) f;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveExtends(Map<String, Object> cfg, Path dir, Set<Path> seen) {
        Object ext = cfg.get("extends");
        if (ext == null) return cfg;
        List<Object> list = ext instanceof String s ? List.of(s) : list(ext, "extends");
        Map<String, Object> base = new LinkedHashMap<>();
        for (Object o : list) {
            Path p = dir.resolve(str(o, "extends")).normalize();
            if (!seen.add(p)) throw new FormatException("\"extends\" loops through " + p);
            if (!Files.isRegularFile(p)) throw new FormatException("\"extends\": " + p + " does not exist");
            Map<String, Object> other = Json.object(readString(p), p.toString());
            Map<String, Object> sec = formatSection(other, p.toString());
            base = merge(base, resolveExtends(sec != null ? sec : other, p.getParent(), seen));
        }
        Map<String, Object> own = new LinkedHashMap<>(cfg);
        own.remove("extends");
        return merge(base, own);
    }

    private static Map<String, Object> read(Path p) {
        return Json.object(readString(p), p.toString());
    }

    private static String readString(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FormatException("Could not read " + p + ": " + e.getMessage());
        }
    }

    /**
     * Deep merge: objects merge key by key, anything else is replaced.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> over) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : over.entrySet()) {
            Object b = out.get(e.getKey());
            if (b instanceof Map && e.getValue() instanceof Map) {
                out.put(e.getKey(), merge((Map<String, Object>) b, (Map<String, Object>) e.getValue()));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    static String rel(Path root, Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /**
     * The Spotless steps for a file that is not Java (relative to the root), from {@code "files"}; empty when none apply.
     */
    List<SpotlessSteps.Step> steps(String rel) {
        return SpotlessSteps.stepsFor(files, rel, format -> formatOptions(rel, format));
    }

    /**
     * True if the file (relative to the root) is ignored.
     */
    public boolean ignored(String rel) {
        Path p = Path.of(rel);
        for (PathMatcher m : ignores) if (m.matches(p)) return true;
        return false;
    }

    /**
     * The settings for a file, relative to the root.
     */
    @SuppressWarnings("unchecked")
    public Settings settings(String rel) {
        Map<String, Object> eff = effective(rel);
        Map<String, Object> f = new LinkedHashMap<>(obj(eff.get("default"), "default"));
        f.putAll(obj(eff.get("java"), "java"));
        eff = new LinkedHashMap<>(eff);
        eff.put("formatter", f);
        Map<String, Object> e = eff;
        String key = Json.write(e);
        return settingsByKey.computeIfAbsent(key, k -> parse(e, k));
    }

    /**
     * The options of one of jpm-format's own formats ({@code properties}, {@code json}, {@code xml}, {@code yaml},
     * {@code toml}) for a file, as its steps take them: those of the {@code default} section the format has, then
     * the format's own section. What neither sets is the format's own default.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> formatOptions(String rel, String format) {
        Map<String, Object> eff = effective(rel);
        Set<String> known = SpotlessSteps.FORMAT_OPTIONS.get(format);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> m : List.of(obj(eff.get("default"), "default"), obj(eff.get(format), format))) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (known.contains(e.getKey()))
                    out.put(e.getKey().equals("indentWidth") ? "indent" : e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * What {@code "default"} takes: the settings every file type has, which apply to each type that does not set them
     * itself.
     */
    private static final Set<String> DEFAULTS =
            Set.of("lineWidth", "indentWidth", "continuationIndentWidth", "maxBlankLines", "useTabs");

    @SuppressWarnings("unchecked")
    private Map<String, Object> effective(String rel) {
        Map<String, Object> cfg = rootConfig;
        Path abs = root.resolve(rel).normalize();
        for (int k = modules.size() - 1; k >= 0; k--) {
            Scoped s = modules.get(k);
            if (abs.startsWith(s.dir())) cfg = merge(cfg, s.config());
        }
        for (Object o : list(cfg.get("overrides"), "overrides")) {
            if (!(o instanceof Map<?, ?> ov)) throw new FormatException("\"overrides\" entries must be objects");
            Map<String, Object> m = (Map<String, Object>) ov;
            boolean match = false;
            for (Object g : list(m.get("include"), "overrides.include")) {
                for (PathMatcher pm : matchers(str(g, "overrides.include"))) if (pm.matches(Path.of(rel))) match = true;
            }
            if (match) {
                Map<String, Object> without = new LinkedHashMap<>(m);
                without.remove("include");
                cfg = merge(cfg, without);
            }
        }
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private Settings parse(Map<String, Object> c, String key) {
        Map<String, Object> f = obj(c.get("formatter"), "formatter");
        Map<String, Object> im = obj(c.get("imports"), "imports");
        Map<String, Object> li = obj(c.get("linter"), "linter");
        int width = range(f.getOrDefault("lineWidth", 120L), "java.lineWidth", 20, 1000);
        int indent = range(f.getOrDefault("indentWidth", 4L), "java.indentWidth", 1, 16);
        int continuation = range(
                f.getOrDefault("continuationIndentWidth", (long) (2 * indent)),
                "java.continuationIndentWidth",
                1,
                32);
        Object st = f.getOrDefault("style", "intellij");
        if (!(st instanceof String style)) {
            throw new FormatException("java.style must be a string, not " + Json.write(st));
        }
        Object ec = f.get("eclipseConfig");
        if (ec != null && !(ec instanceof String)) throw new FormatException("java.eclipseConfig must be a path");
        ExternalFormatter.Spec external = ExternalFormatter.parse(style, root, ec == null ? null : root.resolve((String) ec));
        int maxBlank = range(f.getOrDefault("maxBlankLines", 2L), "java.maxBlankLines", 1, 10);
        String header = null;
        Object h = f.get("licenseHeader");
        if (h instanceof String s) header = s;
        else if (h instanceof Map<?, ?> hm && hm.get("file") instanceof String file)
            header = readString(root.resolve(file));
        else if (h != null)
            throw new FormatException("java.licenseHeader must be a string or {\"file\": \"...\"}");
        if (header != null) {
            header = header.replace("\r\n", "\n").strip();
            if (!header.startsWith("/*") && !header.startsWith("//")) {
                throw new FormatException("java.licenseHeader must be a Java comment (start with /* or //)");
            }
        }
        List<List<String>> groups = new ArrayList<>();
        if (im.containsKey("groups")) {
            for (Object g : list(im.get("groups"), "imports.groups")) {
                if (g instanceof String s) groups.add(List.of(s));
                else {
                    List<String> l = new ArrayList<>();
                    for (Object x : list(g, "imports.groups")) l.add(str(x, "imports.groups"));
                    groups.add(l);
                }
            }
        } else groups.addAll(DEFAULT_GROUPS);

        Map<String, RuleSetting> rs = new LinkedHashMap<>();
        Map<String, Object> byCat = obj(li.get("rules"), "linter.rules");
        for (Map.Entry<String, Object> cat : byCat.entrySet()) {
            for (Map.Entry<String, Object> r :
                    obj(cat.getValue(), "linter.rules." + cat.getKey()).entrySet()) {
                String where = "linter.rules." + cat.getKey() + "." + r.getKey();
                Severity level;
                Map<String, Object> opts = Map.of();
                if (r.getValue() instanceof String s) level = Severity.parse(s);
                else if (r.getValue() instanceof Map<?, ?> m) {
                    Object l = m.get("level");
                    level = l == null ? null : Severity.parse(String.valueOf(l));
                    if (l == null) {
                        Lint rule = rules.get(r.getKey());
                        level = rule == null ? Severity.WARN : rule.severity();
                    }
                    opts = obj(m.get("options"), where + ".options");
                } else level = null;
                if (level == null)
                    throw new FormatException(
                            where + " must be \"off\", \"warn\", \"error\" or {\"level\": ..., \"options\": {...}}");
                rs.put(r.getKey(), new RuleSetting(level, opts));
            }
        }
        return new Settings(
                bool(f.getOrDefault("enabled", true), "java.enabled"),
                width,
                indent,
                continuation,
                "intellij".equals(st),
                maxBlank,
                bool(f.getOrDefault("useTabs", false), "java.useTabs"),
                header,
                bool(im.getOrDefault("removeUnused", true), "imports.removeUnused"),
                bool(im.getOrDefault("sort", true), "imports.sort"),
                List.copyOf(groups),
                (int) num(im.getOrDefault("wildcardThreshold", 0L), "imports.wildcardThreshold"),
                bool(li.getOrDefault("enabled", true), "linter.enabled"),
                bool(li.getOrDefault("recommended", true), "linter.recommended"),
                rs,
                Hashing.sha256(key),
                external);
    }

    private static final Set<String> TOP =
            Set.of("$schema", "extends", "ignore", "imports", "linter", "overrides", "files", "default", "java", "json",
                    "xml", "properties", "toml", "yaml");
    private static final Set<String> FORMATTER = Set.of(
            "enabled",
            "style",
            "eclipseConfig",
            "lineWidth",
            "indentWidth",
            "continuationIndentWidth",
            "maxBlankLines",
            "useTabs",
            "licenseHeader");
    private static final Set<String> IMPORTS = Set.of("removeUnused", "sort", "groups", "wildcardThreshold");
    private static final Set<String> LINTER = Set.of("enabled", "recommended", "rules");

    @SuppressWarnings("unchecked")
    private void validate(Map<String, Object> c, String where) {
        if (c.containsKey("formatter")) {
            throw new FormatException(where + ": \"formatter\" is gone: Java's settings go in \"java\", and those for every"
                    + " file type in \"default\"");
        }
        keys(c, TOP, where);
        keys(obj(c.get("default"), "default"), DEFAULTS, where + ": default");
        keys(obj(c.get("java"), "java"), FORMATTER, where + ": java");
        for (String format : List.of("json", "xml", "properties", "toml", "yaml")) {
            keys(obj(c.get(format), format), SpotlessSteps.FORMAT_OPTIONS.get(format), where + ": " + format);
        }
        keys(obj(c.get("imports"), "imports"), IMPORTS, where + ": imports");
        Map<String, Object> li = obj(c.get("linter"), "linter");
        keys(li, LINTER, where + ": linter");
        for (Map.Entry<String, Object> cat :
                obj(li.get("rules"), "linter.rules").entrySet()) {
            for (String id : obj(cat.getValue(), "linter.rules." + cat.getKey()).keySet()) {
                Lint r = rules.get(id);
                if (r == null) {
                    throw new FormatException(
                            where + ": no rule named " + id + " (run `jpm format rules` to list them)");
                }
                if (!r.category().equals(cat.getKey())) {
                    throw new FormatException(where + ": " + id + " is in category \"" + r.category() + "\", not \""
                            + cat.getKey() + "\"");
                }
            }
        }
        for (Object o : list(c.get("overrides"), "overrides")) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> ov = new LinkedHashMap<>((Map<String, Object>) m);
                if (!ov.containsKey("include"))
                    throw new FormatException(where + ": each of \"overrides\" needs \"include\"");
                ov.remove("include");
                for (String k : List.of("extends", "ignore", "overrides")) {
                    if (ov.containsKey(k))
                        throw new FormatException(where + ": \"" + k + "\" is not allowed in \"overrides\"");
                }
                validate(ov, where + ": overrides");
            }
        }
    }

    private static void keys(Map<String, Object> m, Set<String> allowed, String where) {
        for (String k : m.keySet()) {
            if (!allowed.contains(k))
                throw new FormatException(where + ": unknown key \"" + k + "\" (expected one of " + allowed + ")");
        }
    }

    static List<PathMatcher> matchers(String glob) {
        String g = glob.startsWith("./") ? glob.substring(2) : glob;
        if (g.endsWith("/")) g = g + "**";
        List<String> pats = new ArrayList<>();
        pats.add(g);
        if (!g.contains("*")) pats.add(g + "/**");
        if (!g.contains("/")) pats.add("**/" + g);
        List<PathMatcher> out = new ArrayList<>();
        for (String p : pats) out.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o, String where) {
        if (o == null) return Map.of();
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new FormatException(where + " must be an object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o, String where) {
        if (o == null) return List.of();
        if (o instanceof List) return (List<Object>) o;
        throw new FormatException(where + " must be a list");
    }

    private static String str(Object o, String where) {
        if (o instanceof String s) return s;
        throw new FormatException(where + " must contain strings");
    }

    private static boolean bool(Object o, String where) {
        if (o instanceof Boolean b) return b;
        throw new FormatException(where + " must be true or false");
    }

    private static int range(Object o, String where, int min, int max) {
        long n = num(o, where);
        if (n < min || n > max) throw new FormatException(where + " must be from " + min + " to " + max + ", not " + n);
        return (int) n;
    }

    private static long num(Object o, String where) {
        if (o instanceof Number n) return n.longValue();
        throw new FormatException(where + " must be a number");
    }
}
