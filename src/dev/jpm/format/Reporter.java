package dev.jpm.format;

import dev.jpm.format.api.Finding;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.Lint;
import dev.jpm.format.api.Severity;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Prints findings: {@code pretty} (default), {@code json}, {@code sarif}, or {@code github} annotations. */
public interface Reporter {
    /** Totals of a run. */
    record Summary(int files, int changed, boolean check, long millis) {}

    void report(List<Engine.Result> results, Summary summary, Registry registry, PrintStream out);

    static Reporter named(String name) {
        return switch (name) {
            case "pretty" -> new Pretty();
            case "json" -> new JsonReporter();
            case "sarif" -> new Sarif();
            case "github" -> new Github();
            default ->
                throw new FormatException("Unknown reporter \"" + name + "\"; use pretty, json, sarif or github");
        };
    }

    static List<Finding> all(List<Engine.Result> results) {
        List<Finding> out = new ArrayList<>();
        for (Engine.Result r : results) out.addAll(r.findings());
        return out;
    }

    static Map<String, Object> json(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", f.rule());
        m.put("category", f.category());
        m.put("severity", f.severity().toString());
        m.put("path", f.path());
        m.put("start", f.start());
        m.put("end", f.end());
        m.put("line", f.line());
        m.put("column", f.column());
        m.put("endLine", f.endLine());
        m.put("endColumn", f.endColumn());
        m.put("message", f.message());
        if (f.fix() != null) {
            Map<String, Object> fx = new LinkedHashMap<>();
            fx.put("description", f.fix().description());
            List<Object> edits = new ArrayList<>();
            for (Fix.Edit e : f.fix().edits()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("start", e.start());
                em.put("end", e.end());
                em.put("replacement", e.replacement());
                edits.add(em);
            }
            fx.put("edits", edits);
            m.put("fix", fx);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    static Finding fromJson(Map<String, Object> m) {
        Fix fix = null;
        if (m.get("fix") instanceof Map<?, ?> fx) {
            List<Fix.Edit> edits = new ArrayList<>();
            for (Object o : (List<Object>) fx.get("edits")) {
                Map<String, Object> e = (Map<String, Object>) o;
                edits.add(new Fix.Edit(i(e.get("start")), i(e.get("end")), (String) e.get("replacement")));
            }
            fix = new Fix((String) fx.get("description"), edits);
        }
        return new Finding(
                (String) m.get("rule"),
                (String) m.get("category"),
                Severity.parse((String) m.get("severity")),
                (String) m.get("path"),
                i(m.get("start")),
                i(m.get("end")),
                i(m.get("line")),
                i(m.get("column")),
                i(m.get("endLine")),
                i(m.get("endColumn")),
                (String) m.get("message"),
                fix);
    }

    private static int i(Object o) {
        return ((Number) o).intValue();
    }

    /** Human-readable output with a code frame, and the expected text for formatting findings. */
    final class Pretty implements Reporter {
        private final boolean color = System.console() != null && System.getenv("NO_COLOR") == null;

        private String c(String code, String s) {
            return color ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
        }

        @Override
        public void report(List<Engine.Result> results, Summary summary, Registry registry, PrintStream out) {
            for (Engine.Result r : results) {
                if (r.findings().isEmpty()) continue;
                Source src = new Source(r.path(), r.output());
                for (Finding f : r.findings()) {
                    String sev = f.severity() == Severity.ERROR ? c("31;1", "error") : c("33;1", "warn");
                    out.println(c("1", f.path() + ":" + f.line() + ":" + f.column()) + " " + sev + " "
                            + c("2", f.category() + "/" + f.rule()));
                    out.println("  " + f.message());
                    frame(src, f, out);
                    out.println();
                }
            }
        }

        private void frame(Source src, Finding f, PrintStream out) {
            if (f.start() == 0 && f.end() == 0 && f.category().equals("internal")) return;
            boolean formatting = f.fix() != null && "Format".equals(f.fix().description());
            int first = f.line(),
                    last =
                            Math.max(
                                    first,
                                    f.end() > f.start() && src.text.length() >= f.end()
                                            ? src.line(Math.max(f.start(), f.end() - 1))
                                            : first);
            int shown = 0;
            for (int l = first; l <= last && shown < 6; l++, shown++) {
                String text = lineText(src, l);
                out.println(c("2", String.format("%5d | ", l)) + (formatting ? c("31", "- " + text) : text));
            }
            if (last - first >= 6) out.println(c("2", "      | ..."));
            if (formatting) {
                String[] want = f.fix().edits().get(0).replacement().split("\n", -1);
                int n = want.length > 0 && want[want.length - 1].isEmpty() ? want.length - 1 : want.length;
                for (int k = 0; k < Math.min(n, 6); k++) out.println(c("2", "      | ") + c("32", "+ " + want[k]));
                if (n > 6) out.println(c("2", "      | ..."));
            } else if (first == last) {
                int col = f.column();
                int len = Math.max(
                        1, Math.min(f.end() - f.start(), lineText(src, first).length() - col + 1));
                out.println(c("2", "      | ") + " ".repeat(Math.max(0, col - 1)) + c("31", "^".repeat(len)));
            }
        }

        private static String lineText(Source src, int l) {
            int s = src.lineStart(l), e = src.lineStart(l + 1);
            String t = src.text.substring(s, Math.min(e, src.text.length()));
            return t.replaceAll("\r?\n$", "");
        }
    }

    final class JsonReporter implements Reporter {
        @Override
        public void report(List<Engine.Result> results, Summary summary, Registry registry, PrintStream out) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("tool", "jpm-format");
            root.put("version", Main.VERSION);
            List<Object> fs = new ArrayList<>();
            for (Finding f : all(results)) fs.add(json(f));
            root.put("findings", fs);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("files", summary.files());
            s.put(summary.check() ? "unformatted" : "formatted", summary.changed());
            s.put(
                    "errors",
                    all(results).stream()
                            .filter(f -> f.severity() == Severity.ERROR)
                            .count());
            s.put(
                    "warnings",
                    all(results).stream()
                            .filter(f -> f.severity() == Severity.WARN)
                            .count());
            s.put("millis", summary.millis());
            root.put("summary", s);
            out.println(Json.pretty(root));
        }
    }

    /** SARIF 2.1.0, for GitHub code scanning and other tools. */
    final class Sarif implements Reporter {
        @Override
        public void report(List<Engine.Result> results, Summary summary, Registry registry, PrintStream out) {
            List<Finding> all = all(results);
            Map<String, Integer> index = new TreeMap<>();
            for (Finding f : all) index.putIfAbsent(f.category() + "/" + f.rule(), 0);
            List<Object> rules = new ArrayList<>();
            int k = 0;
            for (String id : index.keySet()) {
                index.put(id, k++);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", id);
                Lint l = registry.rules.get(id.substring(id.indexOf('/') + 1));
                if (l != null && l.docs() != null) r.put("helpUri", l.docs());
                rules.add(r);
            }
            List<Object> res = new ArrayList<>();
            for (Finding f : all) {
                Map<String, Object> r = new LinkedHashMap<>();
                String id = f.category() + "/" + f.rule();
                r.put("ruleId", id);
                r.put("ruleIndex", index.get(id));
                r.put("level", f.severity() == Severity.ERROR ? "error" : "warning");
                r.put("message", Map.of("text", f.message()));
                Map<String, Object> region = new LinkedHashMap<>();
                region.put("startLine", f.line());
                region.put("startColumn", f.column());
                region.put("endLine", f.endLine());
                region.put("endColumn", f.endColumn());
                Map<String, Object> phys = new LinkedHashMap<>();
                phys.put("artifactLocation", Map.of("uri", f.path(), "uriBaseId", "%SRCROOT%"));
                phys.put("region", region);
                r.put("locations", List.of(Map.of("physicalLocation", phys)));
                res.add(r);
            }
            Map<String, Object> driver = new LinkedHashMap<>();
            driver.put("name", "jpm-format");
            driver.put("version", Main.VERSION);
            driver.put("informationUri", "https://github.com/java-package-manager/jpm-format");
            driver.put("rules", rules);
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("tool", Map.of("driver", driver));
            run.put("results", res);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
            root.put("version", "2.1.0");
            root.put("runs", List.of(run));
            out.println(Json.pretty(root));
        }
    }

    /** GitHub Actions workflow commands, shown as annotations on the pull request. */
    final class Github implements Reporter {
        @Override
        public void report(List<Engine.Result> results, Summary summary, Registry registry, PrintStream out) {
            for (Finding f : all(results)) {
                out.println("::" + (f.severity() == Severity.ERROR ? "error" : "warning")
                        + " file=" + prop(f.path()) + ",line=" + f.line() + ",col=" + f.column()
                        + ",endLine=" + f.endLine() + ",endColumn=" + f.endColumn()
                        + ",title=" + prop(f.qualifiedRule()) + "::" + data(f.message()));
            }
        }

        static String data(String s) {
            return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
        }

        static String prop(String s) {
            return data(s).replace(":", "%3A").replace(",", "%2C");
        }
    }
}
