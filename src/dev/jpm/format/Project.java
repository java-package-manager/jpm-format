package dev.jpm.format;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import dev.jpm.format.api.TypeInfo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

/**
 * The project being formatted: its root folder and, when known, each module's classpath for type-aware rules.
 *
 * <p>The classpath comes from {@code JPM_CLASSPATH} when jpm passes it, and otherwise from
 * {@code jpm info --json --classpath --ide}. Outside a jpm project, or when its dependencies are not installed,
 * there is none: type-aware rules are skipped and everything else still runs.
 */
public final class Project {
    /** A module: its folder, the folders its sources live in, and its compile classpath. */
    public record Module(Path dir, List<Path> sourceDirs, List<Path> classpath) {}

    public final Path root;
    /** Modules with a known classpath, deepest folder first; empty when unknown. */
    public final List<Module> modules;
    /** Why there is no classpath, or null. */
    public final String noTypesReason;

    public Project(Path root, List<Module> modules, String noTypesReason) {
        this.root = root;
        List<Module> sorted = new ArrayList<>(modules);
        sorted.sort(
                Comparator.comparingInt((Module m) -> m.dir().getNameCount()).reversed());
        this.modules = List.copyOf(sorted);
        this.noTypesReason = noTypesReason;
    }

    /** A project without type information. */
    public static Project untyped(Path root, String reason) {
        return new Project(root, List.of(), reason);
    }

    /** The folder with a project.jsonc at or above {@code from}, the outermost of a workspace; else {@code from}. */
    public static Path findRoot(Path from) {
        String env = System.getenv("JPM_PROJECT_DIR");
        if (env != null && !env.isBlank()) return Path.of(env).toAbsolutePath().normalize();
        Path found = null;
        for (Path p = from.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("project.jsonc"))) found = p;
            if (Files.isDirectory(p.resolve(".git"))) break;
        }
        return found != null ? found : from.toAbsolutePath().normalize();
    }

    /** Loads the classpath: {@code JPM_CLASSPATH}, else {@code jpm info}. Never throws. */
    public static Project load(Path root) {
        String cp = System.getenv("JPM_CLASSPATH");
        if (cp != null && !cp.isBlank()) {
            List<Path> entries = new ArrayList<>();
            for (String e : cp.split(File.pathSeparator)) if (!e.isBlank()) entries.add(Path.of(e));
            List<Path> srcs = new ArrayList<>();
            for (String d : new String[] {"src", "test", "src/main/java", "src/test/java"}) {
                if (Files.isDirectory(root.resolve(d))) srcs.add(root.resolve(d));
            }
            return new Project(root, List.of(new Module(root, srcs, entries)), null);
        }
        if (!Files.isRegularFile(root.resolve("project.jsonc"))) {
            return untyped(root, "not in a jpm project (no project.jsonc)");
        }
        String jpm = System.getenv("JPM_EXE");
        if (jpm == null || jpm.isBlank()) jpm = "jpm";
        try {
            Process p = new ProcessBuilder(jpm, "info", "--json", "--classpath", "--ide")
                    .directory(root.toFile())
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return untyped(root, "`jpm info` failed (are the dependencies installed? run `jpm install`)");
            }
            int brace = out.indexOf('{');
            if (brace < 0) return untyped(root, "`jpm info --json` printed no JSON");
            return fromInfo(root, Json.object(out.substring(brace), "jpm info --json"));
        } catch (IOException e) {
            return untyped(root, System.getenv("JPM_EXE") == null ? "jpm is not on PATH" : "could not run " + jpm);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return untyped(root, "interrupted");
        } catch (FormatException e) {
            return untyped(root, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static Project fromInfo(Path root, Map<String, Object> info) {
        List<Module> mods = new ArrayList<>();
        for (Object o : (List<Object>) info.getOrDefault("modules", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.get("dir") == null || !(m.get("compileClasspath") instanceof List<?> cpl)) continue;
            List<Path> srcs = new ArrayList<>();
            for (String k : new String[] {"sourceDir", "testDir", "generatedSourceDir"}) {
                if (m.get(k) instanceof String s) srcs.add(Path.of(s));
            }
            List<Path> cp = new ArrayList<>();
            for (Object e : cpl) cp.add(Path.of(String.valueOf(e)));
            mods.add(new Module(Path.of((String) m.get("dir")), srcs, cp));
        }
        if (mods.isEmpty()) return untyped(root, "`jpm info` listed no classpath (jpm 0.4.0 or newer is needed)");
        return new Project(root, mods, null);
    }

    /** The module a file belongs to, or null. */
    public Module moduleOf(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        for (Module m : modules) if (abs.startsWith(m.dir())) return m;
        return null;
    }

    /** True if type information can be computed for files of this project. */
    public boolean typed() {
        return !modules.isEmpty();
    }

    /** A hash of every classpath, for the cache. */
    public String classpathKey() {
        StringBuilder sb = new StringBuilder();
        for (Module m : modules) {
            sb.append(m.dir()).append('\n');
            for (Path p : m.classpath()) {
                sb.append(p);
                try {
                    sb.append('@').append(Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {
                    sb.append("@missing");
                }
                sb.append('\n');
            }
        }
        return Hashing.sha256(sb.toString());
    }

    // ------------------------------------------------------------------ attribution

    private static final ThreadLocal<StandardJavaFileManager> FILE_MANAGERS =
            ThreadLocal.withInitial(() -> Source.JAVAC.getStandardFileManager(null, null, StandardCharsets.UTF_8));

    /** Attributes one file's text against its module's classpath; null if the file has no module. */
    public TypeInfo attribute(Path file, String text) {
        Module m = moduleOf(file);
        if (m == null) return null;
        List<String> opts = new ArrayList<>(List.of(
                "-proc:none",
                "-Xlint:none",
                "-implicit:none",
                "-XDshould-stop.ifError=FLOW",
                "-XDshould-stop.ifNoError=FLOW"));
        opts.add("-classpath");
        opts.add(join(m.classpath()));
        List<Path> srcs = m.sourceDirs().stream().filter(Files::isDirectory).toList();
        if (!srcs.isEmpty()) {
            opts.add("-sourcepath");
            opts.add(join(srcs));
        }
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) Source.JAVAC.getTask(
                null,
                FILE_MANAGERS.get(),
                diags,
                opts,
                null,
                List.of(Source.fileObject(
                        root.relativize(file.toAbsolutePath().normalize()).toString(), text)));
        try {
            CompilationUnitTree cu = null;
            for (CompilationUnitTree u : task.parse()) cu = u;
            task.analyze();
            return cu == null ? null : new TypeInfo(cu, Trees.instance(task), task.getTypes(), task.getElements());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String join(List<Path> ps) {
        StringBuilder sb = new StringBuilder();
        for (Path p : ps) {
            if (!sb.isEmpty()) sb.append(File.pathSeparator);
            sb.append(p);
        }
        return sb.toString();
    }
}
