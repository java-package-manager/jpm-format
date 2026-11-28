package dev.jpm.format.rules;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.Formatter;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Removes modifiers the language already implies, which never changes behaviour: {@code public} and
 * {@code abstract} on interface methods, {@code public static final} on interface fields, {@code public static}
 * on types inside interfaces, {@code static} on nested interfaces, enums and records, {@code final} on
 * records, and {@code private} on enum constructors.
 */
public final class UnnecessaryModifier implements Formatter {
    record Problem(int start, int end, String message) {
    }

    @Override
    public String id() {
        return "UnnecessaryModifier";
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
            ctx.report(
                    p.start(),
                    p.end(),
                    p.message(),
                    Fix.delete("Remove the modifier", p.start(), removalEnd(ctx.source(), p.end())));
        }
    }

    @Override
    public String format(Context ctx) {
        List<Fix.Edit> edits = new ArrayList<>();
        for (Problem p : problems(ctx)) edits.add(new Fix.Edit(p.start(), removalEnd(ctx.source(), p.end()), ""));
        return edits.isEmpty() ? ctx.source() : Fix.apply(ctx.source(), edits);
    }

    private static int removalEnd(String src, int end) {
        int e = end;
        while (e < src.length() && Character.isWhitespace(src.charAt(e))) e++;
        return e;
    }

    static List<Problem> problems(Context ctx) {
        List<Problem> out = new ArrayList<>();
        new TreeScanner<Void, Tree.Kind>() {
            @Override
            public Void visitClass(ClassTree t, Tree.Kind outer) {
                Tree.Kind kind = t.getKind();
                if (outer != null) {
                    boolean inInterface = outer == Tree.Kind.INTERFACE || outer == Tree.Kind.ANNOTATION_TYPE;
                    if (inInterface)
                        flag(
                                ctx,
                                t.getModifiers(),
                                Set.of(Modifier.PUBLIC, Modifier.STATIC),
                                "inside an interface",
                                out);
                    else if (kind != Tree.Kind.CLASS)
                        flag(ctx, t.getModifiers(), Set.of(Modifier.STATIC), "on a nested " + name(kind), out);
                }
                if (kind == Tree.Kind.RECORD) flag(ctx, t.getModifiers(), Set.of(Modifier.FINAL), "on a record", out);
                for (Tree m : t.getMembers()) {
                    if (kind == Tree.Kind.INTERFACE || kind == Tree.Kind.ANNOTATION_TYPE) {
                        if (m instanceof MethodTree mt) {
                            Set<Modifier> f = mt.getModifiers().getFlags();
                            boolean isAbstract = mt.getBody() == null
                                    && !f.contains(Modifier.DEFAULT)
                                    && !f.contains(Modifier.STATIC)
                                    && !f.contains(Modifier.PRIVATE);
                            flag(
                                    ctx,
                                    mt.getModifiers(),
                                    isAbstract ? Set.of(Modifier.PUBLIC, Modifier.ABSTRACT) : Set.of(Modifier.PUBLIC),
                                    "on an interface method",
                                    out);
                        } else if (m instanceof VariableTree vt) {
                            flag(
                                    ctx,
                                    vt.getModifiers(),
                                    Set.of(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL),
                                    "on an interface field",
                                    out);
                        }
                    } else if (kind == Tree.Kind.ENUM
                            && m instanceof MethodTree mt
                            && mt.getReturnType() == null
                            && mt.getName().contentEquals("<init>")) {
                        flag(ctx, mt.getModifiers(), Set.of(Modifier.PRIVATE), "on an enum constructor", out);
                    }
                }
                for (Tree m : t.getMembers()) scan(m, kind);
                return null;
            }

            @Override
            public Void visitMethod(MethodTree t, Tree.Kind outer) {
                return scan(t.getBody(), null);
            }

            @Override
            public Void visitVariable(VariableTree t, Tree.Kind outer) {
                return scan(t.getInitializer(), null);
            }
        }.scan(ctx.tree(), null);
        return out;
    }

    private static String name(Tree.Kind k) {
        return switch (k) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            case ANNOTATION_TYPE -> "annotation type";
            default -> "class";
        };
    }

    /**
     * Adds a problem for each of {@code redundant} that appears among the modifiers' keywords.
     */
    private static void flag(
            Context ctx, ModifiersTree mods, Set<Modifier> redundant, String where, List<Problem> out) {
        if (mods == null || mods.getFlags().stream().noneMatch(redundant::contains)) return;
        int s = ctx.start(mods), e = ctx.end(mods);
        if (s < 0 || e <= s) return;
        String src = ctx.source();
        List<int[]> skip = new ArrayList<>();
        for (AnnotationTree a : mods.getAnnotations()) skip.add(new int[]{ctx.start(a), ctx.end(a)});
        int i = s;
        while (i < e) {
            int at = i;
            int[] sk = skip.stream()
                    .filter(r -> at >= r[0] && at < r[1])
                    .findFirst()
                    .orElse(null);
            if (sk != null) {
                i = sk[1];
                continue;
            }
            if (src.startsWith("//", i)) {
                int nl = src.indexOf('\n', i);
                i = nl < 0 ? e : nl;
                continue;
            }
            if (src.startsWith("/*", i)) {
                int c = src.indexOf("*/", i + 2);
                i = c < 0 ? e : c + 2;
                continue;
            }
            if (Character.isJavaIdentifierStart(src.charAt(i))) {
                int j = i;
                while (j < e && Character.isJavaIdentifierPart(src.charAt(j))) j++;
                String word = src.substring(i, j);
                for (Modifier m : redundant) {
                    if (m.toString().equals(word) && mods.getFlags().contains(m)) {
                        out.add(new Problem(i, j, "Modifier '" + word + "' is redundant " + where));
                    }
                }
                i = j;
                continue;
            }
            i++;
        }
    }
}
