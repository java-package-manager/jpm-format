package dev.jpm.format;

import dev.jpm.format.api.Finding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Results of earlier runs, in {@code .cache/format/results.json}, so unchanged files are skipped. An entry is
 * keyed by the file's content; the whole cache by the tool version, the config (with custom rules) and, when
 * type-aware rules run, the classpath. Any change to those starts afresh.
 */
final class Cache {
    /** A file's findings for a content hash; {@code formatted} if formatting would not change it. */
    record Entry(String hash, boolean formatted, List<Finding> findings) {}

    private final Path file;
    private final String key;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    private Cache(Path file, String key) {
        this.file = file;
        this.key = key;
    }

    static Cache open(Path root, String key) {
        Cache c = new Cache(root.resolve(".cache").resolve("format").resolve("results.json"), key);
        c.load();
        return c;
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Map<String, Object> m = Json.object(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            if (!key.equals(m.get("key"))) return;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.getOrDefault("files", Map.of())).entrySet()) {
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                List<Finding> fs = new ArrayList<>();
                for (Object o : (List<Object>) v.get("findings")) fs.add(Reporter.fromJson((Map<String, Object>) o));
                entries.put(e.getKey(), new Entry((String) v.get("hash"), (Boolean) v.get("formatted"), fs));
            }
        } catch (IOException | RuntimeException e) {
            entries.clear(); // a broken cache is just a cold one
        }
    }

    static String hash(String text) {
        return Hashing.sha256(text);
    }

    /** The cached entry for this content, or null. */
    Entry get(String rel, String text) {
        Entry e = entries.get(rel);
        return e != null && e.hash().equals(hash(text)) ? e : null;
    }

    void put(String rel, String text, boolean formatted, List<Finding> findings) {
        entries.put(rel, new Entry(hash(text), formatted, List.copyOf(findings)));
        dirty = true;
    }

    void save() {
        if (!dirty) return;
        Map<String, Object> files = new LinkedHashMap<>();
        new java.util.TreeMap<>(entries).forEach((rel, e) -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("hash", e.hash());
            v.put("formatted", e.formatted());
            List<Object> fs = new ArrayList<>();
            for (Finding f : e.findings()) fs.add(Reporter.json(f));
            v.put("findings", fs);
            files.put(rel, v);
        });
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("key", key);
        root.put("files", files);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "results", ".tmp");
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // caching is best effort
        }
    }
}
