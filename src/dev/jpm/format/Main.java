package dev.jpm.format;

import dev.jpm.format.api.Finding;
import dev.jpm.format.api.Formatter;
import dev.jpm.format.api.Lint;
import dev.jpm.format.api.Severity;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

/**
 * {@code jpm format}: formats and lints jpm projects. jpm runs it as an extension ({@code jpm format} runs
 * {@code jpm-format}).
 */
public final class Main {
    /**
     * The version in project.jsonc, from the {@code BuildConfig} class jpm generates ({@code "defineClass"}).
     */
    public static final String VERSION = BuildConfig.VERSION;

    static final String HELP = """
            jpm format - format and lint Java code

            Usage:
              jpm format [paths...]          Format every file and apply every safe fix (such as removing unused imports)
              jpm format --check [paths...]  Change nothing; report formatting differences and lint findings,
                                             and exit 1 if there are any (for CI)
              jpm format --stdin <path>      Format standard input as if it were <path>; print the result
              jpm format lsp                 Run the language server (stdio)
              jpm format test [--update]     Run the rule tests in rules/test/ (input .java files with
                                             .expected.java and .findings snapshots)
              jpm format rules               List the rules and how they are configured

            Options:
              --reporter <name>   pretty (default), json, sarif or github (the default in GitHub Actions)
              --debug             Run every formatter twice and report those whose output changes
              --no-cache          Ignore and do not write .cache/format/
              --threads <n>       Files processed in parallel (default: one per CPU)
              --version, --help

            Configuration: "extensions": { "format": { ... } } in project.jsonc, or jpm-format.jsonc.
            Suppress a finding with // jpm-ignore lint/<category>/<rule>: <reason>, @SuppressWarnings("<rule>")
            or //noinspection <rule>; protect code from the formatter with // jpm-ignore format or
            // @formatter:off ... // @formatter:on.
            """;

    private Main() {
    }

    @SuppressWarnings("ThrowablePrintStackTrace")
    public static void main(String[] argv) {
        int code;
        try {
            code = start(new ArrayList<>(Arrays.asList(argv)));
        } catch (FormatException e) {
            System.err.println("error: " + e.getMessage());
            code = 2;
        } catch (RuntimeException | LinkageError e) {
            StringBuilder msg = new StringBuilder("error: unexpected failure: " + e);
            for (Throwable c = e.getCause(); c != null && c != c.getCause(); c = c.getCause())
                msg.append("\n  caused by: ").append(c);
            System.err.println(msg);
            if (System.getenv("JPM_VERBOSE") != null) e.printStackTrace();
            code = 70;
        }
        System.out.flush();
        System.exit(code);
    }

    static int start(List<String> args) {
        if (args.contains("--help")
                || args.contains("-h")
                || (!args.isEmpty() && args.get(0).equals("help"))) {
            System.out.print(HELP);
            return 0;
        }
        if (args.contains("--version")) {
            System.out.println("jpm-format " + VERSION);
            return 0;
        }
        boolean nativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
        boolean child = System.getenv("JPM_FORMAT_CHILD") != null;
        if (!nativeImage && !Jdks.formatterSupported()) {
            if (child) throw new FormatException(Jdks.unsupportedMessage());
            return relaunch(args, System.getProperty("java.class.path"), Jdks.supportedJava());
        }
        if (!nativeImage && !Exports.open() && !child) {
            return relaunch(args, System.getProperty("java.class.path"), javaExecutable());
        }
        if (nativeImage && System.getenv("JPM_FORMAT_CHILD") == null) {
            Path root = Project.findRoot(Path.of(""));
            if (!CustomRules.sources(root).isEmpty()) {
                String cp = System.getenv("JPM_FORMAT_CLASSPATH");
                if (cp == null || cp.isBlank()) {
                    throw new FormatException(
                            "this project has custom rules, which need a JVM; set JPM_FORMAT_CLASSPATH to jpm-format's jar classpath");
                }
                return relaunch(args, cp, javaExecutable());
            }
        }
        String cmd = args.isEmpty() ? "" : args.get(0);
        return switch (cmd) {
            case "lsp" -> new Lsp(System.in, System.out).run();
            case "test" -> SnapshotTests.run(args.subList(1, args.size()));
            case "rules" -> rules();
            default -> run(args);
        };
    }

    /**
     * Runs this command again on the JVM {@code java} (JDK 19 or newer) with javac's internals exported.
     */
    static int relaunch(List<String> args, String classpath, String java) {
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.addAll(Exports.flags());
        boolean jarsOnly = Arrays.stream(classpath.split(File.pathSeparator)).allMatch(e -> e.endsWith(".jar"));
        if (jarsOnly) {
            Path cds = Path.of(
                    System.getProperty("user.home"),
                    ".cache",
                    "jpm-format",
                    VERSION + "-" + Hashing.sha256(classpath + java).substring(0, 12) + ".jsa");
            try {
                Files.createDirectories(cds.getParent());
                cmd.add("-XX:+AutoCreateSharedArchive");
                cmd.add("-XX:SharedArchiveFile=" + cds);
                cmd.add("-Xlog:cds*=off");
            } catch (IOException ignored) {
            }
        }
        cmd.add("-Xshare:auto");
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(Main.class.getName());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        pb.environment().put("JPM_FORMAT_CHILD", "1");
        try {
            return pb.start().waitFor();
        } catch (IOException e) {
            throw new FormatException("could not start " + java + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    private static String javaExecutable() {
        String home = System.getenv("JPM_JAVA_HOME");
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) {
            var cmd = ProcessHandle.current().info().command();
            if (cmd.isPresent()) return cmd.get();
            home = System.getProperty("java.home");
        }
        if (home == null || home.isBlank()) return "java";
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        return Path.of(home, "bin", win ? "java.exe" : "java").toString();
    }

    record Options(
            boolean check,
            String reporter,
            boolean debug,
            boolean cache,
            int threads,
            String stdinPath,
            List<Path> paths) {}

    static Options parse(List<String> args) {
        boolean check = false, debug = false, cache = true;
        String reporter = null, stdin = null;
        int threads = Runtime.getRuntime().availableProcessors();
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--check" -> check = true;
                case "--debug" -> debug = true;
                case "--no-cache" -> cache = false;
                case "--reporter" -> reporter = value(args, ++i, a);
                case "--threads" -> threads = Integer.parseInt(value(args, ++i, a));
                case "--stdin", "--stdin-filepath" -> stdin = value(args, ++i, a);
                default -> {
                    if (a.startsWith("--reporter=")) reporter = a.substring(11);
                    else if (a.startsWith("--threads=")) threads = Integer.parseInt(a.substring(10));
                    else if (a.startsWith("-"))
                        throw new FormatException("Unknown option " + a + ". Run `jpm format --help`.");
                    else paths.add(Path.of(a));
                }
            }
        }
        if (reporter == null) reporter = "true".equals(System.getenv("GITHUB_ACTIONS")) ? "github" : "pretty";
        Reporter.named(reporter);
        return new Options(check, reporter, debug, cache && !debug, Math.max(1, threads), stdin, paths);
    }

    private static String value(List<String> args, int i, String opt) {
        if (i >= args.size()) throw new FormatException(opt + " needs a value");
        return args.get(i);
    }

    static void warn(String msg) {
        System.err.println("warning: " + msg);
    }

    static int run(List<String> args) {
        Options o = parse(args);
        long t0 = System.nanoTime();
        Path cwd = Path.of("").toAbsolutePath();
        Path root = Project.findRoot(cwd);
        Workspace ws = Workspace.open(root, o.debug(), Main::warn);

        if (o.stdinPath() != null) {
            String text;
            try {
                text = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new FormatException("Could not read standard input: " + e.getMessage());
            }
            String rel = Config.rel(root, cwd.resolve(o.stdinPath()));
            Engine.Result r = ws.engine.run(rel, text, Engine.Mode.FORMAT);
            System.out.print(r.output());
            Reporter.named("pretty")
                    .report(
                            List.of(r),
                            new Reporter.Summary(1, r.changed() ? 1 : 0, false, 0),
                            ws.registry,
                            System.err);
            return r.syntaxError() ? 1 : 0;
        }

        List<Path> files = ws.files(o.paths());
        Cache cache = o.cache() ? Cache.open(root, ws.cacheKey) : null;
        Engine.Mode mode = o.check() ? Engine.Mode.CHECK : Engine.Mode.FORMAT;
        List<Engine.Result> results;
        ForkJoinPool pool = new ForkJoinPool(o.threads());
        try {
            results = pool.submit(() -> files.parallelStream()
                    .map(f -> process(ws, cache, f, mode))
                    .toList())
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdown();
        }
        if (cache != null) cache.save();

        int changed = (int) results.stream()
                .filter(r -> o.check() ? hasFormatFinding(r, ws) : r.changed())
                .count();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        PrintStream out = System.out;
        Reporter.named(o.reporter())
                .report(results, new Reporter.Summary(files.size(), changed, o.check(), ms), ws.registry, out);
        out.flush();

        List<Finding> all = Reporter.all(results);
        long errors = all.stream().filter(f -> f.severity() == Severity.ERROR).count();
        long warnings = all.size() - errors;
        String counts = (errors > 0 ? errors + " error" + (errors == 1 ? "" : "s") : "")
                + (errors > 0 && warnings > 0 ? ", " : "")
                + (warnings > 0 ? warnings + " warning" + (warnings == 1 ? "" : "s") : "");
        String time = ms < 1000 ? ms + "ms" : String.format("%.1fs", ms / 1000.0);
        if (o.check()) {
            System.err.println("Checked " + files.size() + " file" + (files.size() == 1 ? "" : "s") + " in " + time
                    + ": "
                    + (all.isEmpty() ? "no problems" : (changed > 0 ? changed + " not formatted, " : "") + counts));
            return all.isEmpty() ? 0 : 1;
        }
        System.err.println("Formatted " + changed + " of " + files.size() + " file" + (files.size() == 1 ? "" : "s")
                + " in " + time + (all.isEmpty() ? "" : "; " + counts + " left"));
        boolean broken = results.stream()
                .anyMatch(r -> r.syntaxError()
                        || r.findings().stream().anyMatch(f -> f.category().equals("internal")));
        return broken ? 1 : 0;
    }

    private static boolean hasFormatFinding(Engine.Result r, Workspace ws) {
        for (Finding f : r.findings()) {
            if (ws.registry.rules.get(f.rule()) instanceof Formatter) return true;
        }
        return false;
    }

    private static Engine.Result process(Workspace ws, Cache cache, Path file, Engine.Mode mode) {
        String rel = Config.rel(ws.root, file);
        String text = Workspace.read(file);
        if (cache != null) {
            Cache.Entry e = cache.get(rel, text);
            if (e != null && (mode == Engine.Mode.CHECK || e.formatted())) {
                return new Engine.Result(rel, text, text, e.findings(), false);
            }
        }
        Engine.Result r = ws.engine.run(rel, text, mode);
        if (mode == Engine.Mode.FORMAT && r.changed()) {
            try {
                Files.writeString(file, r.output(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new FormatException("Could not write " + file + ": " + e.getMessage());
            }
        }
        if (cache != null
                && !r.syntaxError()
                && r.findings().stream().noneMatch(f -> f.category().equals("internal"))) {
            boolean formatted = mode == Engine.Mode.FORMAT || !hasFormatFinding(r, ws);
            cache.put(rel, r.output(), formatted, r.findings());
        }
        return r;
    }

    static int rules() {
        Path root = Project.findRoot(Path.of(""));
        Workspace ws = Workspace.open(root, Project.untyped(root, "listing rules"), false, Main::warn);
        Config.Settings s = ws.config.settings("x.java");
        Map<String, List<Lint>> byCat = new LinkedHashMap<>();
        for (Lint l : ws.registry.rules.all()) {
            if (Registry.internal(l)) continue;
            byCat.computeIfAbsent(l.category(), k -> new ArrayList<>()).add(l);
        }
        for (Map.Entry<String, List<Lint>> e : byCat.entrySet()) {
            System.out.println(e.getKey());
            for (Lint l : e.getValue()) {
                Severity lv = s.level(l, ws.registry.builtin(l));
                String kind = l instanceof Formatter ? "formatter" : "lint";
                System.out.printf(
                        "  %-28s %-6s %-9s%s%s%n",
                        l.id(),
                        lv,
                        kind,
                        l.typeAware() ? " (needs types)" : "",
                        ws.registry.builtin(l) ? "" : " (custom)");
            }
        }
        return 0;
    }
}
