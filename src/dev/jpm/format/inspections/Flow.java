package dev.jpm.format.inspections;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;

import java.util.function.Predicate;

/**
 * Questions about control flow that several inspections ask: what a statement's last statement is, whether code
 * leaves a loop, and which statement a {@code break} or {@code continue} goes to.
 */
final class Flow {
    private Flow() {
    }

    /**
     * True if {@code t} is a loop.
     */
    static boolean loop(Tree t) {
        return t instanceof WhileLoopTree
                || t instanceof DoWhileLoopTree
                || t instanceof ForLoopTree
                || t instanceof EnhancedForLoopTree;
    }

    /**
     * The body of a loop.
     */
    static StatementTree body(Tree loop) {
        return switch (loop) {
            case WhileLoopTree w -> w.getStatement();
            case DoWhileLoopTree d -> d.getStatement();
            case ForLoopTree f -> f.getStatement();
            case EnhancedForLoopTree e -> e.getStatement();
            default -> null;
        };
    }

    /**
     * True if a loop's condition is missing or the literal {@code true}.
     */
    static boolean infinite(Tree loop) {
        ExpressionTree c = switch (loop) {
            case WhileLoopTree w -> w.getCondition();
            case DoWhileLoopTree d -> d.getCondition();
            case ForLoopTree f -> f.getCondition();
            default -> null;
        };
        if (loop instanceof EnhancedForLoopTree) return false;
        return c == null ? loop instanceof ForLoopTree : Boolean.TRUE.equals(Scan.bool(c));
    }

    /**
     * The statement {@code b} leaves, found from its path.
     */
    static Tree target(TreePath path) {
        Tree leaf = path.getLeaf();
        String label = leaf instanceof BreakTree b && b.getLabel() != null
                ? b.getLabel().toString()
                : leaf instanceof ContinueTree c && c.getLabel() != null ? c.getLabel().toString() : null;
        boolean isContinue = leaf instanceof ContinueTree;
        for (TreePath p = path.getParentPath(); p != null; p = p.getParentPath()) {
            Tree t = p.getLeaf();
            if (t instanceof ClassTree || t instanceof LambdaExpressionTree || t instanceof MethodTree) return null;
            if (label != null) {
                if (t instanceof LabeledStatementTree l && l.getLabel().contentEquals(label)) return l.getStatement();
            } else if (loop(t) || !isContinue && (t instanceof SwitchTree || t instanceof SwitchExpressionTree)) {
                return t;
            }
        }
        return null;
    }

    /**
     * True if {@code pred} holds for a node under {@code root}, not looking into classes and lambdas.
     */
    static boolean contains(Tree root, Predicate<Tree> pred) {
        Boolean found = new TreeScanner<Boolean, Void>() {
            @Override
            public Boolean scan(Tree t, Void v) {
                if (t == null) return false;
                if (pred.test(t)) return true;
                if (t != root && (t instanceof ClassTree || t instanceof LambdaExpressionTree)) return false;
                return super.scan(t, v);
            }

            @Override
            public Boolean reduce(Boolean a, Boolean b) {
                return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
            }
        }.scan(root, null);
        return Boolean.TRUE.equals(found);
    }

    /**
     * True if something in the loop at {@code loopPath} leaves it: a break to it or beyond, a return, a throw.
     */
    static boolean exits(TreePath loopPath) {
        Tree loop = loopPath.getLeaf();
        Boolean found = new com.sun.source.util.TreePathScanner<Boolean, Void>() {
            @Override
            public Boolean scan(Tree t, Void v) {
                if (t instanceof ClassTree || t instanceof LambdaExpressionTree) return false;
                return super.scan(t, v);
            }

            @Override
            public Boolean visitReturn(ReturnTree t, Void v) {
                return true;
            }

            @Override
            public Boolean visitThrow(ThrowTree t, Void v) {
                return true;
            }

            @Override
            public Boolean visitMethodInvocation(MethodInvocationTree m, Void v) {
                if (Scan.callsStatic(m, "System", "exit") || Scan.name(m).equals("halt") || Scan.name(m).equals("fail")) {
                    return true;
                }
                return super.visitMethodInvocation(m, v);
            }

            @Override
            public Boolean visitBreak(BreakTree b, Void v) {
                Tree t = target(getCurrentPath());
                return t == null || t == loop || !Flow.contains(Flow.body(loop), x -> x == t);
            }

            @Override
            public Boolean reduce(Boolean a, Boolean b) {
                return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
            }
        }.scan(new TreePath(loopPath, body(loop)), null);
        return Boolean.TRUE.equals(found);
    }

    /**
     * The last statement of {@code t}, looking into blocks; {@code t} itself when it is not a block.
     */
    static StatementTree last(StatementTree t) {
        while (t instanceof BlockTree b) {
            if (b.getStatements().isEmpty()) return b;
            t = b.getStatements().get(b.getStatements().size() - 1);
        }
        return t;
    }

    /**
     * True if {@code t} never completes normally: it ends in a return, throw, break or continue.
     */
    static boolean jumps(StatementTree t) {
        StatementTree l = last(t);
        if (l instanceof ReturnTree || l instanceof ThrowTree || l instanceof BreakTree || l instanceof ContinueTree) {
            return true;
        }
        if (l instanceof IfTree i) {
            return i.getElseStatement() != null && jumps(i.getThenStatement()) && jumps(i.getElseStatement());
        }
        if (l instanceof TryTree tr) return tr.getFinallyBlock() != null && jumps(tr.getFinallyBlock());
        if (l instanceof SynchronizedTree s) return jumps(s.getBlock());
        return loop(l) && infinite(l);
    }

    /**
     * True if {@code c} is the {@code default} case.
     */
    static boolean isDefault(CaseTree c) {
        return c.getLabels().stream().anyMatch(l -> l instanceof com.sun.source.tree.DefaultCaseLabelTree);
    }
}
