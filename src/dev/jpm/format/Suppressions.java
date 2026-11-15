package dev.jpm.format;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.api.Finding;
import dev.jpm.format.api.Lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Suppressions in one file:
 *
 * <ul>
 *   <li>{@code // jpm-ignore lint/<category>/<rule>: <reason>} for the next line (and all of a statement or
 *       declaration that starts there); {@code jpm-ignore-all} for the
 *       whole file; {@code jpm-ignore-start} and {@code jpm-ignore-end} for a range. The target can also be
 *       {@code lint/<category>} or {@code lint}. A reason is required.
 *   <li>{@code @SuppressWarnings("RuleName")} on a class, method, field or variable, and {@code //noinspection
 *       RuleName} before a statement or declaration, as in IntelliJ. A rule's aliases (such as {@code unused})
 *       and {@code all} work too.
 * </ul>
 *
 * Unused {@code jpm-ignore} comments are reported. {@code @SuppressWarnings} and {@code //noinspection} are not,
 * because javac and IntelliJ read them too.
 */
final class Suppressions {
    static final String UNUSED = "UnusedSuppression";
    static final String INVALID = "InvalidSuppression";

    /** One suppression: what it targets and the offsets it covers. */
    private static final class Entry {
        final Comments.Comment comment; // null for annotations and noinspection
        final String target; // lint, lint/cat or lint/cat/Rule; or a bare name for annotations
        final int from, to;
        final boolean jpm;
        boolean used;

        Entry(Comments.Comment comment, String target, int from, int to, boolean jpm) {
            this.comment = comment;
            this.target = target;
            this.from = from;
            this.to = to;
            this.jpm = jpm;
        }
    }

    /** A problem with a suppression comment itself. */
    record Problem(int start, int end, String rule, String message) {}

    private final List<Entry> entries = new ArrayList<>();
    final List<Problem> problems = new ArrayList<>();

    private Suppressions() {}

    static Suppressions of(Source src, Registry registry) {
        Suppressions s = new Suppressions();
        s.comments(src, registry);
        if (src.errors().isEmpty()) s.annotations(src);
        return s;
    }

    private void comments(Source src, Registry registry) {
        List<Entry> open = new ArrayList<>();
        for (Comments.Comment c : src.comments()) {
            String b = c.body();
            if (b.startsWith("noinspection ") && c.line() && src.errors().isEmpty()) {
                int[] el = Elements.next(src, c.end());
                if (el == null) continue;
                for (String n : b.substring("noinspection ".length()).split(",")) {
                    if (!n.isBlank()) entries.add(new Entry(c, n.strip(), el[0], el[1], false));
                }
                continue;
            }
            if (!b.startsWith("jpm-ignore")) continue;
            String rest = b.substring("jpm-ignore".length());
            String kind = "";
            for (String k : new String[] {"-all", "-start", "-end"}) {
                if (rest.startsWith(k)) {
                    kind = k;
                    rest = rest.substring(k.length());
                }
            }
            if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) continue; // jpm-ignored, etc.
            rest = rest.strip();
            if (kind.isEmpty() && (rest.equals("format") || rest.startsWith("format:") || rest.startsWith("format ")))
                continue;
            int colon = rest.indexOf(':');
            String target = (colon < 0 ? rest : rest.substring(0, colon)).strip();
            String reason = colon < 0 ? "" : rest.substring(colon + 1).strip();
            String bad = checkTarget(target, registry);
            if (bad != null) {
                problems.add(new Problem(c.start(), c.end(), INVALID, bad));
                continue;
            }
            if (kind.equals("-end")) {
                Entry start = null;
                for (int k = open.size() - 1; k >= 0; k--) {
                    if (open.get(k).target.equals(target)) {
                        start = open.remove(k);
                        break;
                    }
                }
                if (start == null) {
                    problems.add(new Problem(
                            c.start(), c.end(), INVALID, "jpm-ignore-end without a jpm-ignore-start for " + target));
                } else {
                    entries.add(new Entry(start.comment, target, start.from, c.start(), true));
                }
                continue;
            }
            if (reason.isEmpty()) {
                problems.add(new Problem(
                        c.start(),
                        c.end(),
                        INVALID,
                        "Suppression needs a reason: // jpm-ignore" + kind + " " + target + ": <reason>"));
                continue;
            }
            switch (kind) {
                case "-all" -> entries.add(new Entry(c, target, 0, src.text.length(), true));
                case "-start" -> open.add(new Entry(c, target, c.end(), src.text.length(), true));
                default -> {
                    int line = src.line(c.end()) + 1;
                    while (line <= src.lineCount() && codeless(src, line)) line++;
                    int from = src.lineStart(line);
                    int to = Math.max(from, src.lineStart(line + 1) - 1);
                    // and all of the element that starts on that line, so reformatting cannot move code out of reach
                    if (src.errors().isEmpty()) {
                        int[] el = Elements.next(src, from);
                        if (el != null && src.line(el[0]) == line) to = Math.max(to, el[1]);
                    }
                    entries.add(new Entry(c, target, from, to, true));
                }
            }
        }
        for (Entry e : open) {
            problems.add(new Problem(
                    e.comment.start(),
                    e.comment.end(),
                    INVALID,
                    "jpm-ignore-start without a jpm-ignore-end for " + e.target));
        }
    }

    /** True if a line holds only whitespace and comments. */
    private static boolean codeless(Source src, int line) {
        int from = src.lineStart(line), to = src.lineStart(line + 1);
        int k = from;
        for (Comments.Comment c : src.comments()) {
            if (c.end() <= from || c.start() >= to) continue;
            if (!Comments.blank(src.text, k, Math.max(k, c.start()))) return false;
            k = Math.max(k, c.end());
        }
        return Comments.blank(src.text, Math.min(k, to), to);
    }

    private static String checkTarget(String target, Registry registry) {
        if (target.isEmpty()) return "Suppression needs a target such as lint/style/UnusedImport";
        String[] parts = target.split("/", -1);
        if (!parts[0].equals("lint") || parts.length > 3) {
            return "Unknown suppression target \"" + target + "\"; use lint, lint/<category> or lint/<category>/<rule>";
        }
        if (parts.length >= 2 && !registry.categories().contains(parts[1])) {
            return "Unknown category \"" + parts[1] + "\"; categories are " + registry.categories();
        }
        if (parts.length == 3) {
            Lint r = registry.rules.get(parts[2]);
            if (r == null) return "Unknown rule \"" + parts[2] + "\"";
            if (!r.category().equals(parts[1]))
                return parts[2] + " is in category " + r.category() + ", not " + parts[1];
        }
        return null;
    }

    private void annotations(Source src) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree t, Void v) {
                add(t, t.getModifiers());
                return super.visitClass(t, v);
            }

            @Override
            public Void visitMethod(MethodTree t, Void v) {
                add(t, t.getModifiers());
                return super.visitMethod(t, v);
            }

            @Override
            public Void visitVariable(VariableTree t, Void v) {
                add(t, t.getModifiers());
                return super.visitVariable(t, v);
            }

            private void add(Tree owner, ModifiersTree mods) {
                if (mods == null) return;
                for (AnnotationTree a : mods.getAnnotations()) {
                    String name = a.getAnnotationType().toString();
                    if (!name.equals("SuppressWarnings") && !name.equals("java.lang.SuppressWarnings")) continue;
                    for (ExpressionTree arg : a.getArguments()) {
                        ExpressionTree val = arg instanceof AssignmentTree as ? as.getExpression() : arg;
                        List<ExpressionTree> vals = val instanceof NewArrayTree na && na.getInitializers() != null
                                ? new ArrayList<>(na.getInitializers())
                                : List.of(val);
                        for (ExpressionTree x : vals) {
                            if (x instanceof LiteralTree lt && lt.getValue() instanceof String s) {
                                entries.add(new Entry(null, s, src.start(owner), src.end(owner), false));
                            }
                        }
                    }
                }
            }
        }.scan(src.tree(), null);
    }

    /** True if the finding is suppressed; marks the suppressions that matched as used. */
    boolean suppressed(Finding f, Lint rule) {
        boolean hit = false;
        for (Entry e : entries) {
            if (f.start() < e.from || f.start() > e.to) continue;
            if (matches(e, f, rule)) {
                e.used = true;
                hit = true;
            }
        }
        return hit;
    }

    private static boolean matches(Entry e, Finding f, Lint rule) {
        if (e.jpm) {
            return e.target.equals("lint")
                    || e.target.equals("lint/" + f.category())
                    || e.target.equals(f.qualifiedRule());
        }
        String t = e.target;
        if (t.equalsIgnoreCase("all")) return true;
        if (t.equals(f.rule())) return true;
        Set<String> aliases = rule == null ? Set.of() : rule.aliases();
        return aliases.contains(t);
    }

    /** Unused jpm-ignore comments whose target rules all ran (so "unused" is certain). */
    List<Problem> unused(Predicate<String> ran) {
        List<Problem> out = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.jpm || e.used || e.comment == null) continue;
            if (!ran.test(e.target)) continue;
            out.add(new Problem(
                    e.comment.start(),
                    e.comment.end(),
                    UNUSED,
                    "Unused suppression: nothing matches " + e.target + " here"));
        }
        return out;
    }
}
