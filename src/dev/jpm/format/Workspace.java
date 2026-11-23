package dev.jpm.format;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Everything a run needs for one project: its rules, config and classpath, and the engine.
 */
public final class Workspace {
    public final Path root;
    public final Project project;
    public final Registry registry;
    public final Config config;
    public final Engine engine;
    final String cacheKey;

    private Workspace(Path root, Project project, Registry registry, Config config, Engine engine) {
        this.root = root;
        this.project = project;
        this.registry = registry;
        this.config = config;
        this.engine = engine;
        this.cacheKey = Hashing.sha256(
                Main.VERSION + "\n" + config.hash + "\n" + (project.typed() ? project.classpathKey() : "untyped"));
    }

    public static Workspace open(Path root, boolean debug, Consumer<String> warn) {
        Project project = Project.load(root);
        return open(root, project, debug, warn);
    }

    static Workspace open(Path root, Project project, boolean debug, Consumer<String> warn) {
        Registry registry = Registry.builtins();
        String custom = CustomRules.load(root, project, registry);
        Config config = Config.load(root, registry.rules, custom);
        return new Workspace(root, project, registry, config, new Engine(project, config, registry, debug, warn));
    }

    /**
     * The Java files to process: those under {@code paths} (default: the root) that are not ignored.
     */
    public List<Path> files(List<Path> paths) {
        TreeSet<Path> out = new TreeSet<>();
        List<Path> starts = paths.isEmpty() ? List.of(root) : paths;
        for (Path start : starts) {
            Path abs = start.toAbsolutePath().normalize();
            if (!Files.exists(abs)) throw new FormatException(start + " does not exist");
            if (!abs.startsWith(root)) throw new FormatException(start + " is outside the project at " + root);
            if (Files.isRegularFile(abs)) {
                if (!config.ignored(Config.rel(root, abs))) out.add(abs);
                continue;
            }
            try {
                Files.walkFileTree(abs, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(root) && config.ignored(Config.rel(root, dir) + "/x"))
                            return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".java")
                                && attrs.isRegularFile()
                                && !config.ignored(Config.rel(root, file))) {
                            out.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new FormatException("Could not list " + abs + ": " + e.getMessage());
            }
        }
        return new ArrayList<>(out);
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new FormatException(file + " is not UTF-8");
        } catch (IOException e) {
            throw new FormatException("Could not read " + file + ": " + e.getMessage());
        }
    }
}
