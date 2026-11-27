package dev.jpm.format;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The files git ignores, by git's rules: the {@code .gitignore} of every folder from the repository's root (the
 * nearest folder up from the project with a {@code .git}, else the project) down to the file, and
 * {@code .git/info/exclude}. A pattern without a {@code /} but at its end matches at any depth, one with a
 * {@code /} matches from its {@code .gitignore}'s folder; a trailing {@code /} matches folders only, {@code !}
 * includes again what an earlier pattern ignored, a later pattern wins over an earlier one and a deeper
 * {@code .gitignore} over a higher one, and nothing in an ignored folder is included again.
 */
final class GitIgnore {
    private record Rule(Pattern pattern, boolean negated, boolean dirOnly) {
    }

    private final Path top;
    private final Path root;
    private final Map<Path, List<Rule>> rules = new ConcurrentHashMap<>();
    private final Map<Path, Boolean> dirs = new ConcurrentHashMap<>();

    GitIgnore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        Path t = this.root;
        while (t != null && !Files.exists(t.resolve(".git"))) t = t.getParent();
        this.top = t == null ? this.root : t;
    }

    /**
     * True if git ignores the file or folder (relative to the project root).
     */
    boolean ignored(String rel, boolean dir) {
        Path abs = root.resolve(rel).normalize();
        if (!abs.startsWith(top)) return false;
        Path parent = abs.getParent();
        if (parent != null && !parent.equals(top) && parent.startsWith(top) && ignoredDir(parent)) return true;
        return matches(abs, dir);
    }

    private boolean ignoredDir(Path dir) {
        Boolean known = dirs.get(dir);
        if (known != null) return known;
        Path parent = dir.getParent();
        boolean ignored = parent != null && !parent.equals(top) && parent.startsWith(top) && ignoredDir(parent)
                || matches(dir, true);
        dirs.put(dir, ignored);
        return ignored;
    }

    private boolean matches(Path abs, boolean dir) {
        Boolean result = null;
        List<Path> chain = new ArrayList<>();
        for (Path d = abs.getParent(); d != null && d.startsWith(top); d = d.getParent()) chain.addFirst(d);
        for (Path d : chain) {
            String rel = d.relativize(abs).toString().replace('\\', '/');
            for (Rule r : rulesOf(d)) {
                if (r.dirOnly && !dir) continue;
                if (r.pattern.matcher(rel).matches()) result = !r.negated;
            }
        }
        return Boolean.TRUE.equals(result);
    }

    private List<Rule> rulesOf(Path dir) {
        return rules.computeIfAbsent(dir, d -> {
            List<Rule> out = new ArrayList<>();
            if (d.equals(top)) read(d.resolve(".git").resolve("info").resolve("exclude"), out);
            read(d.resolve(".gitignore"), out);
            return out;
        });
    }

    private static void read(Path file, List<Rule> out) {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Rule r = parse(line);
                if (r != null) out.add(r);
            }
        } catch (IOException e) {
            throw new FormatException("Could not read " + file + ": " + e.getMessage());
        }
    }

    static Rule parse(String line) {
        String l = line;
        if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
        int end = l.length();
        while (end > 0 && l.charAt(end - 1) == ' ' && (end < 2 || l.charAt(end - 2) != '\\')) end--;
        l = l.substring(0, end);
        if (l.isEmpty() || l.startsWith("#")) return null;
        boolean negated = l.startsWith("!");
        if (negated) l = l.substring(1);
        else if (l.startsWith("\\!") || l.startsWith("\\#")) l = l.substring(1);
        boolean dirOnly = l.endsWith("/");
        if (dirOnly) l = l.substring(0, l.length() - 1);
        if (l.isEmpty()) return null;
        boolean anchored = l.indexOf('/') >= 0;
        if (l.startsWith("/")) l = l.substring(1);
        String regex = glob(l);
        if (!anchored) regex = "(?:.*/)?" + regex;
        return new Rule(Pattern.compile(regex), negated, dirOnly);
    }

    private static String glob(String g) {
        StringBuilder sb = new StringBuilder();
        int n = g.length();
        for (int i = 0; i < n; i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < n && g.charAt(i + 1) == '*'
                    && (i == 0 || g.charAt(i - 1) == '/') && (i + 2 == n || g.charAt(i + 2) == '/')) {
                if (i + 2 == n) {
                    sb.append(".*");
                } else {
                    sb.append("(?:.*/)?");
                    i++;
                }
                i++;
            } else if (c == '*') {
                sb.append("[^/]*");
            } else if (c == '?') {
                sb.append("[^/]");
            } else if (c == '[') {
                int close = g.indexOf(']', i + 2);
                if (close < 0) {
                    sb.append("\\[");
                    continue;
                }
                String set = g.substring(i + 1, close);
                if (set.startsWith("!")) set = "^" + set.substring(1);
                sb.append('[').append(set.replace("\\", "\\\\").replace("[", "\\[")).append(']');
                i = close;
            } else if (c == '\\' && i + 1 < n) {
                sb.append(Pattern.quote(String.valueOf(g.charAt(++i))));
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }
}
