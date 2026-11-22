package dev.jpm.format.intellij;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.Source;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Fix;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Modifier;

/**
 * IntelliJ's "Minimum blank lines": one blank line around methods, nested classes and initializer blocks, none
 * required around fields, after the class header or before its end. Palantir Java Format leaves it out when the next
 * member starts with a comment, such as its Javadoc. The blank line goes before the comment, which belongs to the
 * member.
 */
public final class MemberBlankLines implements IntelliJRule {
    public static final String ID = "MemberBlankLines";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String format(Context ctx) {
        String src = ctx.source();
        String nl = Source.newline(src);
        List<Fix.Edit> edits = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree c, Void v) {
                List<Tree> members = new ArrayList<>();
                for (Tree m : c.getMembers()) {
                    if (ctx.start(m) < 0 || ctx.end(m) <= 0) continue;
                    if (c.getKind() == Tree.Kind.RECORD
                            && m instanceof VariableTree vt
                            && !vt.getModifiers().getFlags().contains(Modifier.STATIC)) continue;
                    members.add(m);
                }
                for (int k = 1; k < members.size(); k++) {
                    Tree prev = members.get(k - 1), next = members.get(k);
                    if (!spaced(prev) && !spaced(next)) continue;
                    int at = insertionLine(ctx, src, prev, next);
                    if (at > 0) edits.add(new Fix.Edit(ctx.lineStart(at), ctx.lineStart(at), nl));
                }
                return super.visitClass(c, v);
            }
        }.scan(ctx.tree(), null);
        return edits.isEmpty() ? src : Fix.apply(src, edits);
    }

    /** Members that want a blank line around them. */
    static boolean spaced(Tree m) {
        return m instanceof MethodTree || m instanceof ClassTree || m instanceof BlockTree;
    }

    /**
     * The line to put a blank line before: the first non-blank line after {@code prev} (the start of {@code next}
     * or of its comments), or -1 if a blank line is already there or both are on one line.
     */
    static int insertionLine(Context ctx, String src, Tree prev, Tree next) {
        int prevLine = ctx.line(ctx.end(prev) - 1);
        int nextLine = ctx.line(ctx.start(next));
        for (int l = prevLine + 1; l <= nextLine; l++) {
            int s = ctx.lineStart(l), e = ctx.lineStart(l + 1);
            if (src.substring(s, Math.min(e, src.length())).isBlank()) return -1;
            return l;
        }
        return -1;
    }
}
