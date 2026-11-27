package dev.jpm.format.rules;

import com.sun.source.tree.ImportTree;
import dev.jpm.format.Comments;
import dev.jpm.format.Config;
import dev.jpm.format.FileContext;
import dev.jpm.format.Source;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Formatter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sorts imports into groups, IntelliJ's layout by default: all other imports, then {@code java.*} and
 * {@code javax.*}, then static imports; each group sorted alphabetically and separated from the next by a
 * blank line. With {@code imports.wildcardThreshold} set, that many imports from one package become a
 * wildcard import. Imports with comments between them are left alone, so no comment is lost.
 */
public final class ImportOrder implements Formatter {
    record Imp(boolean isStatic, String name) {
        String line() {
            return "import " + (isStatic ? "static " : "") + name + ";";
        }

        String owner() {
            int d = name.lastIndexOf('.');
            return d < 0 ? "" : name.substring(0, d);
        }
    }

    /**
     * Alphabetical, with {@code .} before any letter so {@code a.b.C} comes before {@code a.bc}.
     */
    static final Comparator<String> ORDER = Comparator.comparing(s -> s.replace('.', '\0'));

    @Override
    public String id() {
        return "ImportOrder";
    }

    @Override
    public String category() {
        return "format";
    }

    @Override
    public String docs() {
        return Builtins.docs(id());
    }

    @Override
    public String format(Context ctx) {
        List<? extends ImportTree> imports = ctx.tree().getImports();
        if (imports.isEmpty()) return ctx.source();
        int start = ctx.start(imports.get(0));
        int end = ctx.end(imports.get(imports.size() - 1));
        for (Comments.Comment c : ((FileContext) ctx).parsed().comments()) {
            if (c.start() >= start && c.end() <= end) return ctx.source();
        }
        Config.Settings s = ((FileContext) ctx).settings();
        Set<Imp> all = new LinkedHashSet<>();
        for (ImportTree t : imports)
            all.add(new Imp(t.isStatic(), t.getQualifiedIdentifier().toString()));
        List<Imp> list = wildcards(new ArrayList<>(all), s.wildcardThreshold());
        String block = layout(list, s.importGroups(), Source.newline(ctx.source()));
        return ctx.source().substring(0, start) + block + ctx.source().substring(end);
    }

    static List<Imp> wildcards(List<Imp> imps, int threshold) {
        if (threshold <= 0) return imps;
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Imp i : imps) if (!i.name().endsWith(".*")) count.merge(i.isStatic() + ":" + i.owner(), 1, Integer::sum);
        Set<Imp> out = new LinkedHashSet<>();
        for (Imp i : imps) {
            if (!i.name().endsWith(".*") && count.get(i.isStatic() + ":" + i.owner()) >= threshold) {
                out.add(new Imp(i.isStatic(), i.owner() + ".*"));
            } else out.add(i);
        }
        return new ArrayList<>(out);
    }

    static String layout(List<Imp> imps, List<List<String>> groups, String nl) {
        List<List<Imp>> buckets = new ArrayList<>();
        for (int k = 0; k < groups.size(); k++) buckets.add(new ArrayList<>());
        for (Imp i : imps) buckets.get(group(i, groups)).add(i);
        List<String> parts = new ArrayList<>();
        for (List<Imp> b : buckets) {
            if (b.isEmpty()) continue;
            b.sort(Comparator.comparing(Imp::name, ORDER));
            StringBuilder sb = new StringBuilder();
            for (Imp i : b) {
                if (!sb.isEmpty()) sb.append(nl);
                sb.append(i.line());
            }
            parts.add(sb.toString());
        }
        return String.join(nl + nl, parts);
    }

    /**
     * The group (an index into {@code groups}) of an import.
     */
    public static int group(boolean isStatic, String name, List<List<String>> groups) {
        return group(new Imp(isStatic, name), groups);
    }

    /**
     * The group of the most specific matching pattern; the last group if none matches.
     */
    static int group(Imp i, List<List<String>> groups) {
        int best = -1, bestLen = -1;
        for (int g = 0; g < groups.size(); g++) {
            for (String pat : groups.get(g)) {
                boolean st = pat.startsWith("static ");
                String body = st ? pat.substring(7).strip() : pat.strip();
                if (st != i.isStatic()) continue;
                int len;
                if (body.equals("*")) len = 0;
                else if (body.endsWith(".*") && i.name().startsWith(body.substring(0, body.length() - 1)))
                    len = body.length();
                else if (i.name().equals(body)) len = body.length() + 1;
                else continue;
                if (len > bestLen) {
                    best = g;
                    bestLen = len;
                }
            }
        }
        return best < 0 ? groups.size() - 1 : best;
    }
}
