package dev.jpm.format.intellij;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.Source;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Fix;

import java.util.ArrayList;
import java.util.List;

/**
 * IntelliJ's "Keep when reformatting: simple methods / classes / blocks in one line", all off by default: an empty
 * body is written {@code {}} on two lines, the closing brace under the first line of the declaration or statement
 * (the line of the brace for {@code else}, {@code catch}, {@code finally} and {@code do}). The layout engine keeps
 * {@code void f() {}} and {@code if (x) {}} on one line.
 *
 * <p>Method and constructor bodies, named classes, initializer blocks and the blocks of {@code if}, {@code else},
 * loops, {@code try}, {@code catch}, {@code finally} and {@code synchronized}, and those of lambdas and anonymous
 * classes, whose {@code }} goes under the line the lambda or the {@code new} starts on, and the blocks of
 * {@code switch} cases, whose {@code }} goes under the {@code case}. Array initializers and empty enums keep
 * {@code {}}.
 */
public final class EmptyBodies implements IntelliJRule {
    public static final String ID = "EmptyBodies";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String format(Context ctx) {
        String src = ctx.source();
        String nl = Source.newline(src);
        List<int[]> braces = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            private void block(Tree t) {
                block(t, t);
            }

            private void block(Tree t, Tree owner) {
                if (t instanceof BlockTree b && b.getStatements().isEmpty())
                    braces.add(new int[]{ctx.start(b), ctx.start(owner)});
            }

            @Override
            public Void visitMethod(MethodTree m, Void v) {
                block(m.getBody(), m);
                return super.visitMethod(m, v);
            }

            @Override
            public Void visitClass(ClassTree c, Void v) {
                int end = ctx.end(c);
                boolean empty = c.getMembers().stream().allMatch(m -> ctx.start(m) < 0 || ctx.start(m) < end - 2);
                if (empty && c.getKind() != Tree.Kind.ENUM && end >= 2 && src.charAt(end - 1) == '}') {
                    braces.add(new int[]{end - 2, ctx.start(c)});
                }
                for (Tree m : c.getMembers()) if (m instanceof BlockTree) block(m);
                return super.visitClass(c, v);
            }

            @Override
            public Void visitNewClass(NewClassTree n, Void v) {
                scan(n.getEnclosingExpression(), v);
                scan(n.getIdentifier(), v);
                scan(n.getTypeArguments(), v);
                scan(n.getArguments(), v);
                if (n.getClassBody() != null) {
                    if (n.getClassBody().getMembers().stream().allMatch(m -> ctx.start(m) < 0)) {
                        int end = ctx.end(n);
                        if (end >= 2 && src.charAt(end - 1) == '}') braces.add(new int[]{end - 2, ctx.start(n)});
                    }
                    scan(n.getClassBody().getMembers(), v);
                }
                return null;
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree l, Void v) {
                block(l.getBody(), l);
                return super.visitLambdaExpression(l, v);
            }

            @Override
            public Void visitIf(IfTree t, Void v) {
                block(t.getThenStatement(), t);
                block(t.getElseStatement());
                return super.visitIf(t, v);
            }

            @Override
            public Void visitForLoop(ForLoopTree t, Void v) {
                block(t.getStatement(), t);
                return super.visitForLoop(t, v);
            }

            @Override
            public Void visitEnhancedForLoop(EnhancedForLoopTree t, Void v) {
                block(t.getStatement(), t);
                return super.visitEnhancedForLoop(t, v);
            }

            @Override
            public Void visitWhileLoop(WhileLoopTree t, Void v) {
                block(t.getStatement(), t);
                return super.visitWhileLoop(t, v);
            }

            @Override
            public Void visitDoWhileLoop(DoWhileLoopTree t, Void v) {
                block(t.getStatement());
                return super.visitDoWhileLoop(t, v);
            }

            @Override
            public Void visitTry(TryTree t, Void v) {
                block(t.getBlock(), t);
                block(t.getFinallyBlock());
                return super.visitTry(t, v);
            }

            @Override
            public Void visitCatch(CatchTree t, Void v) {
                block(t.getBlock());
                return super.visitCatch(t, v);
            }

            @Override
            public Void visitCase(CaseTree t, Void v) {
                if (t.getCaseKind() == CaseTree.CaseKind.RULE) block(t.getBody(), t);
                else if (!t.getStatements().isEmpty()) block(t.getStatements().getFirst(), t);
                return super.visitCase(t, v);
            }

            @Override
            public Void visitSynchronized(SynchronizedTree t, Void v) {
                block(t.getBlock(), t);
                return super.visitSynchronized(t, v);
            }
        }.scan(ctx.tree(), null);
        List<Fix.Edit> edits = new ArrayList<>();
        for (int[] b : braces) {
            int at = b[0];
            if (at < 0 || !src.startsWith("{}", at)) continue;
            edits.add(new Fix.Edit(at + 1, at + 1, nl + indentOf(src, b[1] < 0 ? at : b[1])));
        }
        return edits.isEmpty() ? src : Fix.apply(src, edits);
    }

    /**
     * The leading whitespace of the line {@code offset} is on.
     */
    static String indentOf(String src, int offset) {
        int ls = src.lastIndexOf('\n', offset - 1) + 1;
        int k = ls;
        while (k < offset && (src.charAt(k) == ' ' || src.charAt(k) == '\t')) k++;
        return src.substring(ls, k);
    }
}
