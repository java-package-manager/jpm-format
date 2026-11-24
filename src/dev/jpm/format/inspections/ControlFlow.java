package dev.jpm.format.inspections;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EmptyStatementTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.TreePath;
import dev.jpm.format.api.Fix;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.type.TypeKind;

/**
 * IntelliJ's inspections about control flow: redundant jumps, empty and suspicious statements, loops and
 * {@code finally}.
 */
final class ControlFlow {
    private ControlFlow() {
    }

    static List<Inspection> all() {
        return List.of(
                new UnnecessaryReturn(),
                new UnnecessaryContinue(),
                new UnnecessaryBreak(),
                new UnusedLabel(),
                new UnnecessaryLabelOnBreakStatement(),
                new UnnecessaryLabelOnContinueStatement(),
                new EmptyStatementBody(),
                new EmptyFinallyBlock(),
                new EmptyTryBlock(),
                new EmptySynchronizedStatement(),
                new ReturnFromFinallyBlock(),
                new ThrowFromFinallyBlock(),
                new ContinueOrBreakFromFinallyBlock(),
                new FinallyBlockCannotCompleteNormally(),
                new InfiniteLoopStatement(),
                new LoopStatementsThatDontLoop(),
                new ConditionalBreakInInfiniteLoop(),
                new ForLoopReplaceableByWhile(),
                new AssignmentUsedAsCondition(),
                new AssignmentToCatchBlockParameter(),
                new CaughtExceptionImmediatelyRethrown(),
                new TrivialIf(),
                new IfStatementWithIdenticalBranches(),
                new DuplicateCondition(),
                new SwitchStatementWithTooFewBranches(),
                new DefaultNotLastCaseInSwitch(),
                new TextLabelInSwitchStatement(),
                new DuplicateBranchesInSwitch(),
                new RedundantLabeledSwitchRuleCodeBlock(),
                new SuspiciousIndentAfterControlStatement(),
                new UnnecessaryLocalVariable(),
                new CodeBlock2Expr(),
                new TryWithIdenticalCatches(),
                new RedundantExplicitClose(),
                new InfiniteRecursion());
    }

    /**
     * A fix that deletes statement {@code t}, with its line when nothing else is on it.
     */
    static Fix deleteStatement(Scan s, String description, Tree t) {
        String src = s.source();
        int start = s.start(t), end = s.end(t);
        int ls = src.lastIndexOf('\n', start - 1) + 1;
        int le = src.indexOf('\n', end);
        le = le < 0 ? src.length() : le + 1;
        if (src.substring(ls, start).isBlank() && src.substring(end, le).isBlank()) {
            start = ls;
            end = le;
        }
        return Fix.delete(description, start, end);
    }

    /**
     * Calls {@code f} with each statement in a tail position of {@code body}: last, and last of last branches.
     */
    static void tails(StatementTree body, java.util.function.Consumer<StatementTree> f) {
        if (body == null) return;
        if (body instanceof BlockTree b) {
            if (!b.getStatements().isEmpty()) tails(b.getStatements().get(b.getStatements().size() - 1), f);
            return;
        }
        if (body instanceof IfTree i) {
            tails(i.getThenStatement(), f);
            tails(i.getElseStatement(), f);
            return;
        }
        f.accept(body);
    }

    static final class UnnecessaryReturn extends Inspection {
        UnnecessaryReturn() {
            super("UnnecessaryReturn", "complexity", Types.NONE,
                    "A `return;` as the last thing a `void` method, constructor or lambda does. The fix deletes it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    boolean isVoid = m.getReturnType() == null
                            || m.getReturnType() instanceof PrimitiveTypeTree p && p.getPrimitiveTypeKind() == TypeKind.VOID;
                    if (isVoid && m.getBody() != null) check(m.getBody());
                    return super.visitMethod(m, v);
                }

                @Override
                public Void visitLambdaExpression(LambdaExpressionTree l, Void v) {
                    if (l.getBody() instanceof BlockTree b) check(b);
                    return super.visitLambdaExpression(l, v);
                }

                private void check(BlockTree body) {
                    tails(body, t -> {
                        if (t instanceof ReturnTree r && r.getExpression() == null) {
                            s.report(r, "'return' is unnecessary as the last statement",
                                    deleteStatement(s, "Delete the return", r));
                        }
                    });
                }
            }.run();
        }
    }

    static final class UnnecessaryContinue extends Inspection {
        UnnecessaryContinue() {
            super("UnnecessaryContinue", "complexity", Types.NONE,
                    "A `continue` as the last thing a loop's body does. The fix deletes it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void scan(Tree t, Void v) {
                    if (Flow.loop(t)) {
                        tails(Flow.body(t), x -> {
                            if (x instanceof ContinueTree c && (c.getLabel() == null || labels(t, c))) {
                                s.report(c, "'continue' is unnecessary as the last statement in a loop",
                                        deleteStatement(s, "Delete the continue", c));
                            }
                        });
                    }
                    return super.scan(t, v);
                }

                private boolean labels(Tree loop, ContinueTree c) {
                    return parent() instanceof LabeledStatementTree l && l.getStatement() == loop
                            && l.getLabel().contentEquals(c.getLabel());
                }
            }.run();
        }
    }

    static final class UnnecessaryBreak extends Inspection {
        UnnecessaryBreak() {
            super("UnnecessaryBreak", "complexity", Types.NONE,
                    "A `break` as the last statement of a `case ->` block, which never falls through. The fix deletes"
                            + " it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitCase(CaseTree c, Void v) {
                    if (c.getCaseKind() == CaseTree.CaseKind.RULE && c.getBody() instanceof BlockTree b
                            && !b.getStatements().isEmpty()
                            && b.getStatements().get(b.getStatements().size() - 1) instanceof BreakTree br
                            && br.getLabel() == null) {
                        s.report(br, "'break' is unnecessary in a 'case ->' block",
                                deleteStatement(s, "Delete the break", br));
                    }
                    return super.visitCase(c, v);
                }
            }.run();
        }
    }

    static final class UnusedLabel extends Inspection {
        UnusedLabel() {
            super("UnusedLabel", "complexity", Types.NONE,
                    "A label that no `break` or `continue` names. The fix removes the label.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitLabeledStatement(LabeledStatementTree l, Void v) {
                    String name = l.getLabel().toString();
                    boolean used = Flow.contains(l.getStatement(), t ->
                            t instanceof BreakTree b && b.getLabel() != null && b.getLabel().contentEquals(name)
                                    || t instanceof ContinueTree c && c.getLabel() != null && c.getLabel().contentEquals(name));
                    if (!used) {
                        s.report(s.start(l), s.start(l) + name.length(), "Label '" + name + "' is not used",
                                Fix.delete("Remove the label", s.start(l), s.start(l.getStatement())));
                    }
                    return super.visitLabeledStatement(l, v);
                }
            }.run();
        }
    }

    static final class UnnecessaryLabelOnBreakStatement extends Inspection {
        UnnecessaryLabelOnBreakStatement() {
            super("UnnecessaryLabelOnBreakStatement", "complexity", Types.NONE,
                    "`break label;` where the label names the loop or `switch` the `break` would leave anyway. The fix"
                            + " removes the label.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBreak(BreakTree b, Void v) {
                    if (b.getLabel() != null && unnecessary(getCurrentPath(), b.getLabel().toString(), false)) {
                        s.report(b, "Unnecessary label on 'break'", s.replace("Remove the label", b, "break;"));
                    }
                    return null;
                }
            }.run();
        }
    }

    static final class UnnecessaryLabelOnContinueStatement extends Inspection {
        UnnecessaryLabelOnContinueStatement() {
            super("UnnecessaryLabelOnContinueStatement", "complexity", Types.NONE,
                    "`continue label;` where the label names the innermost loop. The fix removes the label.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitContinue(ContinueTree c, Void v) {
                    if (c.getLabel() != null && unnecessary(getCurrentPath(), c.getLabel().toString(), true)) {
                        s.report(c, "Unnecessary label on 'continue'", s.replace("Remove the label", c, "continue;"));
                    }
                    return null;
                }
            }.run();
        }
    }

    /**
     * True if the innermost loop (or switch, for a break) around the jump at {@code path} carries {@code label}.
     */
    static boolean unnecessary(TreePath path, String label, boolean isContinue) {
        for (TreePath p = path.getParentPath(); p != null; p = p.getParentPath()) {
            Tree t = p.getLeaf();
            if (t instanceof ClassTree || t instanceof LambdaExpressionTree) return false;
            if (Flow.loop(t) || !isContinue && t instanceof SwitchTree) {
                return p.getParentPath().getLeaf() instanceof LabeledStatementTree l && l.getLabel().contentEquals(label);
            }
        }
        return false;
    }

    static final class EmptyStatementBody extends Inspection {
        EmptyStatementBody() {
            super("EmptyStatementBody", "suspicious", Types.NONE,
                    "An `if`, loop or `switch` whose body is empty: `;` or `{}` with no comment in it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitIf(IfTree i, Void v) {
                    check(i, i.getThenStatement(), "if");
                    if (i.getElseStatement() != null && !(i.getElseStatement() instanceof IfTree)) {
                        check(i, i.getElseStatement(), "else");
                    }
                    return super.visitIf(i, v);
                }

                @Override
                public Void scan(Tree t, Void v) {
                    if (Flow.loop(t)) {
                        check(t, Flow.body(t), t instanceof DoWhileLoopTree ? "do" : t instanceof WhileLoopTree ? "while" : "for");
                    }
                    return super.scan(t, v);
                }

                @Override
                public Void visitSwitch(SwitchTree t, Void v) {
                    if (t.getCases().isEmpty() && !s.hasComment(t)) s.report(t, "'switch' statement has an empty body");
                    return super.visitSwitch(t, v);
                }

                private void check(Tree owner, StatementTree body, String what) {
                    boolean empty = body instanceof EmptyStatementTree
                            || body instanceof BlockTree b && b.getStatements().isEmpty() && !s.hasComment(b);
                    if (empty) s.report(owner, "'" + what + "' statement has an empty body");
                }
            }.run();
        }
    }

    static final class EmptyFinallyBlock extends Inspection {
        EmptyFinallyBlock() {
            super("EmptyFinallyBlock", "complexity", Types.NONE, "A `finally` block with nothing in it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitTry(TryTree t, Void v) {
                    BlockTree f = t.getFinallyBlock();
                    if (f != null && f.getStatements().isEmpty() && !s.hasComment(f)) {
                        s.report(f, "Empty 'finally' block");
                    }
                    return super.visitTry(t, v);
                }
            }.run();
        }
    }

    static final class EmptyTryBlock extends Inspection {
        EmptyTryBlock() {
            super("EmptyTryBlock", "complexity", Types.NONE,
                    "A `try` block with nothing in it and no resources.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitTry(TryTree t, Void v) {
                    if (t.getResources().isEmpty() && t.getBlock().getStatements().isEmpty() && !s.hasComment(t.getBlock())) {
                        s.report(t.getBlock(), "Empty 'try' block");
                    }
                    return super.visitTry(t, v);
                }
            }.run();
        }
    }

    static final class EmptySynchronizedStatement extends Inspection {
        EmptySynchronizedStatement() {
            super("EmptySynchronizedStatement", "suspicious", Types.NONE,
                    "A `synchronized` block with nothing in it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitSynchronized(SynchronizedTree t, Void v) {
                    if (t.getBlock().getStatements().isEmpty() && !s.hasComment(t.getBlock())) {
                        s.report(t, "Empty 'synchronized' statement");
                    }
                    return super.visitSynchronized(t, v);
                }
            }.run();
        }
    }

    /**
     * Scans the finally blocks, with whether each node is inside one (not in a lambda or class in it).
     */
    abstract static class InFinally extends Inspection.Visitor {
        int depth;

        InFinally(Scan s) {
            super(s);
        }

        @Override
        public Void visitTry(TryTree t, Void v) {
            scan(t.getResources(), v);
            scan(t.getBlock(), v);
            scan(t.getCatches(), v);
            if (t.getFinallyBlock() != null) {
                depth++;
                scan(t.getFinallyBlock(), v);
                depth--;
            }
            return null;
        }

        @Override
        public Void visitClass(ClassTree c, Void v) {
            int d = depth;
            depth = 0;
            super.visitClass(c, v);
            depth = d;
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree l, Void v) {
            int d = depth;
            depth = 0;
            super.visitLambdaExpression(l, v);
            depth = d;
            return null;
        }

        /**
         * The innermost finally block around the current node.
         */
        BlockTree finallyBlock() {
            for (TreePath p = getCurrentPath(); p.getParentPath() != null; p = p.getParentPath()) {
                if (p.getParentPath().getLeaf() instanceof TryTree t && t.getFinallyBlock() == p.getLeaf()) {
                    return t.getFinallyBlock();
                }
            }
            return null;
        }
    }

    static final class ReturnFromFinallyBlock extends Inspection {
        ReturnFromFinallyBlock() {
            super("ReturnFromFinallyBlock", "suspicious", Types.NONE,
                    "A `return` inside a `finally` block, which discards any exception thrown in the `try`.");
        }

        @Override
        protected void run(Scan s) {
            new InFinally(s) {
                @Override
                public Void visitReturn(ReturnTree r, Void v) {
                    if (depth > 0) s.report(r, "'return' inside a 'finally' block");
                    return super.visitReturn(r, v);
                }
            }.run();
        }
    }

    static final class ThrowFromFinallyBlock extends Inspection {
        ThrowFromFinallyBlock() {
            super("ThrowFromFinallyBlock", "suspicious", Types.NONE,
                    "A `throw` inside a `finally` block, not caught there, which replaces any exception thrown in the"
                            + " `try`.");
        }

        @Override
        protected void run(Scan s) {
            new InFinally(s) {
                @Override
                public Void visitThrow(ThrowTree t, Void v) {
                    if (depth > 0 && !caughtInFinally()) s.report(t, "'throw' inside a 'finally' block");
                    return super.visitThrow(t, v);
                }

                private boolean caughtInFinally() {
                    BlockTree f = finallyBlock();
                    for (TreePath p = getCurrentPath(); p != null && p.getLeaf() != f; p = p.getParentPath()) {
                        if (p.getParentPath() != null && p.getParentPath().getLeaf() instanceof TryTree t
                                && t.getBlock() == p.getLeaf() && !t.getCatches().isEmpty()) {
                            return true;
                        }
                    }
                    return false;
                }
            }.run();
        }
    }

    static final class ContinueOrBreakFromFinallyBlock extends Inspection {
        ContinueOrBreakFromFinallyBlock() {
            super("ContinueOrBreakFromFinallyBlock", "suspicious", Types.NONE,
                    "A `break` or `continue` that leaves a `finally` block, which discards any exception thrown in the"
                            + " `try`.");
        }

        @Override
        protected void run(Scan s) {
            new InFinally(s) {
                @Override
                public Void visitBreak(BreakTree b, Void v) {
                    check(b, "break");
                    return null;
                }

                @Override
                public Void visitContinue(ContinueTree c, Void v) {
                    check(c, "continue");
                    return null;
                }

                private void check(Tree jump, String what) {
                    if (depth == 0) return;
                    Tree target = Flow.target(getCurrentPath());
                    BlockTree f = finallyBlock();
                    if (target == null || f == null || !Flow.contains(f, t -> t == target)) {
                        s.report(jump, "'" + what + "' inside a 'finally' block");
                    }
                }
            }.run();
        }
    }

    static final class FinallyBlockCannotCompleteNormally extends Inspection {
        FinallyBlockCannotCompleteNormally() {
            super("FinallyBlockCannotCompleteNormally", "suspicious", Types.NONE,
                    "A `finally` block that always ends in a `return`, `throw`, `break` or `continue`, so the `try`"
                            + " never completes as written.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitTry(TryTree t, Void v) {
                    BlockTree f = t.getFinallyBlock();
                    if (f != null && !f.getStatements().isEmpty() && Flow.jumps(f)) {
                        int at = s.source().lastIndexOf("finally", s.start(f));
                        s.report(at, at + "finally".length(), "'finally' block can not complete normally", null);
                    }
                    return super.visitTry(t, v);
                }
            }.run();
        }
    }

    /**
     * The range of a loop's keyword.
     */
    static int[] keyword(Scan s, Tree loop) {
        String k = loop instanceof WhileLoopTree ? "while" : loop instanceof DoWhileLoopTree ? "do" : "for";
        int at = s.start(loop);
        return new int[]{at, at + k.length()};
    }

    static final class InfiniteLoopStatement extends Inspection {
        InfiniteLoopStatement() {
            super("InfiniteLoopStatement", "correctness", Types.NONE,
                    "A `while (true)`, `for (;;)` or `do ... while (true)` loop that nothing leaves: no `break` to it,"
                            + " `return`, `throw`, `System.exit` or `fail(...)`, so it can only end with an exception.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void scan(Tree t, Void v) {
                    if (Flow.loop(t) && Flow.infinite(t) && !Flow.exits(new TreePath(getCurrentPath(), t))) {
                        int[] k = keyword(s, t);
                        s.report(k[0], k[1], "Loop can not complete without throwing an exception", null);
                    }
                    return super.scan(t, v);
                }
            }.run();
        }
    }

    static final class LoopStatementsThatDontLoop extends Inspection {
        LoopStatementsThatDontLoop() {
            super("LoopStatementsThatDontLoop", "suspicious", Types.NONE,
                    "A loop whose body always ends in a `break`, `return` or `throw` and never `continue`s, so it runs at"
                            + " most once.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void scan(Tree t, Void v) {
                    if (Flow.loop(t) && Flow.body(t) instanceof BlockTree b && !b.getStatements().isEmpty()) {
                        StatementTree last = b.getStatements().get(b.getStatements().size() - 1);
                        boolean ends = last instanceof ReturnTree || last instanceof ThrowTree
                                || last instanceof BreakTree br && br.getLabel() == null;
                        boolean continues = Flow.contains(b, x -> x instanceof ContinueTree);
                        if (ends && !continues) {
                            int[] k = keyword(s, t);
                            s.report(k[0], k[1], "Loop does not loop", null);
                        }
                    }
                    return super.scan(t, v);
                }
            }.run();
        }
    }

    static final class ConditionalBreakInInfiniteLoop extends Inspection {
        ConditionalBreakInInfiniteLoop() {
            super("ConditionalBreakInInfiniteLoop", "style", Types.NONE,
                    "`while (true) { if (c) break; ... }`: the `if` can be the loop's condition, `while (!c)`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void scan(Tree t, Void v) {
                    if ((t instanceof WhileLoopTree || t instanceof ForLoopTree f && f.getInitializer().isEmpty()
                            && f.getUpdate().isEmpty()) && Flow.infinite(t)
                            && Flow.body(t) instanceof BlockTree b && !b.getStatements().isEmpty()
                            && b.getStatements().get(0) instanceof IfTree i && i.getElseStatement() == null
                            && Scan.statements(i.getThenStatement()).size() == 1
                            && Scan.statements(i.getThenStatement()).get(0) instanceof BreakTree br
                            && br.getLabel() == null) {
                        int[] k = keyword(s, t);
                        s.report(k[0], k[1], "Conditional 'break' can be the loop's condition", null);
                    }
                    return super.scan(t, v);
                }
            }.run();
        }
    }

    static final class ForLoopReplaceableByWhile extends Inspection {
        ForLoopReplaceableByWhile() {
            super("ForLoopReplaceableByWhile", "style", Types.NONE,
                    "A `for` loop with a condition but neither initializer nor update, `for (; c; )`, which is a"
                            + " `while` loop. The fix rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitForLoop(ForLoopTree f, Void v) {
                    if (f.getInitializer().isEmpty() && f.getUpdate().isEmpty() && f.getCondition() != null) {
                        String cond = f.getCondition() == null ? "true" : s.text(f.getCondition());
                        s.report(s.start(f), s.start(f) + 3, "'for' loop can be a 'while' loop",
                                Fix.replace("Use while", s.start(f), s.start(f.getStatement()), "while (" + cond + ") "));
                    }
                    return super.visitForLoop(f, v);
                }
            }.run();
        }
    }

    static final class AssignmentUsedAsCondition extends Inspection {
        AssignmentUsedAsCondition() {
            super("AssignmentUsedAsCondition", "suspicious", Types.NONE,
                    "An assignment as the condition of an `if`, loop or `?:`, `if (a = b)`, usually a mistyped `==`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitIf(IfTree i, Void v) {
                    check(i.getCondition());
                    return super.visitIf(i, v);
                }

                @Override
                public Void visitWhileLoop(WhileLoopTree w, Void v) {
                    check(w.getCondition());
                    return super.visitWhileLoop(w, v);
                }

                @Override
                public Void visitDoWhileLoop(DoWhileLoopTree d, Void v) {
                    check(d.getCondition());
                    return super.visitDoWhileLoop(d, v);
                }

                @Override
                public Void visitForLoop(ForLoopTree f, Void v) {
                    check(f.getCondition());
                    return super.visitForLoop(f, v);
                }

                @Override
                public Void visitConditionalExpression(ConditionalExpressionTree c, Void v) {
                    check(c.getCondition());
                    return super.visitConditionalExpression(c, v);
                }

                private void check(ExpressionTree c) {
                    if (c != null && Scan.strip(c) instanceof AssignmentTree a) {
                        s.report(a, "Assignment '" + s.text(a) + "' used as a condition");
                    }
                }
            }.run();
        }
    }

    static final class AssignmentToCatchBlockParameter extends Inspection {
        AssignmentToCatchBlockParameter() {
            super("AssignmentToCatchBlockParameter", "suspicious", Types.NONE,
                    "An assignment to the parameter of a `catch`, which hides the exception that was caught.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitCatch(CatchTree c, Void v) {
                    String name = c.getParameter().getName().toString();
                    new Visitor(s) {
                        @Override
                        public Void visitAssignment(AssignmentTree a, Void v2) {
                            check(a.getVariable());
                            return super.visitAssignment(a, v2);
                        }

                        @Override
                        public Void visitCompoundAssignment(CompoundAssignmentTree a, Void v2) {
                            check(a.getVariable());
                            return super.visitCompoundAssignment(a, v2);
                        }

                        @Override
                        public Void visitClass(ClassTree t, Void v2) {
                            return null;
                        }

                        private void check(ExpressionTree target) {
                            if (target instanceof IdentifierTree i && i.getName().contentEquals(name)) {
                                s.report(target, "Assignment to catch block parameter '" + name + "'");
                            }
                        }
                    }.scan(new TreePath(getCurrentPath(), c.getBlock()), null);
                    return super.visitCatch(c, v);
                }
            }.run();
        }
    }

    static final class CaughtExceptionImmediatelyRethrown extends Inspection {
        CaughtExceptionImmediatelyRethrown() {
            super("CaughtExceptionImmediatelyRethrown", "complexity", Types.NONE,
                    "The last `catch` of a `try` that only rethrows what it caught, `catch (E e) { throw e; }`, which"
                            + " changes nothing.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitTry(TryTree t, Void v) {
                    if (!t.getCatches().isEmpty()) {
                        CatchTree c = t.getCatches().get(t.getCatches().size() - 1);
                        List<? extends StatementTree> body = c.getBlock().getStatements();
                        if (body.size() == 1 && body.get(0) instanceof ThrowTree th
                                && Scan.strip(th.getExpression()) instanceof IdentifierTree i
                                && i.getName().contentEquals(c.getParameter().getName())
                                && !s.hasComment(c.getBlock())) {
                            s.report(th, "Caught exception '" + i.getName() + "' is immediately rethrown");
                        }
                    }
                    return super.visitTry(t, v);
                }
            }.run();
        }
    }

    static final class TrivialIf extends Inspection {
        TrivialIf() {
            super("TrivialIf", "complexity", Types.NONE,
                    "`if (c) return true; else return false;` (or `return false` after the `if`), and the same with an"
                            + " assignment, which is `return c;`. The fix rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBlock(BlockTree b, Void v) {
                    List<? extends StatementTree> st = b.getStatements();
                    for (int k = 0; k < st.size(); k++) {
                        if (!(st.get(k) instanceof IfTree i)) continue;
                        StatementTree otherwise = i.getElseStatement();
                        Tree end = i;
                        if (otherwise == null && k + 1 < st.size() && returnsBoolean(single(i.getThenStatement()))) {
                            otherwise = st.get(k + 1);
                            end = otherwise;
                        }
                        Boolean a = returned(single(i.getThenStatement())), c = returned(single(otherwise));
                        if (a != null && c != null && a != c) {
                            String cond = s.text(Scan.strip(i.getCondition()));
                            String repl = "return " + (a ? cond : "!" + parenthesized(i.getCondition(), cond)) + ";";
                            s.report(s.start(i), s.start(i) + 2, "'if' statement can be simplified",
                                    Fix.replace("Simplify", s.start(i), s.end(end), repl));
                        }
                    }
                    return super.visitBlock(b, v);
                }

                private String parenthesized(ExpressionTree e, String text) {
                    ExpressionTree x = Scan.strip(e);
                    return x instanceof IdentifierTree || x instanceof com.sun.source.tree.MethodInvocationTree
                            || x instanceof com.sun.source.tree.MemberSelectTree ? text : "(" + text + ")";
                }

                private boolean returnsBoolean(StatementTree t) {
                    return returned(t) != null;
                }

                private Boolean returned(StatementTree t) {
                    return t instanceof ReturnTree r && r.getExpression() != null ? Scan.bool(r.getExpression()) : null;
                }

                private StatementTree single(StatementTree t) {
                    List<? extends StatementTree> l = Scan.statements(t);
                    return l.size() == 1 ? l.get(0) : null;
                }
            }.run();
        }
    }

    static final class IfStatementWithIdenticalBranches extends Inspection {
        IfStatementWithIdenticalBranches() {
            super("IfStatementWithIdenticalBranches", "complexity", Types.NONE,
                    "An `if` whose `then` and `else` branches are the same code.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitIf(IfTree i, Void v) {
                    if (i.getElseStatement() != null && sameStatements(i.getThenStatement(), i.getElseStatement())) {
                        s.report(s.start(i), s.start(i) + 2, "'if' statement has identical branches", null);
                    }
                    return super.visitIf(i, v);
                }
            }.run();
        }
    }

    static boolean sameStatements(StatementTree a, StatementTree b) {
        List<? extends StatementTree> x = Scan.statements(a), y = Scan.statements(b);
        if (x.size() != y.size() || x.isEmpty()) return false;
        for (int k = 0; k < x.size(); k++) if (!Scan.same(x.get(k), y.get(k))) return false;
        return true;
    }

    static final class DuplicateCondition extends Inspection {
        DuplicateCondition() {
            super("DuplicateCondition", "suspicious", Types.NONE,
                    "A condition that appears twice in an `if` ... `else if` chain, or twice in a chain of `&&` or `||`;"
                            + " the second time it decides nothing.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitIf(IfTree i, Void v) {
                    if (!(parent() instanceof IfTree p && p.getElseStatement() == i)) {
                        List<ExpressionTree> seen = new ArrayList<>();
                        for (StatementTree t = i; t instanceof IfTree x; t = x.getElseStatement()) {
                            ExpressionTree c = Scan.strip(x.getCondition());
                            if (Scan.pure(c) && seen.stream().anyMatch(e -> Scan.same(e, c))) {
                                s.report(c, "Duplicate condition '" + s.text(c) + "'");
                            }
                            seen.add(c);
                        }
                    }
                    return super.visitIf(i, v);
                }

                @Override
                public Void visitBinary(com.sun.source.tree.BinaryTree b, Void v) {
                    Tree.Kind k = b.getKind();
                    if ((k == Tree.Kind.CONDITIONAL_AND || k == Tree.Kind.CONDITIONAL_OR)
                            && !(parent() instanceof com.sun.source.tree.BinaryTree p && p.getKind() == k)) {
                        List<ExpressionTree> ops = new ArrayList<>();
                        flatten(b, k, ops);
                        for (int x = 1; x < ops.size(); x++) {
                            ExpressionTree e = ops.get(x);
                            if (Scan.pure(e) && ops.subList(0, x).stream().anyMatch(o -> Scan.same(o, e))) {
                                s.report(e, "Duplicate condition '" + s.text(e) + "'");
                            }
                        }
                    }
                    return super.visitBinary(b, v);
                }

                private void flatten(ExpressionTree e, Tree.Kind k, List<ExpressionTree> out) {
                    ExpressionTree x = Scan.strip(e);
                    if (x instanceof com.sun.source.tree.BinaryTree b && b.getKind() == k) {
                        flatten(b.getLeftOperand(), k, out);
                        flatten(b.getRightOperand(), k, out);
                    } else {
                        out.add(x);
                    }
                }
            }.run();
        }
    }

    static int labels(SwitchTree t) {
        int n = 0;
        for (CaseTree c : t.getCases()) if (!Flow.isDefault(c)) n += Math.max(1, c.getLabels().size());
        return n;
    }

    static final class SwitchStatementWithTooFewBranches extends Inspection {
        SwitchStatementWithTooFewBranches() {
            super("SwitchStatementWithTooFewBranches", "style", Types.NONE,
                    "A `switch` statement with fewer than two `case` labels, which reads better as an `if`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitSwitch(SwitchTree t, Void v) {
                    boolean patterns = t.getCases().stream()
                            .flatMap(c -> c.getLabels().stream())
                            .anyMatch(l -> l instanceof com.sun.source.tree.PatternCaseLabelTree);
                    if (!t.getCases().isEmpty() && labels(t) < 2 && !patterns) {
                        s.report(s.start(t), s.start(t) + 6, "'switch' has too few branches", null);
                    }
                    return super.visitSwitch(t, v);
                }
            }.run();
        }
    }

    static final class DefaultNotLastCaseInSwitch extends Inspection {
        DefaultNotLastCaseInSwitch() {
            super("DefaultNotLastCaseInSwitch", "style", Types.NONE,
                    "A `default` that is not the last branch of its `switch`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitSwitch(SwitchTree t, Void v) {
                    check(t.getCases());
                    return super.visitSwitch(t, v);
                }

                @Override
                public Void visitSwitchExpression(SwitchExpressionTree t, Void v) {
                    check(t.getCases());
                    return super.visitSwitchExpression(t, v);
                }

                private void check(List<? extends CaseTree> cases) {
                    for (int k = 0; k < cases.size() - 1; k++) {
                        CaseTree c = cases.get(k);
                        if (Flow.isDefault(c) && c.getLabels().size() == 1) {
                            s.report(s.start(c), s.start(c) + 7, "'default' is not the last branch of the 'switch'", null);
                        }
                    }
                }
            }.run();
        }
    }

    static final class TextLabelInSwitchStatement extends Inspection {
        TextLabelInSwitchStatement() {
            super("TextLabelInSwitchStatement", "suspicious", Types.NONE,
                    "A label among the statements of a `switch` case, `case 1: foo: ...`, usually a mistyped `case`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitCase(CaseTree c, Void v) {
                    if (c.getStatements() != null) {
                        for (StatementTree t : c.getStatements()) {
                            if (t instanceof LabeledStatementTree l) {
                                s.report(s.start(l), s.start(l) + l.getLabel().length(),
                                        "Label '" + l.getLabel() + "' in a 'switch'", null);
                            }
                        }
                    }
                    return super.visitCase(c, v);
                }
            }.run();
        }
    }

    static final class DuplicateBranchesInSwitch extends Inspection {
        DuplicateBranchesInSwitch() {
            super("DuplicateBranchesInSwitch", "complexity", Types.NONE,
                    "Two branches of a `switch` with the same code, which can be merged into one with both labels"
                            + " (not pattern labels, which cannot be merged).");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitSwitch(SwitchTree t, Void v) {
                    check(t.getCases());
                    return super.visitSwitch(t, v);
                }

                @Override
                public Void visitSwitchExpression(SwitchExpressionTree t, Void v) {
                    check(t.getCases());
                    return super.visitSwitchExpression(t, v);
                }

                private void check(List<? extends CaseTree> cases) {
                    List<List<? extends Tree>> bodies = new ArrayList<>();
                    for (CaseTree c : cases) {
                        List<? extends Tree> body = body(c);
                        boolean trivial = body.isEmpty() || body.size() == 1 && body.get(0) instanceof BreakTree
                                || body.stream().allMatch(x -> x instanceof BreakTree || x instanceof ThrowTree t2
                                && t2.getExpression() instanceof com.sun.source.tree.NewClassTree);
                        boolean complete = c.getCaseKind() == CaseTree.CaseKind.RULE
                                || !body.isEmpty() && body.get(body.size() - 1) instanceof StatementTree st && Flow.jumps(st);
                        boolean pattern = c.getLabels().stream().anyMatch(l -> l instanceof com.sun.source.tree.PatternCaseLabelTree);
                        if (!trivial && complete && !Flow.isDefault(c) && !pattern) {
                            for (List<? extends Tree> other : bodies) {
                                if (sameList(other, body)) {
                                    s.report(s.start(c), s.start(c) + 4, "Duplicate branch in 'switch'", null);
                                    break;
                                }
                            }
                        }
                        bodies.add(complete ? body : List.of());
                    }
                }

                private List<? extends Tree> body(CaseTree c) {
                    if (c.getCaseKind() == CaseTree.CaseKind.RULE) {
                        Tree b = c.getBody();
                        return b instanceof BlockTree bl ? bl.getStatements() : b == null ? List.of() : List.of(b);
                    }
                    return c.getStatements() == null ? List.of() : c.getStatements();
                }

                private boolean sameList(List<? extends Tree> a, List<? extends Tree> b) {
                    if (a.size() != b.size() || a.isEmpty()) return false;
                    for (int k = 0; k < a.size(); k++) if (!Scan.same(a.get(k), b.get(k))) return false;
                    return true;
                }
            }.run();
        }
    }

    static final class RedundantLabeledSwitchRuleCodeBlock extends Inspection {
        RedundantLabeledSwitchRuleCodeBlock() {
            super("RedundantLabeledSwitchRuleCodeBlock", "complexity", Types.NONE,
                    "A `case ->` block with a single statement, `case 1 -> { f(); }` or `case 1 -> { yield x; }`, which"
                            + " can be the statement or expression alone. The fix unwraps it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitCase(CaseTree c, Void v) {
                    if (c.getCaseKind() == CaseTree.CaseKind.RULE && c.getBody() instanceof BlockTree b
                            && b.getStatements().size() == 1 && !s.hasComment(b)) {
                        StatementTree only = b.getStatements().get(0);
                        String repl = null;
                        if (only instanceof YieldTree y) repl = s.text(y.getValue()) + ";";
                        else if (only instanceof ExpressionStatementTree || only instanceof ThrowTree)
                            repl = s.text(only);
                        if (y(only) && !(parent() instanceof SwitchExpressionTree)) repl = null;
                        if (repl != null) {
                            s.report(b, "Unnecessary code block in 'case ->'", s.replace("Unwrap", b, repl));
                        }
                    }
                    return super.visitCase(c, v);
                }

                private boolean y(StatementTree t) {
                    return t instanceof YieldTree;
                }
            }.run();
        }
    }

    static final class SuspiciousIndentAfterControlStatement extends Inspection {
        SuspiciousIndentAfterControlStatement() {
            super("SuspiciousIndentAfterControlStatement", "suspicious", Types.NONE,
                    "A statement indented as if it were in the body of the `if` or loop before it, which has no braces:"
                            + " only the first statement is the body.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBlock(BlockTree b, Void v) {
                    List<? extends StatementTree> st = b.getStatements();
                    for (int k = 0; k + 1 < st.size(); k++) {
                        StatementTree t = st.get(k), body = bodyOf(t);
                        if (body == null || body instanceof BlockTree || body instanceof EmptyStatementTree) continue;
                        int ownerCol = col(s.start(t)), bodyCol = col(s.start(body)), next = s.start(st.get(k + 1));
                        boolean ownLine = lineStart(s.start(body)) && lineStart(next);
                        if (ownLine && bodyCol > ownerCol && col(next) == bodyCol) {
                            s.report(st.get(k + 1), "Suspicious indentation after the "
                                    + (t instanceof IfTree ? "'if'" : "loop") + ": the statement is not in its body");
                        }
                    }
                    return super.visitBlock(b, v);
                }

                private StatementTree bodyOf(StatementTree t) {
                    if (t instanceof IfTree i) return i.getElseStatement() == null ? i.getThenStatement() : null;
                    if (t instanceof DoWhileLoopTree) return null;
                    return Flow.loop(t) ? Flow.body(t) : null;
                }

                private boolean lineStart(int at) {
                    int ls = s.source().lastIndexOf('\n', at - 1) + 1;
                    return s.source().substring(ls, at).isBlank();
                }

                private int col(int at) {
                    return at - (s.source().lastIndexOf('\n', at - 1) + 1);
                }
            }.run();
        }
    }

    static final class UnnecessaryLocalVariable extends Inspection {
        UnnecessaryLocalVariable() {
            super("UnnecessaryLocalVariable", "complexity", Types.NONE,
                    "A local variable that is returned or thrown right after it is declared, `T x = e; return x;`. The"
                            + " fix returns the expression.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBlock(BlockTree b, Void v) {
                    List<? extends StatementTree> st = b.getStatements();
                    for (int k = 0; k + 1 < st.size(); k++) {
                        if (!(st.get(k) instanceof VariableTree var) || var.getInitializer() == null) continue;
                        if (!var.getModifiers().getAnnotations().isEmpty() || s.hasComment(var)) continue;
                        StatementTree next = st.get(k + 1);
                        ExpressionTree used = next instanceof ReturnTree r ? r.getExpression()
                                : next instanceof ThrowTree t ? t.getExpression() : null;
                        if (used != null && Scan.strip(used) instanceof IdentifierTree i
                                && i.getName().contentEquals(var.getName())
                                && !(var.getInitializer() instanceof com.sun.source.tree.NewArrayTree na && na.getType() == null)) {
                            String kw = next instanceof ReturnTree ? "return " : "throw ";
                            s.report(s.start(var), s.end(var), "Local variable '" + var.getName() + "' is redundant",
                                    Fix.replace("Inline the variable", s.start(var), s.end(next),
                                            kw + s.text(var.getInitializer()) + ";"));
                        }
                    }
                    return super.visitBlock(b, v);
                }
            }.run();
        }
    }

    static final class CodeBlock2Expr extends Inspection {
        CodeBlock2Expr() {
            super("CodeBlock2Expr", "style", Types.NONE,
                    "A lambda whose block body is a single `return x;` or expression statement, which can be the"
                            + " expression alone: `x -> { return f(x); }` is `x -> f(x)`. The fix rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitLambdaExpression(LambdaExpressionTree l, Void v) {
                    if (l.getBody() instanceof BlockTree b && b.getStatements().size() == 1 && !s.hasComment(b)) {
                        StatementTree only = b.getStatements().get(0);
                        ExpressionTree e = only instanceof ReturnTree r ? r.getExpression() : Scan.expression(only);
                        if (e != null && !(e instanceof com.sun.source.tree.SwitchExpressionTree)) {
                            s.report(b, "Statement lambda can be an expression lambda",
                                    s.replace("Use an expression lambda", b, s.text(e)));
                        }
                    }
                    return super.visitLambdaExpression(l, v);
                }
            }.run();
        }
    }

    static final class TryWithIdenticalCatches extends Inspection {
        TryWithIdenticalCatches() {
            super("TryWithIdenticalCatches", "complexity", Types.NONE,
                    "Two `catch` blocks with the same code, which can be one multi-catch, `catch (A | B e)`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitTry(TryTree t, Void v) {
                    List<? extends CatchTree> cs = t.getCatches();
                    for (int k = 1; k < cs.size(); k++) {
                        CatchTree c = cs.get(k);
                        if (c.getBlock().getStatements().isEmpty()) continue;
                        for (int j = 0; j < k; j++) {
                            CatchTree o = cs.get(j);
                            if (o.getParameter().getName().contentEquals(c.getParameter().getName())
                                    && sameStatements(o.getBlock(), c.getBlock())) {
                                s.report(c.getParameter(), "'catch' block is identical to an earlier one");
                                break;
                            }
                        }
                    }
                    return super.visitTry(t, v);
                }
            }.run();
        }
    }

    static final class RedundantExplicitClose extends Inspection {
        RedundantExplicitClose() {
            super("RedundantExplicitClose", "complexity", Types.NONE,
                    "A `close()` at the end of a `try`-with-resources block on one of its resources, which the `try`"
                            + " closes anyway. The fix deletes it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitTry(TryTree t, Void v) {
                    List<? extends StatementTree> st = t.getBlock().getStatements();
                    if (!t.getResources().isEmpty() && !st.isEmpty()
                            && Scan.expression(st.get(st.size() - 1)) instanceof com.sun.source.tree.MethodInvocationTree m
                            && Scan.calls(m, "close", 0) && Scan.receiver(m) instanceof IdentifierTree r) {
                        for (Tree res : t.getResources()) {
                            String name = res instanceof VariableTree vt ? vt.getName().toString()
                                    : res instanceof IdentifierTree i ? i.getName().toString() : null;
                            if (r.getName().contentEquals(String.valueOf(name))) {
                                s.report(m, "Redundant 'close()' of a resource",
                                        deleteStatement(s, "Delete the call", st.get(st.size() - 1)));
                            }
                        }
                    }
                    return super.visitTry(t, v);
                }
            }.run();
        }
    }

    static final class InfiniteRecursion extends Inspection {
        InfiniteRecursion() {
            super("InfiniteRecursion", "correctness", Types.OPTIONAL,
                    "A method whose first statement calls the method itself, so it can only end with a"
                            + " `StackOverflowError`. Without types, only calls that pass the parameters on unchanged"
                            + " are reported.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    if (m.getBody() != null && !m.getBody().getStatements().isEmpty()) {
                        StatementTree first = m.getBody().getStatements().get(0);
                        ExpressionTree e = first instanceof ReturnTree r ? r.getExpression() : Scan.expression(first);
                        if (e instanceof com.sun.source.tree.MethodInvocationTree call && self(m, call)) {
                            s.report(s.start(call), s.end(call), "Method '" + m.getName() + "' recurses infinitely", null);
                        }
                    }
                    return super.visitMethod(m, v);
                }

                private boolean self(MethodTree m, com.sun.source.tree.MethodInvocationTree call) {
                    if (!Scan.name(call).contentEquals(m.getName())) return false;
                    ExpressionTree r = Scan.receiver(call);
                    if (r != null && !(r instanceof IdentifierTree i && i.getName().contentEquals("this")))
                        return false;
                    if (call.getArguments().size() != m.getParameters().size()) return false;
                    if (s.typed()) {
                        var callee = s.element(call);
                        var self = s.element(m);
                        return callee != null && callee.equals(self);
                    }
                    for (int k = 0; k < call.getArguments().size(); k++) {
                        if (!(call.getArguments().get(k) instanceof IdentifierTree a)
                                || !a.getName().contentEquals(m.getParameters().get(k).getName())) {
                            return false;
                        }
                    }
                    return true;
                }
            }.run();
        }
    }
}
