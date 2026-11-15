package dev.jpm.format.rules;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.Lint;

/**
 * Comparisons with boolean literals that can be simplified: {@code x == true} is {@code x}, {@code x != true}
 * is {@code !x}, {@code x && true} is {@code x}, {@code x || false} is {@code x}. The fix is offered, not
 * applied: with a {@code Boolean}, the simplified form has a different static type.
 */
public final class PointlessBooleanExpression implements Lint {
    @Override
    public String id() {
        return "PointlessBooleanExpression";
    }

    @Override
    public String category() {
        return "complexity";
    }

    @Override
    public String docs() {
        return Builtins.docs(id());
    }

    @Override
    public void check(Context ctx) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitBinary(BinaryTree b, Void v) {
                Boolean lit = literal(b.getRightOperand());
                ExpressionTree other = b.getLeftOperand();
                if (lit == null) {
                    lit = literal(b.getLeftOperand());
                    other = b.getRightOperand();
                }
                if (lit != null && literal(other) == null) {
                    String x = Trees2.text(ctx, other);
                    String repl = switch (b.getKind()) {
                        case EQUAL_TO -> lit ? x : "!" + Trees2.paren(ctx, other);
                        case NOT_EQUAL_TO -> lit ? "!" + Trees2.paren(ctx, other) : x;
                        case CONDITIONAL_AND -> lit ? x : null;
                        case CONDITIONAL_OR -> lit ? null : x;
                        default -> null;
                    };
                    if (repl != null) {
                        ctx.report(
                                b,
                                "'" + Trees2.text(ctx, b) + "' can be simplified to '" + repl + "'",
                                Fix.replace("Simplify", ctx.start(b), ctx.end(b), repl));
                    }
                }
                return super.visitBinary(b, v);
            }
        }.scan(ctx.tree(), null);
    }

    private static Boolean literal(ExpressionTree e) {
        return e instanceof LiteralTree l && l.getKind() == Tree.Kind.BOOLEAN_LITERAL ? (Boolean) l.getValue() : null;
    }
}
