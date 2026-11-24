package dev.jpm.format;

import dev.jpm.format.api.Finding;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.Severity;
import dev.jpm.format.files.JsonFormat;
import dev.jpm.format.files.PropertiesFormat;
import dev.jpm.format.files.TomlFormat;
import dev.jpm.format.files.XmlFormat;
import dev.jpm.format.files.YamlFormat;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spotless's steps for files other than Java, from {@code "files"} in the config: each entry names the files it
 * applies to ({@code include}, globs relative to the project) and the steps that format them, in order. jpm provides
 * Spotless's jars ({@code dependencies/resolve}) and those of the formatters the steps download, as for
 * {@code formatter.style: "eclipse"}.
 *
 * <p>Five steps are jpm-format's own and need nothing downloaded: {@code properties}, {@code json}, {@code xml},
 * {@code yaml} and {@code toml}
 * ({@code dev.jpm.format.files}). They are on by default for the files they are named after ({@link #DEFAULTS}) when no
 * entry includes the file; an entry with {@code "steps": []} turns them off for the files it includes.
 *
 * <pre>
 * "files": [
 *   { "include": ["**&#47;*.kt", "**&#47;*.kts"], "steps": ["ktfmt", "trimTrailingWhitespace", "endWithNewline"] },
 *   { "include": ["**&#47;*.json"], "steps": [{ "step": "jsonSimple", "indent": 2 }] }
 * ]
 * </pre>
 */
final class SpotlessSteps {
    /**
     * The rule findings of these files are reported under.
     */
    static final String RULE = "SpotlessSteps";

    /**
     * A step: its name, the version of the formatter it runs (null for Spotless's default) and its options.
     */
    record Step(String name, String version, Map<String, Object> options) {
    }

    /**
     * Files and the steps that format them.
     */
    record Entry(List<String> include, List<PathMatcher> matchers, List<Step> steps) {
    }

    /**
     * The steps jpm-format knows, with the options each takes besides {@code step} and {@code version}.
     */
    static final Map<String, Set<String>> STEPS = Map.ofEntries(
            Map.entry("ktfmt", Set.of()),
            Map.entry("ktlint", Set.of()),
            Map.entry("scalafmt", Set.of("config")),
            Map.entry("jacksonJson", Set.of()),
            Map.entry("jsonSimple", Set.of("indent")),
            Map.entry("jacksonYaml", Set.of()),
            Map.entry("dbeaverSql", Set.of("config")),
            Map.entry("trimTrailingWhitespace", Set.of()),
            Map.entry("endWithNewline", Set.of()),
            Map.entry("removeSemicolons", Set.of()),
            Map.entry("indent", Set.of("type", "width")),
            Map.entry("properties", Set.of("maxBlankLines", "lineWidth", "indent", "wrap", "encoding")),
            Map.entry("json", Set.of("indent", "maxBlankLines", "lineWidth", "style")),
            Map.entry("xml", Set.of("indent", "maxBlankLines")),
            Map.entry("toml", Set.of("indent", "maxBlankLines")),
            Map.entry("yaml", Set.of("indent", "maxBlankLines")));

    /**
     * The options of jpm-format's own formats in their config sections ({@code "json": {"indentWidth": 2}}), with
     * the names of the {@code formatter} settings.
     */
    static final Map<String, Set<String>> FORMAT_OPTIONS = Map.of(
            "properties", Set.of("maxBlankLines", "lineWidth", "indentWidth", "wrap", "encoding"),
            "json", Set.of("indentWidth", "maxBlankLines", "lineWidth", "style"),
            "xml", Set.of("indentWidth", "maxBlankLines"),
            "toml", Set.of("indentWidth", "maxBlankLines"),
            "yaml", Set.of("indentWidth", "maxBlankLines"));

    /**
     * The built-in steps files get by their extension when no {@code "files"} entry includes them.
     */
    static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("properties", "properties"),
            Map.entry("json", "json"),
            Map.entry("jsonc", "json"),
            Map.entry("xml", "xml"),
            Map.entry("xsd", "xml"),
            Map.entry("xsl", "xml"),
            Map.entry("xslt", "xml"),
            Map.entry("wsdl", "xml"),
            Map.entry("fxml", "xml"),
            Map.entry("toml", "toml"),
            Map.entry("yml", "yaml"),
            Map.entry("yaml", "yaml"));

    private static final Set<String> NO_VERSION = Set.of("jsonSimple", "jacksonJson", "jacksonYaml", "dbeaverSql", "trimTrailingWhitespace",
            "endWithNewline", "removeSemicolons", "indent", "properties", "json", "xml", "toml", "yaml");

    /**
     * Formats text with one step; the plugin's steps run Spotless, tests replace them.
     */
    interface Fn {
        String format(String text, File file) throws Exception;
    }

    /**
     * How a step is made; tests replace it.
     */
    static volatile java.util.function.BiFunction<Path, Step, Fn> factory = SpotlessSteps::create;

    private static final Map<String, Fn> MADE = new ConcurrentHashMap<>();

    private SpotlessSteps() {
    }

    /**
     * The {@code "files"} entries of a config.
     */
    @SuppressWarnings("unchecked")
    static List<Entry> parse(Object files) {
        if (files == null) return List.of();
        if (!(files instanceof List<?> list))
            throw new FormatException("\"files\" must be an array of {\"include\": [...], \"steps\": [...]}");
        List<Entry> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m))
                throw new FormatException("\"files\" entries must be objects with \"include\" and \"steps\"");
            for (Object k : m.keySet()) {
                if (!k.equals("include") && !k.equals("steps"))
                    throw new FormatException("files: unknown key \"" + k + "\" (\"include\", \"steps\")");
            }
            List<String> include = new ArrayList<>();
            List<PathMatcher> matchers = new ArrayList<>();
            if (!(m.get("include") instanceof List<?> inc) || inc.isEmpty())
                throw new FormatException("files: \"include\" must be a non-empty array of globs");
            for (Object g : inc) {
                if (!(g instanceof String glob)) throw new FormatException("files.include must be globs");
                include.add(glob);
                matchers.addAll(Config.matchers(glob));
                if (glob.startsWith("**/")) matchers.addAll(Config.matchers(glob.substring(3)));
            }
            if (!(m.get("steps") instanceof List<?> st))
                throw new FormatException("files: \"steps\" must be an array ([] for none)");
            List<Step> steps = new ArrayList<>();
            for (Object s : st) steps.add(step(s));
            out.add(new Entry(List.copyOf(include), List.copyOf(matchers), List.copyOf(steps)));
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static Step step(Object s) {
        String name, version = null;
        Map<String, Object> options = new LinkedHashMap<>();
        if (s instanceof String text) {
            int at = text.indexOf('@');
            name = at < 0 ? text : text.substring(0, at);
            if (at >= 0) version = text.substring(at + 1);
        } else if (s instanceof Map<?, ?> m && m.get("step") instanceof String n) {
            name = n;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.equals("step")) continue;
                if (k.equals("version")) {
                    if (!(e.getValue() instanceof String v))
                        throw new FormatException("files.steps: \"version\" of " + n + " must be a string");
                    version = v;
                } else {
                    options.put(k, e.getValue());
                }
            }
        } else {
            throw new FormatException("files.steps entries are a step's name (\"ktfmt\", \"ktfmt@0.53\") or {\"step\": \"...\", ...}");
        }
        Set<String> allowed = STEPS.get(name);
        if (allowed == null) {
            throw new FormatException("files.steps: no step named \"" + name + "\"; the steps are " + String.join(", ", new java.util.TreeSet<>(STEPS.keySet())));
        }
        if (version != null && (version.isBlank() || NO_VERSION.contains(name))) {
            throw new FormatException("files.steps: " + name + (NO_VERSION.contains(name) ? " takes no version" : " needs a version after @"));
        }
        for (String k : options.keySet()) {
            if (!allowed.contains(k)) {
                throw new FormatException("files.steps: " + name + " has no option \"" + k + "\"" + (allowed.isEmpty() ? "" : " (" + String.join(", ",
                        new java.util.TreeSet<>(allowed)) + ")"));
            }
        }
        return new Step(name, version, Map.copyOf(options));
    }

    /**
     * The steps for a file, from every entry that includes it, in order; empty for Java files, which the engine formats.
     */
    static List<Step> stepsFor(List<Entry> entries, String rel) {
        return stepsFor(entries, rel, format -> Map.of());
    }

    /**
     * The same, with the options of jpm-format's own formats from their config sections under a step's own.
     */
    static List<Step> stepsFor(List<Entry> entries, String rel,
                               java.util.function.Function<String, Map<String, Object>> formats) {
        if (rel.endsWith(".java")) return List.of();
        Path p = Path.of(rel);
        List<Step> out = new ArrayList<>();
        boolean included = false;
        for (Entry e : entries) {
            for (PathMatcher m : e.matchers()) {
                if (m.matches(p)) {
                    for (Step s : e.steps()) out.add(withFormat(s, formats));
                    included = true;
                    break;
                }
            }
        }
        if (included) return out;
        int dot = rel.lastIndexOf('.');
        String step = dot < 0 || rel.lastIndexOf('/') > dot ? null : DEFAULTS.get(rel.substring(dot + 1));
        return step == null ? List.of() : List.of(withFormat(new Step(step, null, Map.of()), formats));
    }

    private static Step withFormat(Step s, java.util.function.Function<String, Map<String, Object>> formats) {
        if (!FORMAT_OPTIONS.containsKey(s.name())) return s;
        Map<String, Object> options = new LinkedHashMap<>(formats.apply(s.name()));
        options.putAll(s.options());
        return new Step(s.name(), s.version(), Map.copyOf(options));
    }

    /**
     * Formats a file with its steps; in check mode, a file the steps would change has one finding, with the formatted
     * text as its fix.
     */
    static Engine.Result run(Path root, List<Step> steps, String rel, String text, Engine.Mode mode) {
        boolean crlf = text.contains("\r\n");
        String cur = crlf ? text.replace("\r\n", "\n") : text;
        File file = root.resolve(rel).toAbsolutePath().toFile();
        for (Step s : steps) {
            Fn fn = MADE.computeIfAbsent(root + "\n" + s, k -> factory.apply(root, s));
            try {
                String next = fn.format(cur, file);
                if (next != null) cur = next;
            } catch (FormatException e) {
                throw e;
            } catch (Exception e) {
                throw new FormatException(rel + ": the " + s.name() + " step failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
            }
        }
        String out = crlf ? cur.replace("\n", "\r\n") : cur;
        List<Finding> findings = new ArrayList<>();
        if (mode == Engine.Mode.CHECK && !out.equals(text)) {
            int[] end = position(text, text.length());
            findings.add(new Finding(RULE, "style", Severity.ERROR, rel, 0, text.length(), 1, 1, end[0], end[1],
                    "Not formatted by " + String.join(", ", steps.stream().map(Step::name).toList()), Fix.replace("Format", 0, text.length(), out)));
        }
        return new Engine.Result(rel, text, mode == Engine.Mode.FORMAT ? out : text, findings, false);
    }

    private static int[] position(String text, int offset) {
        int line = 1, col = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new int[]{line, col};
    }

    private static Fn create(Path root, Step s) {
        int blank = number(s, "maxBlankLines", 2);
        switch (s.name()) {
            case "properties" -> {
                int width = Boolean.TRUE.equals(flag(s, "wrap")) ? width(s) : 0;
                int indent = number(s, "indent", 4);
                String encoding = choice(s, "encoding", null, "ISO-8859-1", "UTF-8");
                return (text, file) -> PropertiesFormat.format(text, blank, width, indent, encoding);
            }
            case "json" -> {
                int indent = number(s, "indent", 2);
                int width = width(s);
                String style = choice(s, "style", "keep", "keep", "compact", "pretty");
                return (text, file) -> JsonFormat.format(text, style, indent, blank, width);
            }
            case "xml" -> {
                int indent = number(s, "indent", 4);
                return (text, file) -> XmlFormat.format(text, indent, blank);
            }
            case "yaml" -> {
                int indent = number(s, "indent", 2);
                return (text, file) -> YamlFormat.format(text, indent, blank);
            }
            case "toml" -> {
                int indent = number(s, "indent", 4);
                return (text, file) -> TomlFormat.format(text, indent, blank);
            }
            default -> {
            }
        }
        ClassLoader loader = ExternalFormatter.spotless(root);
        Object provisioner = ExternalFormatter.provisioner(loader, root);
        Class<?> prov;
        try {
            prov = loader.loadClass("com.diffplug.spotless.Provisioner");
            Object step = switch (s.name()) {
                case "ktfmt" -> versioned(loader, "kotlin.KtfmtStep", s, prov, provisioner);
                case "ktlint" -> versioned(loader, "kotlin.KtLintStep", s, prov, provisioner);
                case "scalafmt" -> {
                    Class<?> c = loader.loadClass("com.diffplug.spotless.scala.ScalaFmtStep");
                    String v = s.version() != null ? s.version() : (String) c.getMethod("defaultVersion").invoke(null);
                    yield c.getMethod("create", String.class, prov, File.class).invoke(null, v, provisioner, file(root, s, "config"));
                }
                case "jacksonJson" -> staticCall(loader, "json.JacksonJsonStep", new Class<?>[]{prov}, provisioner);
                case "jacksonYaml" -> staticCall(loader, "yaml.JacksonYamlStep", new Class<?>[]{prov}, provisioner);
                case "jsonSimple" ->
                        staticCall(loader, "json.JsonSimpleStep", new Class<?>[]{int.class, prov}, number(s, "indent", 4), provisioner);
                case "dbeaverSql" -> {
                    File config = file(root, s, "config");
                    yield staticCall(loader, "sql.DBeaverSQLFormatterStep", new Class<?>[]{Iterable.class}, config == null ? List.of() : List.of(config));
                }
                case "trimTrailingWhitespace" ->
                        staticCall(loader, "generic.TrimTrailingWhitespaceStep", new Class<?>[0]);
                case "endWithNewline" -> staticCall(loader, "generic.EndWithNewlineStep", new Class<?>[0]);
                case "removeSemicolons" -> staticCall(loader, "groovy.RemoveSemicolonsStep", new Class<?>[0]);
                case "indent" -> {
                    Class<?> type = loader.loadClass("com.diffplug.spotless.generic.IndentStep$Type");
                    Object t = "tab".equals(s.options().get("type")) ? type.getField("TAB").get(null) : type.getField("SPACE").get(null);
                    yield staticCall(loader, "generic.IndentStep", new Class<?>[]{type, int.class}, t, number(s, "width", 4));
                }
                default -> throw new FormatException("no step named " + s.name());
            };
            Method format = loader.loadClass("com.diffplug.spotless.FormatterStep").getMethod("format", String.class, File.class);
            return (text, file) -> {
                try {
                    return (String) format.invoke(step, text, file);
                } catch (InvocationTargetException e) {
                    throw e.getCause() instanceof Exception ex ? ex : new IllegalStateException(e.getCause());
                }
            };
        } catch (InvocationTargetException e) {
            throw new FormatException("could not make the " + s.name() + " step: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new FormatException("could not make the " + s.name() + " step: " + e, e);
        }
    }

    private static Object versioned(ClassLoader loader, String cls, Step s, Class<?> prov, Object provisioner) throws ReflectiveOperationException {
        return s.version() == null
                ? staticCall(loader, cls, new Class<?>[]{prov}, provisioner)
                : staticCall(loader, cls, new Class<?>[]{String.class, prov}, s.version(), provisioner);
    }

    private static Object staticCall(ClassLoader loader, String cls, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        return loader.loadClass("com.diffplug.spotless." + cls).getMethod("create", types).invoke(null, args);
    }

    private static File file(Path root, Step s, String option) {
        Object v = s.options().get(option);
        if (v == null) return null;
        if (!(v instanceof String path))
            throw new FormatException("files.steps: \"" + option + "\" of " + s.name() + " must be a path");
        return root.resolve(path).toAbsolutePath().toFile();
    }

    private static int width(Step s) {
        Object v = s.options().get("lineWidth");
        if (v == null) return 120;
        if (!(v instanceof Number n) || n.intValue() < 20 || n.intValue() > 1000) {
            throw new FormatException("\"lineWidth\" of " + s.name() + " must be a number from 20 to 1000");
        }
        return n.intValue();
    }

    private static Boolean flag(Step s, String option) {
        Object v = s.options().get(option);
        if (v != null && !(v instanceof Boolean))
            throw new FormatException("\"" + option + "\" of " + s.name() + " must be true or false");
        return (Boolean) v;
    }

    private static String choice(Step s, String option, String fallback, String... values) {
        Object v = s.options().get(option);
        if (v == null) return fallback;
        for (String c : values) if (c.equals(v)) return c;
        throw new FormatException("\"" + option + "\" of " + s.name() + " must be " + String.join(", ", values));
    }

    private static int number(Step s, String option, int fallback) {
        Object v = s.options().get(option);
        if (v == null) return fallback;
        if (!(v instanceof Number n) || n.intValue() < 0 || n.intValue() > 16) {
            throw new FormatException("files.steps: \"" + option + "\" of " + s.name() + " must be a number from 0 to 16");
        }
        return n.intValue();
    }
}
