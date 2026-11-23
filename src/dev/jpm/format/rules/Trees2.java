package dev.jpm.format.rules;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.api.Context;

import java.util.HashSet;
import java.util.Set;

/**
 * Small helpers on javac trees shared by the built-in rules.
 */
final class Trees2 {
    private Trees2() {
    }

    /**
     * Every simple name referred to under {@code root} (identifiers, member selects, method references).
     */
    static Set<String> names(Tree root) {
        Set<String> out = new HashSet<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitImport(ImportTree t, Void v) {
                return null;
            }

            @Override
            public Void visitIdentifier(IdentifierTree t, Void v) {
                out.add(t.getName().toString());
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree t, Void v) {
                out.add(t.getIdentifier().toString());
                return super.visitMemberSelect(t, v);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree t, Void v) {
                out.add(t.getName().toString());
                return super.visitMemberReference(t, v);
            }
        }.scan(root, null);
        return out;
    }

    /**
     * True if an identifier named {@code name} appears under {@code root}.
     */
    static boolean usesIdentifier(Tree root, String name) {
        Boolean found = new TreeScanner<Boolean, Void>() {
            @Override
            public Boolean visitIdentifier(IdentifierTree t, Void v) {
                return t.getName().contentEquals(name);
            }

            @Override
            public Boolean reduce(Boolean a, Boolean b) {
                return (a != null && a) || (b != null && b);
            }
        }.scan(root, null);
        return found != null && found;
    }

    static boolean hasAnnotation(ModifiersTree mods, String simpleName) {
        if (mods == null) return false;
        for (AnnotationTree a : mods.getAnnotations()) {
            String n = a.getAnnotationType().toString();
            if (n.equals(simpleName) || n.endsWith("." + simpleName)) return true;
        }
        return false;
    }

    /**
     * True if the expression can be used as a receiver or after {@code !} without parentheses.
     */
    static boolean primary(ExpressionTree e) {
        return e instanceof IdentifierTree
                || e instanceof MemberSelectTree
                || e instanceof MethodInvocationTree
                || e instanceof LiteralTree
                || e instanceof ParenthesizedTree
                || e instanceof ArrayAccessTree
                || e instanceof NewClassTree;
    }

    static String text(Context ctx, Tree t) {
        return ctx.source().substring(ctx.start(t), ctx.end(t));
    }

    static String paren(Context ctx, ExpressionTree e) {
        String s = text(ctx, e);
        return primary(e) ? s : "(" + s + ")";
    }

    /**
     * The whitespace before {@code offset} on its line, or null if code precedes it there.
     */
    static String indentAt(String src, int offset) {
        int ls = src.lastIndexOf('\n', offset - 1) + 1;
        String before = src.substring(ls, offset);
        return before.isBlank() ? before : null;
    }

    /**
     * Extends {@code [start, end)} to whole lines when nothing else is on them.
     */
    static int[] wholeLines(String src, int start, int end) {
        int ls = src.lastIndexOf('\n', start - 1) + 1;
        int le = src.indexOf('\n', end);
        le = le < 0 ? src.length() : le + 1;
        if (src.substring(ls, start).isBlank() && src.substring(end, le).isBlank()) return new int[]{ls, le};
        return new int[]{start, end};
    }
}
