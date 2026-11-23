package dev.jpm.format;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;

/**
 * Finds the element (declaration, import or statement) that follows an offset, for suppression comments.
 */
final class Elements {
    private Elements() {
    }

    /**
     * {@code [start, end)} of the outermost element starting at or after {@code offset}, or null.
     */
    static int[] next(Source src, int offset) {
        int[] best = new int[] {Integer.MAX_VALUE, -1};
        new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree t, Void v) {
                if (t == null) return null;
                int s = src.start(t);
                int e = src.end(t);
                if (e >= 0 && e <= offset) return null;
                if (s >= offset && isElement(t)) {
                    if (s < best[0] || (s == best[0] && e > best[1])) {
                        best[0] = s;
                        best[1] = e;
                    }
                    return null;
                }
                return super.scan(t, v);
            }
        }.scan(src.tree(), null);
        return best[1] < 0 ? null : best;
    }

    static boolean isElement(Tree t) {
        return t instanceof StatementTree
                || t instanceof ClassTree
                || t instanceof MethodTree
                || t instanceof VariableTree
                || t instanceof ImportTree;
    }
}
