import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * How far jpm-format's output is from IntelliJ IDEA's, on a corpus of real projects: the sources jars in
 * checks/parity/corpus.txt are formatted by IntelliJ's command-line formatter with its default code style (the
 * reference, cached per IntelliJ build) and by jpm-format with only its formatter, and the lines that differ are
 * counted. The count may only go down: it is stored in checks/parity/baseline.txt.
 *
 * <pre>
 * java checks/Parity.java --idea &lt;IntelliJ home&gt; [--jpm-format &lt;command&gt;] [--baseline &lt;file&gt;] [--update] [--show N]
 * </pre>
 * <p>
 * IntelliJ's home is the folder with bin/format.sh (or IDEA_HOME). jpm-format runs from dist/*-bundle.jar
 * (`jpm build --bundle`) in the corpus folder, unless --jpm-format names another command. --baseline names another
 * baseline file than checks/parity/baseline.txt. --update writes a lower count to the baseline; --show
 * prints the N files that differ most, with their first differences.
 */
public final class Parity {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path CACHE = ROOT.resolve(".cache/parity");
    private static final Path DEFAULT_BASELINE = ROOT.resolve("checks/parity/baseline.txt");
    private static final String CONFIG = """
            {
              "name": "parity-corpus",
              "version": "0.0.0",
              "extensions": {
                "format": {
                  "formatter": { "style": "intellij" },
                  "imports": { "removeUnused": false, "sort": false },
                  "linter": { "enabled": false }
                }
              }
            }
            """;

    public static void main(String[] args) throws Exception {
        String idea = System.getenv("IDEA_HOME");
        List<String> jpmFormat = null;
        boolean update = false;
        int show = 0;
        Path baselineFile = DEFAULT_BASELINE;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--idea" -> idea = args[++i];
                case "--jpm-format" -> jpmFormat = List.of(args[++i].split(" "));
                case "--update" -> update = true;
                case "--show" -> show = Integer.parseInt(args[++i]);
                case "--baseline" -> baselineFile = ROOT.resolve(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (idea == null) throw new IllegalArgumentException("--idea <IntelliJ home> (or IDEA_HOME) is required");
        Path ideaHome = Path.of(idea);
        String build = Files.readString(ideaHome.resolve("build.txt")).strip();

        Path sources = CACHE.resolve("sources");
        List<String> corpus = Files.readAllLines(ROOT.resolve("checks/parity/corpus.txt")).stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        for (String line : corpus) unpack(line, sources);

        Path reference = CACHE.resolve("reference-" + build + "-" + hash("utf-8 only\n" + String.join("\n", corpus)));
        if (!Files.isDirectory(reference)) {
            Path tmp = CACHE.resolve("reference.tmp");
            copy(sources, tmp);
            run(List.of(ideaHome.resolve("bin/format.sh").toString(), "-allowDefaults", "-r", tmp.toString()), ROOT,
                    Map.of("IDEA_PROPERTIES", isolatedIdea().toString()));
            Files.move(tmp, reference);
        }

        Path ours = Path.of(System.getProperty("java.io.tmpdir"), "jpm-format-parity");
        deleteTree(ours);
        copy(sources, ours);
        Files.writeString(ours.resolve("project.jsonc"), CONFIG);
        if (jpmFormat == null) {
            java.util.regex.Matcher v = java.util.regex.Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(Files.readString(ROOT.resolve("project.jsonc")));
            if (!v.find()) throw new IOException("no \"version\" in project.jsonc");
            Path bundle = ROOT.resolve("dist/jpm-format-" + v.group(1) + "-bundle.jar");
            if (!Files.isRegularFile(bundle)) throw new IOException("no " + ROOT.relativize(bundle) + ": run `jpm build --bundle` first");
            jpmFormat = List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", bundle.toString());
        }
        List<String> cmd = new ArrayList<>(jpmFormat);
        cmd.add("--no-cache");
        cmd.add(".");
        run(cmd, ours);

        Map<String, Integer> diffs = new TreeMap<>();
        Map<String, List<String>> samples = new TreeMap<>();
        int total = 0, files = 0;
        try (Stream<Path> s = Files.walk(reference)) {
            for (Path ref :
                    s.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String rel = reference.relativize(ref).toString();
                List<String> a = Files.readAllLines(ref), b = Files.readAllLines(ours.resolve(rel));
                List<String> sample = new ArrayList<>();
                int d = diff(a, b, sample);
                files++;
                if (d > 0) {
                    diffs.put(rel, d);
                    samples.put(rel, sample);
                    total += d;
                }
            }
        }
        System.out.printf(
                "%d of %d files differ from IntelliJ %s; %d lines in all%n", diffs.size(), files, build, total);
        if (show > 0) {
            diffs.entrySet().stream()
                    .sorted((x, y) -> y.getValue() - x.getValue())
                    .limit(show)
                    .forEach(e -> {
                        System.out.println("\n" + e.getKey() + ": " + e.getValue() + " lines");
                        samples.get(e.getKey()).stream().limit(12).forEach(l -> System.out.println("  " + l));
                    });
        }
        int baseline = Files.exists(baselineFile)
                ? Integer.parseInt(Files.readString(baselineFile).strip())
                : Integer.MAX_VALUE;
        if (update && total < baseline) {
            Files.createDirectories(baselineFile.getParent());
            Files.writeString(baselineFile, total + "\n");
            System.out.println("baseline: " + total);
        } else if (total > baseline) {
            System.out.println("more than the baseline of " + baseline + ": jpm-format moved away from IntelliJ");
            System.exit(1);
        } else if (total < baseline) {
            System.out.println("below the baseline of " + baseline + ": run with --update to lower it");
        }
    }

    private static void unpack(String coordinates, Path into) throws Exception {
        String[] parts = coordinates.split("\\s+");
        String[] gav = parts[0].split(":");
        Path marker = into.resolve(gav[1] + "-" + gav[2] + ".done");
        if (Files.exists(marker)) return;
        String url = "https://repo.maven.apache.org/maven2/" + gav[0].replace('.', '/') + "/" + gav[1] + "/" + gav[2]
                + "/" + gav[1] + "-" + gav[2] + "-sources.jar";
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpResponse<byte[]> r =
                http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException(url + " answered HTTP " + r.statusCode());
        String sha =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(r.body()));
        if (parts.length > 1 && !parts[1].equals(sha))
            throw new IOException(url + " has SHA-256 " + sha + ", not " + parts[1]);
        Path dir = into.resolve(gav[1] + "-" + gav[2]);
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(r.body()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".java") || name.endsWith("module-info.java")) continue;
                Path out = dir.resolve(name).normalize();
                if (!out.startsWith(dir)) continue;
                byte[] bytes = zip.readAllBytes();
                try {
                    StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
                } catch (java.nio.charset.CharacterCodingException notUtf8) {
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.write(out, bytes);
            }
        }
        Files.writeString(marker, sha + "\n");
    }

    /**
     * An idea.properties that gives IntelliJ config, system, plugins and log folders of its own, so format.sh
     * formats with IntelliJ's defaults, not with the IDE settings (and default code style) of whoever runs it.
     */
    private static Path isolatedIdea() throws IOException {
        Path home = CACHE.resolve("idea-home");
        deleteTree(home);
        Files.createDirectories(home);
        StringBuilder props = new StringBuilder();
        for (String k : List.of("config", "system", "plugins", "log")) {
            props.append("idea.").append(k).append(".path=")
                    .append(home.resolve(k).toAbsolutePath().toString().replace('\\', '/')).append('\n');
        }
        Path file = home.resolve("idea.properties");
        Files.writeString(file, props.toString());
        return file;
    }

    private static void run(List<String> cmd, Path dir) throws Exception {
        run(cmd, dir, Map.of());
    }

    private static void run(List<String> cmd, Path dir, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        if (p.waitFor() != 0) {
            System.out.write(out);
            throw new IOException(String.join(" ", cmd) + " failed");
        }
    }

    private static int diff(List<String> a, List<String> b, List<String> sample) {
        int n = a.size(), m = b.size(), max = n + m;
        int[] v = new int[2 * max + 2];
        List<int[]> trace = new ArrayList<>();
        int d;
        found:
        for (d = 0; d <= max; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || (k != d && v[max + k - 1] < v[max + k + 1]) ? v[max + k + 1] : v[max + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[max + k] = x;
                if (x >= n && y >= m) break found;
            }
        }
        int x = n, y = m;
        List<String> rev = new ArrayList<>();
        for (int e = d; e > 0; e--) {
            int[] pv = trace.get(e);
            int k = x - y;
            int prevK = k == -e || (k != e && pv[max + k - 1] < pv[max + k + 1]) ? k + 1 : k - 1;
            int px = pv[max + prevK], py = px - prevK;
            while (x > px && y > py) {
                x--;
                y--;
            }
            if (x == px) rev.add("+ " + (y > 0 ? y : 1) + ": " + b.get(y - 1));
            else rev.add("- " + x + ": " + a.get(x - 1));
            x = px;
            y = py;
        }
        for (int i = rev.size() - 1; i >= 0; i--) sample.add(rev.get(i));
        return d;
    }

    private static String hash(String s) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 12);
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                Files.createDirectories(t.getParent());
                Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }
}
