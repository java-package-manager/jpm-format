package dev.jpm.format;

import dev.jpm.format.api.Lint;
import dev.jpm.format.api.RuleSet;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The project's own rules: one Java file per rule in {@code rules/} at the project root (tests for them go in
 * {@code rules/test/}), and rule sets that dependencies list in {@code META-INF/services/dev.jpm.format.api.RuleSet}.
 *
 * <p>Rules are compiled against jpm-format into {@code .cache/format/rules/} and loaded into this JVM. A native
 * build of jpm-format cannot load classes, so {@link Main} hands the run to a JVM when a project has custom
 * rules; projects without them never pay for that. The rule API does not depend on where rules run.
 */
final class CustomRules {
    static final String SERVICE = "META-INF/services/" + RuleSet.class.getName();
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private CustomRules() {
    }

    /**
     * The rule sources in {@code rules/}, sorted.
     */
    static List<Path> sources(Path root) {
        Path dir = root.resolve("rules");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .filter(p ->
                            !dir.relativize(p).toString().replace('\\', '/').startsWith("test/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new FormatException("Could not read " + dir + ": " + e.getMessage());
        }
    }

    /**
     * Jars on the project's classpath that ship rule sets.
     */
    static List<Path> ruleSetJars(Project project) {
        List<Path> out = new ArrayList<>();
        for (Project.Module m : project.modules) {
            for (Path p : m.classpath()) {
                if (out.contains(p)) continue;
                if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                    try (JarFile jf = new JarFile(p.toFile())) {
                        if (jf.getEntry(SERVICE) != null) out.add(p);
                    } catch (IOException ignored) {
                    }
                } else if (Files.isRegularFile(p.resolve(SERVICE))) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /**
     * True if this project has custom rules of either kind.
     */
    static boolean present(Path root, Project project) {
        return !sources(root).isEmpty() || !ruleSetJars(project).isEmpty();
    }

    /**
     * Loads every custom rule into {@code registry}; returns a hash of their sources for the cache.
     */
    static String load(Path root, Project project, Registry registry) {
        StringBuilder hash = new StringBuilder();
        List<Path> jars = ruleSetJars(project);
        if (!jars.isEmpty()) {
            List<URL> urls = new ArrayList<>();
            for (Project.Module m : project.modules) for (Path p : m.classpath()) urls.add(url(p));
            for (Path j : jars) {
                hash.append(j).append('@');
                try {
                    hash.append(Files.getLastModifiedTime(j).toMillis());
                } catch (IOException ignored) {
                }
            }
            ClassLoader cl = new URLClassLoader(urls.toArray(URL[]::new), CustomRules.class.getClassLoader());
            for (RuleSet rs : ServiceLoader.load(RuleSet.class, cl)) {
                register(() -> rs.register(registry.rules), rs.getClass().getName());
            }
        }
        List<Path> srcs = sources(root);
        if (srcs.isEmpty()) return Hashing.sha256(hash.toString());
        for (Path p : srcs) {
            hash.append(root.relativize(p)).append('\n');
            try {
                hash.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            } catch (IOException e) {
                throw new FormatException("Could not read " + p + ": " + e.getMessage());
            }
        }
        hash.append(Main.VERSION);
        String h = Hashing.sha256(hash.toString());
        Path out = root.resolve(".cache").resolve("format").resolve("rules").resolve(h.substring(0, 16));
        if (!Files.isDirectory(out)) compile(srcs, out);
        ClassLoader cl = new URLClassLoader(new URL[]{url(out)}, CustomRules.class.getClassLoader());
        for (Path p : srcs) {
            String name = className(p);
            Class<?> c;
            try {
                c = Class.forName(name, true, cl);
            } catch (ClassNotFoundException | LinkageError e) {
                throw new FormatException(root.relativize(p) + ": could not load " + name + " (" + e + ")");
            }
            if (c.isInterface() || java.lang.reflect.Modifier.isAbstract(c.getModifiers())) continue;
            boolean isSet = RuleSet.class.isAssignableFrom(c), isRule = Lint.class.isAssignableFrom(c);
            if (!isSet && !isRule) continue;
            Object o;
            try {
                o = c.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new FormatException(
                        root.relativize(p) + ": " + name + " needs a public no-argument constructor (" + e + ")");
            }
            if (isSet) register(() -> ((RuleSet) o).register(registry.rules), name);
            else register(() -> registry.rules.add((Lint) o), name);
        }
        return h;
    }

    private static void register(Runnable r, String who) {
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            throw new FormatException(who + ": " + e.getMessage());
        }
    }

    private static String className(Path p) {
        String src;
        try {
            src = Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FormatException("Could not read " + p + ": " + e.getMessage());
        }
        String simple = p.getFileName().toString().replaceFirst("\\.java$", "");
        Matcher m = PACKAGE.matcher(src);
        return m.find() ? m.group(1) + "." + simple : simple;
    }

    private static void compile(List<Path> srcs, Path out) {
        try {
            Path tmp = Files.createDirectories(out.resolveSibling(
                    out.getFileName() + ".tmp" + ProcessHandle.current().pid()));
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm =
                         Source.JAVAC.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
                List<String> opts = List.of(
                        "-proc:none", "-d", tmp.toString(), "-cp", apiClasspath(), "-encoding", "UTF-8", "-Xlint:none");
                boolean ok = Source.JAVAC
                        .getTask(null, fm, diags, opts, null, fm.getJavaFileObjectsFromPaths(srcs))
                        .call();
                if (!ok) {
                    StringBuilder sb = new StringBuilder("Could not compile the custom rules:");
                    for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                        if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                        sb.append("\n  ")
                                .append(
                                        d.getSource() == null
                                                ? ""
                                                : Path.of(d.getSource().toUri()).getFileName() + ":" + d.getLineNumber()
                                                + ": ")
                                .append(d.getMessage(Locale.ROOT));
                    }
                    throw new FormatException(sb.toString());
                }
            }
            try {
                Files.move(tmp, out);
            } catch (IOException e) {
                if (!Files.isDirectory(out)) throw e;
            }
        } catch (IOException e) {
            throw new FormatException("Could not compile the custom rules: " + e.getMessage());
        }
    }

    /**
     * The classpath rules compile against: jpm-format itself (wherever it was loaded from) and the JVM's.
     */
    static String apiClasspath() {
        List<String> cp = new ArrayList<>();
        try {
            var src = Lint.class.getProtectionDomain().getCodeSource();
            if (src != null) cp.add(Path.of(src.getLocation().toURI()).toString());
        } catch (java.net.URISyntaxException | RuntimeException ignored) {
        }
        String jvm = System.getProperty("java.class.path");
        if (jvm != null && !jvm.isBlank()) cp.add(jvm);
        return String.join(java.io.File.pathSeparator, cp);
    }

    private static URL url(Path p) {
        try {
            return p.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
