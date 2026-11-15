package dev.jpm.format.intellij;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
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
 * body is written {@code {}} on two lines, the closing brace under the line that opens it. Palantir Java Format keeps
 * {@code void f() {}}, {@code class A {}} and {@code if (x) {}} on one line.
 *
 * <p>Method and constructor bodies, named classes, initializer blocks and the blocks of {@code if}, {@code else},
 * loops, {@code try}, {@code catch}, {@code finally} and {@code synchronized}. Lambdas, anonymous classes,
 * {@code switch} cases and array initializers keep {@code {}}.
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
        List<Integer> braces = new ArrayList<>(); // offsets of "{}" to split
        new TreeScanner<Void, Void>() {
            private void block(Tree t) {
                if (t instanceof BlockTree b && b.getStatements().isEmpty()) braces.add(ctx.start(b));
            }

            @Override
            public Void visitMethod(MethodTree m, Void v) {
                block(m.getBody());
                return super.visitMethod(m, v);
            }

            @Override
            public Void visitClass(ClassTree c, Void v) {
                if (c.getMembers().stream().allMatch(m -> ctx.start(m) < 0)) {
                    int end = ctx.end(c);
                    if (end >= 2 && src.charAt(end - 1) == '}') braces.add(end - 2);
                }
                for (Tree m : c.getMembers()) if (m instanceof BlockTree) block(m); // initializers
                return super.visitClass(c, v);
            }

            @Override
            public Void visitNewClass(NewClassTree n, Void v) {
                // an anonymous class keeps {}: scan inside it, but not its own body as a class
                scan(n.getEnclosingExpression(), v);
                scan(n.getIdentifier(), v);
                scan(n.getTypeArguments(), v);
                scan(n.getArguments(), v);
                if (n.getClassBody() != null) scan(n.getClassBody().getMembers(), v);
                return null;
            }

            @Override
            public Void visitIf(IfTree t, Void v) {
                block(t.getThenStatement());
                block(t.getElseStatement());
                return super.visitIf(t, v);
            }

            @Override
            public Void visitForLoop(ForLoopTree t, Void v) {
                block(t.getStatement());
                return super.visitForLoop(t, v);
            }

            @Override
            public Void visitEnhancedForLoop(EnhancedForLoopTree t, Void v) {
                block(t.getStatement());
                return super.visitEnhancedForLoop(t, v);
            }

            @Override
            public Void visitWhileLoop(WhileLoopTree t, Void v) {
                block(t.getStatement());
                return super.visitWhileLoop(t, v);
            }

            @Override
            public Void visitDoWhileLoop(DoWhileLoopTree t, Void v) {
                block(t.getStatement());
                return super.visitDoWhileLoop(t, v);
            }

            @Override
            public Void visitTry(TryTree t, Void v) {
                block(t.getBlock());
                block(t.getFinallyBlock());
                return super.visitTry(t, v);
            }

            @Override
            public Void visitCatch(CatchTree t, Void v) {
                block(t.getBlock());
                return super.visitCatch(t, v);
            }

            @Override
            public Void visitSynchronized(SynchronizedTree t, Void v) {
                block(t.getBlock());
                return super.visitSynchronized(t, v);
            }
        }.scan(ctx.tree(), null);
        List<Fix.Edit> edits = new ArrayList<>();
        for (int at : braces) {
            if (at < 0 || !src.startsWith("{}", at)) continue; // not written as {} (a comment inside, or split already)
            edits.add(new Fix.Edit(at + 1, at + 1, nl + indentOf(src, at)));
        }
        return edits.isEmpty() ? src : Fix.apply(src, edits);
    }

    /** The leading whitespace of the line {@code offset} is on. */
    static String indentOf(String src, int offset) {
        int ls = src.lastIndexOf('\n', offset - 1) + 1;
        int k = ls;
        while (k < offset && (src.charAt(k) == ' ' || src.charAt(k) == '\t')) k++;
        return src.substring(ls, k);
    }
}
