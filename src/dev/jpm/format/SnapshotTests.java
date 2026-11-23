package dev.jpm.format;

import dev.jpm.format.api.Finding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code jpm format test}: the test kit for rule authors. Each {@code Name.java} in {@code rules/test/} is an
 * input; {@code Name.expected.java} is what {@code jpm format} must turn it into, and {@code Name.findings} what
 * {@code jpm format --check} must report for it (one {@link Finding#brief()} per line). Missing snapshots are
 * written on the first run; {@code --update} rewrites them all. Commit them, and formatting cannot change by
 * accident.
 */
final class SnapshotTests {
    private SnapshotTests() {
    }

    static int run(List<String> args) {
        boolean update = false;
        Path dir = null;
        for (String a : args) {
            if (a.equals("--update")) update = true;
            else if (a.startsWith("-")) throw new FormatException("Unknown option " + a + " for `jpm format test`");
            else dir = Path.of(a);
        }
        Path root = Project.findRoot(Path.of(""));
        return run(Workspace.open(root, true, Main::warn), dir, update);
    }

    static int run(Workspace ws, Path dir, boolean update) {
        Path root = ws.root;
        if (dir == null) dir = root.resolve("rules").resolve("test");
        dir = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            System.err.println("No rule tests: " + dir + " does not exist");
            return 0;
        }
        List<Path> inputs;
        try (Stream<Path> s = Files.walk(dir)) {
            inputs = s.filter(
                    p -> p.toString().endsWith(".java") && !p.toString().endsWith(".expected.java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new FormatException("Could not read " + dir + ": " + e.getMessage());
        }
        int failed = 0, written = 0;
        for (Path in : inputs) {
            String rel = Config.rel(root, in);
            String text = Workspace.read(in);
            String base = in.getFileName().toString().replaceFirst("\\.java$", "");
            Path expected = in.resolveSibling(base + ".expected.java");
            Path findings = in.resolveSibling(base + ".findings");

            String formatted = ws.engine.run(rel, text, Engine.Mode.FORMAT).output();
            List<String> got = new ArrayList<>();
            for (Finding f : ws.engine.run(rel, text, Engine.Mode.CHECK).findings()) got.add(f.brief());
            String gotFindings = got.isEmpty() ? "" : String.join("\n", got) + "\n";

            List<String> problems = new ArrayList<>();
            written += snapshot(expected, formatted, update, "formatted output", problems);
            written += snapshot(findings, gotFindings, update, "findings", problems);
            if (problems.isEmpty()) {
                System.err.println("  ok    " + rel);
            } else {
                failed++;
                System.err.println("  FAIL  " + rel);
                for (String p : problems) System.err.println(p.indent(8).stripTrailing());
            }
        }
        System.err.println(inputs.size() + " rule test" + (inputs.size() == 1 ? "" : "s") + ", " + failed + " failed"
                + (written > 0 ? ", " + written + " snapshot" + (written == 1 ? "" : "s") + " written" : ""));
        return failed > 0 ? 1 : 0;
    }

    private static int snapshot(Path file, String actual, boolean update, String what, List<String> problems) {
        try {
            if (Files.exists(file) && !update) {
                String want = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
                if (!want.equals(actual)) {
                    problems.add(what + " differs from " + file.getFileName() + ":\n" + diff(want, actual)
                            + "(run `jpm format test --update` to accept)");
                }
                return 0;
            }
            if (Files.exists(file)
                    && Files.readString(file, StandardCharsets.UTF_8).equals(actual)) return 0;
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return 1;
        } catch (IOException e) {
            throw new FormatException("Could not access " + file + ": " + e.getMessage());
        }
    }

    private static String diff(String want, String got) {
        String[] a = want.split("\n", -1), b = got.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int n = Math.max(a.length, b.length), shown = 0;
        for (int k = 0; k < n && shown < 20; k++) {
            String x = k < a.length ? a[k] : null, y = k < b.length ? b[k] : null;
            if (x != null && x.equals(y)) continue;
            if (x != null) sb.append(String.format("%4d - %s%n", k + 1, x));
            if (y != null) sb.append(String.format("%4d + %s%n", k + 1, y));
            shown++;
        }
        return sb.toString();
    }
}
