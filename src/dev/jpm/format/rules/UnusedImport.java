package dev.jpm.format.rules;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import dev.jpm.format.Comments;
import dev.jpm.format.FileContext;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.Formatter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes unused, redundant ({@code java.lang} and same-package) and duplicate imports. A name used only in
 * Javadoc ({@code {@link Foo}}, {@code @see}, {@code @throws}) counts as used. Wildcard imports are only
 * removed when redundant or duplicate, since without types it cannot be known what they bring in.
 */
public final class UnusedImport implements Formatter {
    private static final Pattern JAVADOC_REF = Pattern.compile(
            "\\{@(?:link|linkplain|value)\\s+([A-Za-z_$][\\w$]*)|@(?:see|throws|exception)\\s+([A-Za-z_$][\\w$]*)");

    record Problem(ImportTree imp, String message) {}

    @Override
    public String id() {
        return "UnusedImport";
    }

    @Override
    public String category() {
        return "style";
    }

    @Override
    public String docs() {
        return Builtins.docs(id());
    }

    @Override
    public void check(Context ctx) {
        for (Problem p : problems(ctx)) {
            int[] r = range(ctx, p.imp());
            ctx.report(ctx.start(p.imp()), ctx.end(p.imp()), p.message(), Fix.delete("Remove the import", r[0], r[1]));
        }
    }

    @Override
    public String format(Context ctx) {
        List<Fix.Edit> edits = new ArrayList<>();
        for (Problem p : problems(ctx)) {
            int[] r = range(ctx, p.imp());
            edits.add(new Fix.Edit(r[0], r[1], ""));
        }
        return edits.isEmpty() ? ctx.source() : Fix.apply(ctx.source(), edits);
    }

    private static int[] range(Context ctx, ImportTree imp) {
        return Trees2.wholeLines(ctx.source(), ctx.start(imp), ctx.end(imp));
    }

    static List<Problem> problems(Context ctx) {
        CompilationUnitTree cu = ctx.tree();
        List<Problem> out = new ArrayList<>();
        if (cu.getImports().isEmpty()) return out;
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        Set<String> used = Trees2.names(cu);
        for (Comments.Comment c : ((FileContext) ctx).parsed().comments()) {
            if (!c.text().startsWith("/**")) continue;
            Matcher m = JAVADOC_REF.matcher(c.text());
            while (m.find()) used.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        Set<String> seen = new HashSet<>();
        for (ImportTree imp : cu.getImports()) {
            String name = imp.getQualifiedIdentifier().toString();
            String shown = (imp.isStatic() ? "static " : "") + name;
            if (!seen.add(shown)) {
                out.add(new Problem(imp, "Duplicate import '" + shown + "'"));
                continue;
            }
            int dot = name.lastIndexOf('.');
            if (dot < 0) continue;
            String owner = name.substring(0, dot), simple = name.substring(dot + 1);
            if (!imp.isStatic() && (owner.equals("java.lang") || owner.equals(pkg))) {
                out.add(new Problem(
                        imp,
                        "Redundant import '" + name + "': "
                                + (owner.equals(pkg) ? "same package" : "java.lang is imported implicitly")));
            } else if (!simple.equals("*") && !used.contains(simple)) {
                out.add(new Problem(imp, "Unused import '" + shown + "'"));
            }
        }
        return out;
    }
}
