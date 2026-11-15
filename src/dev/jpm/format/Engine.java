package dev.jpm.format;

import dev.jpm.format.api.Context;
import dev.jpm.format.api.Finding;
import dev.jpm.format.api.Formatter;
import dev.jpm.format.api.Lint;
import dev.jpm.format.api.Severity;
import dev.jpm.format.api.TypeInfo;
import dev.jpm.format.rules.JavaFormat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs the rules on one file. {@link Mode#FORMAT} runs every formatter in order, each on the previous one's
 * output, then lints the result. {@link Mode#CHECK} changes nothing: it runs {@code check} on every rule, so
 * formatters report where their output would differ.
 */
public final class Engine {
    public enum Mode {
        FORMAT,
        CHECK
    }

    /** What happened to one file. */
    public record Result(String path, String original, String output, List<Finding> findings, boolean syntaxError) {
        public boolean changed() {
            return !original.equals(output);
        }
    }

    final Project project;
    final Config config;
    final Registry registry;
    private final boolean debug;
    private final Consumer<String> warn;
    private final AtomicBoolean warnedTypes = new AtomicBoolean();

    public Engine(Project project, Config config, Registry registry, boolean debug, Consumer<String> warn) {
        this.project = project;
        this.config = config;
        this.registry = registry;
        this.debug = debug;
        this.warn = warn;
    }

    /** The rules that run on a file at their levels, formatters in order. */
    private record Active(Lint rule, Severity level) {}

    private List<Active> active(Config.Settings s) {
        List<Active> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Lint r : registry.rules.all()) {
            if (Registry.internal(r)) continue;
            Severity lv = s.level(r, registry.builtin(r));
            if (lv == Severity.OFF) continue;
            if (r.typeAware() && !project.typed()) {
                skipped.add(r.id());
                continue;
            }
            out.add(new Active(r, lv));
        }
        if (!skipped.isEmpty() && warnedTypes.compareAndSet(false, true)) {
            warn.accept("type-aware rules skipped (" + String.join(", ", skipped) + "): " + project.noTypesReason);
        }
        return out;
    }

    public Result run(String rel, String text, Mode mode) {
        Source src = new Source(rel, text);
        Config.Settings settings = config.settings(rel);
        List<Finding> findings = new ArrayList<>();
        if (!src.errors().isEmpty()) {
            for (Source.SyntaxError e : src.errors()) {
                findings.add(FileContext.finding(
                        src, Registry.PARSE_ERROR, "syntax", Severity.ERROR, e.start(), e.end(), e.message(), null));
            }
            return new Result(rel, text, text, findings, true);
        }
        List<Active> rules = active(settings);
        Source cur = src;
        // what `jpm format` makes of the file; in check mode, the reference formatting findings are judged by
        Pipeline formatted = pipeline(src, settings, rules, mode == Mode.FORMAT ? findings : new ArrayList<>());
        if (mode == Mode.FORMAT) cur = formatted.out();
        boolean clean = formatted.out().text.equals(src.text);
        Supplier<TypeInfo> types = typesFor(cur);
        List<int[]> protectedRegions = null;
        List<Finding> formatting = new ArrayList<>();
        for (Active a : rules) {
            if (a.rule() instanceof Formatter && (mode == Mode.FORMAT || clean)) continue;
            List<Finding> sink = new ArrayList<>();
            FileContext ctx = new FileContext(cur, a.rule(), a.level(), settings, types, sink);
            try {
                if (a.rule() instanceof Formatter f && defaultCheck(f)) {
                    Source at = cur;
                    wrap(f, () -> apply(f, at, settings, findings)).check(ctx); // also checks idempotence in debug mode
                } else {
                    a.rule().check(ctx);
                    if (a.rule() instanceof Formatter f) {
                        if (protectedRegions == null) protectedRegions = Regions.find(cur);
                        List<int[]> pr = protectedRegions;
                        sink.removeIf(fd -> pr.stream().anyMatch(r -> fd.start() < r[1] && fd.end() > r[0]));
                        if (debug) apply(f, cur, settings, findings);
                    }
                }
            } catch (RuntimeException | StackOverflowError e) {
                findings.add(internal(cur, Registry.RULE_CRASH, a.rule().id() + " failed: " + e));
            }
            (a.rule() instanceof Formatter ? formatting : findings).addAll(sink);
        }
        if (mode == Mode.CHECK && !clean) findings.addAll(attribute(src, settings, formatted, formatting));
        List<Finding> result = suppress(cur, settings, rules, findings);
        result.sort(Comparator.comparingInt(Finding::start).thenComparing(Finding::rule));
        return new Result(rel, text, cur.text, result, false);
    }

    /** The result of running every formatter in order, and the last one that changed the text. */
    private record Pipeline(Source out, Formatter lastChange) {}

    private Pipeline pipeline(Source src, Config.Settings settings, List<Active> rules, List<Finding> findings) {
        Source cur = src;
        Formatter last = null;
        for (Active a : rules) {
            if (!(a.rule() instanceof Formatter f)) continue;
            String out = apply(f, cur, settings, findings);
            if (out.equals(cur.text)) continue;
            Source next = new Source(src.path, out);
            if (!next.errors().isEmpty()) {
                findings.add(internal(
                        cur,
                        Registry.RULE_CRASH,
                        f.id() + " produced code that does not parse (" + next.errors().get(0).message()
                                + "); its changes were dropped"));
                continue;
            }
            cur = next;
            last = f;
        }
        return new Pipeline(cur, last);
    }

    /**
     * Formatting findings for {@code --check}: each formatter judged on its own would also flag what a later
     * formatter changes on purpose, so its findings count only where the file differs from what the whole pipeline
     * makes of it. Differences no single formatter explains (formatters working together) are reported for the last
     * formatter that changed the text.
     */
    private List<Finding> attribute(Source src, Config.Settings settings, Pipeline formatted, List<Finding> own) {
        List<Finding> differences = new ArrayList<>();
        Formatter blame = formatted.lastChange();
        if (blame == null) return differences; // nothing changes: nothing to report
        Lint rule = registry.rules.get(blame.id());
        FileContext ctx = new FileContext(src, rule, settings.level(rule, registry.builtin(rule)), settings, null, differences);
        wrap(blame, () -> formatted.out().text).check(ctx);
        List<Finding> out = new ArrayList<>();
        for (Finding f : own) if (differences.stream().anyMatch(d -> overlaps(f, d))) out.add(f);
        for (Finding d : differences) if (out.stream().noneMatch(f -> overlaps(f, d))) out.add(d);
        return out;
    }

    /** True if two findings touch; an insertion (empty range) touches what is right next to it. */
    private static boolean overlaps(Finding a, Finding b) {
        if (a.start() == a.end() || b.start() == b.end()) return a.start() <= b.end() && b.start() <= a.end();
        return a.start() < b.end() && b.start() < a.end();
    }

    /** Formats only {@code [start, end)} with the core formatter, for editors. */
    public String formatRange(String rel, String text, int start, int end) {
        Source src = new Source(rel, text);
        if (!src.errors().isEmpty()) return text;
        Config.Settings settings = config.settings(rel);
        Lint jf = registry.rules.get(JavaFormat.ID);
        if (!(jf instanceof JavaFormat f) || settings.level(jf, true) == Severity.OFF) return text;
        FileContext ctx = new FileContext(src, f, Severity.ERROR, settings, null, new ArrayList<>());
        return Regions.restore(src, f.formatRange(ctx, start, end));
    }

    /** Runs one formatter on {@code cur}, keeping protected regions; in debug mode, checks idempotence. */
    private String apply(Formatter f, Source cur, Config.Settings settings, List<Finding> findings) {
        FileContext ctx = new FileContext(cur, f, Severity.ERROR, settings, typesFor(cur), new ArrayList<>());
        String out;
        try {
            out = f.format(ctx);
        } catch (RuntimeException | StackOverflowError e) {
            synchronized (findings) {
                findings.add(internal(cur, Registry.RULE_CRASH, f.id() + " failed: " + e));
            }
            return cur.text;
        }
        if (out == null) return cur.text;
        out = Regions.restore(cur, out);
        if (debug && !out.equals(cur.text)) {
            Source once = new Source(cur.path, out);
            if (once.errors().isEmpty()) {
                FileContext c2 = new FileContext(once, f, Severity.ERROR, settings, typesFor(once), new ArrayList<>());
                String twice;
                try {
                    twice = Regions.restore(once, f.format(c2));
                } catch (RuntimeException e) {
                    twice = out;
                }
                if (twice != null && !twice.equals(out)) {
                    synchronized (findings) {
                        findings.add(internal(
                                cur,
                                Registry.NOT_IDEMPOTENT,
                                f.id() + " is not idempotent: formatting its output again changes it"));
                    }
                }
            }
        }
        return out;
    }

    private static Formatter wrap(Formatter f, Supplier<String> format) {
        return new Formatter() {
            @Override
            public String id() {
                return f.id();
            }

            @Override
            public String format(Context ctx) {
                return format.get();
            }
        };
    }

    static boolean defaultCheck(Formatter f) {
        try {
            return f.getClass().getMethod("check", Context.class).getDeclaringClass() == Formatter.class;
        } catch (NoSuchMethodException e) {
            return true;
        }
    }

    private Supplier<TypeInfo> typesFor(Source s) {
        if (!project.typed()) return null;
        Path file = project.root.resolve(s.path);
        return new Supplier<>() {
            private TypeInfo value;
            private boolean done;

            @Override
            public synchronized TypeInfo get() {
                if (!done) {
                    value = project.attribute(file, s.text);
                    done = true;
                }
                return value;
            }
        };
    }

    private Finding internal(Source s, String rule, String message) {
        return FileContext.finding(s, rule, registry.rules.get(rule).category(), Severity.ERROR, 0, 0, message, null);
    }

    private List<Finding> suppress(Source src, Config.Settings settings, List<Active> rules, List<Finding> findings) {
        Suppressions sup = Suppressions.of(src, registry);
        List<Finding> out = new ArrayList<>();
        for (Finding f : findings) {
            if (f.rule().equals(Registry.PARSE_ERROR) || f.category().equals("internal")) {
                out.add(f);
                continue;
            }
            if (!sup.suppressed(f, registry.rules.get(f.rule()))) out.add(f);
        }
        Set<String> ran = new TreeSet<>();
        for (Active a : rules) ran.add(a.rule().id());
        List<Suppressions.Problem> problems = new ArrayList<>(sup.problems);
        Lint unusedRule = registry.rules.get(Suppressions.UNUSED);
        if (settings.level(unusedRule, true) != Severity.OFF) {
            problems.addAll(sup.unused(target -> {
                String[] p = target.split("/");
                if (p.length < 3) return true;
                return ran.contains(p[2]);
            }));
        }
        for (Suppressions.Problem p : problems) {
            Lint r = registry.rules.get(p.rule());
            Severity lv = settings.level(r, true);
            if (lv == Severity.OFF) continue;
            out.add(FileContext.finding(src, p.rule(), r.category(), lv, p.start(), p.end(), p.message(), null));
        }
        return out;
    }
}
