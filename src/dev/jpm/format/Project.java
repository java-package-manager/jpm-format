package dev.jpm.format;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import dev.jpm.api.client.JpmClient;
import dev.jpm.api.client.JpmError;
import dev.jpm.api.client.JpmNotInstalled;
import dev.jpm.api.client.LoadOptions;
import dev.jpm.api.client.ProtocolMismatch;
import dev.jpm.api.model.Classpaths;
import dev.jpm.api.model.Problem;
import dev.jpm.format.api.TypeInfo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

/**
 * The project being formatted: its root folder and, when known, each module's classpath for type-aware rules.
 *
 * <p>The classpath comes from {@code JPM_CLASSPATH} when jpm passes it, and otherwise from jpm over its protocol
 * ({@code jpm api}, with jpm's client dev.jpm:jpm-api). Outside a jpm project, or when its dependencies are not installed,
 * there is none: type-aware rules are skipped and everything else still runs.
 */
public final class Project {
    /**
     * A module: its folder, the folders its sources live in, and its compile classpath.
     */
    public record Module(Path dir, List<Path> sourceDirs, List<Path> classpath) {
    }

    public final Path root;
    /**
     * Modules with a known classpath, deepest folder first; empty when unknown.
     */
    public final List<Module> modules;
    /**
     * Why there is no classpath, or null.
     */
    public final String noTypesReason;

    public Project(Path root, List<Module> modules, String noTypesReason) {
        this.root = root;
        List<Module> sorted = new ArrayList<>(modules);
        sorted.sort(
                Comparator.comparingInt((Module m) -> m.dir().getNameCount()).reversed());
        this.modules = List.copyOf(sorted);
        this.noTypesReason = noTypesReason;
    }

    /**
     * A project without type information.
     */
    public static Project untyped(Path root, String reason) {
        return new Project(root, List.of(), reason);
    }

    /**
     * The folder with a project.jsonc at or above {@code from}, the outermost of a workspace; else {@code from}.
     */
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

    /**
     * Loads the classpath: {@code JPM_CLASSPATH}, else jpm's {@code project/load} and {@code project/classpaths}. Never throws.
     */
    public static Project load(Path root) {
        String cp = System.getenv("JPM_CLASSPATH");
        if (cp != null && !cp.isBlank()) {
            List<Path> entries = new ArrayList<>();
            for (String e : cp.split(File.pathSeparator)) if (!e.isBlank()) entries.add(Path.of(e));
            List<Path> srcs = new ArrayList<>();
            for (String d : new String[]{"src", "test", "src/main/java", "src/test/java"}) {
                if (Files.isDirectory(root.resolve(d))) srcs.add(root.resolve(d));
            }
            return new Project(root, List.of(new Module(root, srcs, entries)), null);
        }
        if (!Files.isRegularFile(root.resolve("project.jsonc"))) {
            return untyped(root, "not in a jpm project (no project.jsonc)");
        }
        String jpm = System.getenv("JPM_EXE");
        if (jpm == null || jpm.isBlank()) jpm = "jpm";
        try (JpmClient client = JpmClient.start(List.of(jpm, "api"), root, Map.of(), "jpm-format", Main.VERSION)) {
            dev.jpm.api.model.Project p = client.load(root, LoadOptions.defaults());
            return fromModel(root, p, client.classpaths(root, List.of()));
        } catch (JpmNotInstalled e) {
            return untyped(root, System.getenv("JPM_EXE") == null ? "jpm is not on PATH" : "could not run " + jpm);
        } catch (ProtocolMismatch e) {
            return untyped(root, e.getMessage());
        } catch (JpmError e) {
            return untyped(root, "jpm could not load the project: " + e.getMessage());
        }
    }

    /**
     * jpm's warnings and errors about the project at {@code dir}: {@code jpm check} with only {@code checks}, over jpm's
     * protocol ({@code check/run}). A problem jpm stops at, such as a manifest that does not parse, is the only one.
     *
     * @throws IllegalStateException when jpm cannot be run or does not answer
     */
    static List<Problem> check(Path dir, List<String> checks) {
        String jpm = System.getenv("JPM_EXE");
        if (jpm == null || jpm.isBlank()) jpm = "jpm";
        try (JpmClient client = JpmClient.start(List.of(jpm, "api"), dir, Map.of(), "jpm-format", Main.VERSION)) {
            return client.check(dir, checks, false).problems();
        } catch (JpmNotInstalled e) {
            throw new IllegalStateException("could not run " + jpm, e);
        } catch (JpmError e) {
            if (e.problem() != null) return List.of(e.problem());
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * The modules jpm reports, each with its source folders and the classpath its main and test sources compile against.
     */
    static Project fromModel(Path root, dev.jpm.api.model.Project p, Map<String, Classpaths> classpaths) {
        List<Module> mods = new ArrayList<>();
        for (dev.jpm.api.model.Module m : p.modules()) {
            Classpaths c = classpaths.get(m.name());
            if (c == null) continue;
            List<Path> srcs = new ArrayList<>();
            srcs.addAll(m.folders().sources());
            srcs.addAll(m.folders().tests());
            srcs.addAll(m.folders().generatedSources());
            List<Path> cp = new ArrayList<>();
            for (dev.jpm.api.model.Package pkg : c.testCompile()) {
                if (pkg.classes() != null) cp.add(pkg.classes());
                cp.addAll(pkg.jars());
            }
            mods.add(new Module(m.dir(), srcs, cp));
        }
        if (mods.isEmpty()) return untyped(root, "jpm reported no modules");
        return new Project(root, mods, null);
    }

    /**
     * The module a file belongs to, or null.
     */
    public Module moduleOf(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        for (Module m : modules) if (abs.startsWith(m.dir())) return m;
        return null;
    }

    /**
     * True if type information can be computed for files of this project.
     */
    public boolean typed() {
        return !modules.isEmpty();
    }

    /**
     * A hash of every classpath, for the cache.
     */
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

    private static final ThreadLocal<JavaFileManager> FILE_MANAGERS = ThreadLocal.withInitial(
            () -> new InMemoryAware(Source.JAVAC.getStandardFileManager(null, null, StandardCharsets.UTF_8)));

    /**
     * javac's file manager only knows its own file objects: in module mode it asks which source folder holds
     * each file, and throws for the in-memory ones we attribute (their text may not be on disk yet). This
     * answers from the file's path instead.
     */
    private static final class InMemoryAware extends ForwardingJavaFileManager<StandardJavaFileManager> {
        InMemoryAware(StandardJavaFileManager fm) {
            super(fm);
        }

        @Override
        public boolean contains(Location location, FileObject fo) throws IOException {
            if (!(fo instanceof Source.InMemory)) return super.contains(location, fo);
            Path p = Path.of(fo.toUri());
            for (Path dir : fileManager.getLocationAsPaths(location)) if (p.startsWith(dir)) return true;
            return false;
        }

        @Override
        public boolean isSameFile(FileObject a, FileObject b) {
            if (a instanceof Source.InMemory || b instanceof Source.InMemory)
                return a.toUri().equals(b.toUri());
            return super.isSameFile(a, b);
        }
    }

    /**
     * Attributes one file's text against its module's classpath; null if the file has no module.
     */
    public TypeInfo attribute(Path file, String text) {
        Path abs = file.toAbsolutePath().normalize();
        return attributeAll(Map.of(abs, text)).get(abs);
    }

    /**
     * What decides how a file is attributed (its module's classpath and source folders), or null if it has no
     * module: files with the same key can be attributed together, by {@link #attributeAll}.
     */
    public String batchKey(Path file) {
        List<String> opts = options(file);
        return opts == null ? null : String.join("\0", opts);
    }

    /**
     * Attributes several files in one javac task, by absolute path; they must have the same {@link #batchKey}.
     * javac then reads the classpath and enters the sources the files share once for all of them, not once per
     * file. A file javac could not attribute is missing from the result; if javac fails, the result is empty.
     */
    public Map<Path, TypeInfo> attributeAll(Map<Path, String> texts) {
        if (texts.isEmpty()) return Map.of();
        List<String> opts = options(texts.keySet().iterator().next());
        if (opts == null) return Map.of();
        List<JavaFileObject> units = new ArrayList<>();
        for (Map.Entry<Path, String> e : texts.entrySet())
            units.add(Source.fileObject(e.getKey().toAbsolutePath().normalize(), e.getValue()));
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        JavacTask task = (JavacTask)
                Source.JAVAC.getTask(Source.quiet(), FILE_MANAGERS.get(), diags, opts, null, units);
        try {
            List<CompilationUnitTree> cus = new ArrayList<>();
            for (CompilationUnitTree u : task.parse()) cus.add(u);
            task.analyze();
            Trees trees = Trees.instance(task);
            Map<Path, TypeInfo> out = new HashMap<>();
            for (CompilationUnitTree cu : cus) {
                out.put(
                        Path.of(cu.getSourceFile().toUri()),
                        new TypeInfo(cu, trees, task.getTypes(), task.getElements()));
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * javac's options for attributing a file, or null if the file has no module.
     */
    private List<String> options(Path file) {
        Module m = moduleOf(file);
        if (m == null) return null;
        List<String> opts = new ArrayList<>(List.of(
                "-proc:none",
                "-Xlint:none",
                "-implicit:none",
                "-XDshould-stop.ifError=ATTR",
                "-XDshould-stop.ifNoError=ATTR"));
        Path abs = file.toAbsolutePath().normalize();
        List<Path> srcs = m.sourceDirs().stream().filter(Files::isDirectory).toList();
        Path modular = srcs.stream()
                .filter(d -> abs.startsWith(d) && Files.isRegularFile(d.resolve("module-info.java")))
                .findFirst()
                .orElse(null);
        if (modular != null) {
            opts.add("--module-path");
            opts.add(join(m.classpath()));
            opts.add("-sourcepath");
            opts.add(modular.toString());
        } else {
            opts.add("-classpath");
            opts.add(join(m.classpath()));
            List<Path> plain = srcs.stream()
                    .filter(d -> !Files.isRegularFile(d.resolve("module-info.java")))
                    .toList();
            if (!plain.isEmpty()) {
                opts.add("-sourcepath");
                opts.add(join(plain));
            }
        }
        return opts;
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
