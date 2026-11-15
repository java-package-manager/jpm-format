package dev.jpm.format.rules;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.Lint;
import dev.jpm.format.api.TypeInfo;

/**
 * Strings compared with {@code ==} or {@code !=}, which compares identity, not content. Needs types. The fix,
 * {@code a.equals(b)}, is offered but not applied: it throws if {@code a} is null.
 */
public final class StringEquality implements Lint {
    @Override
    public String id() {
        return "StringEquality";
    }

    @Override
    public String category() {
        return "correctness";
    }

    @Override
    public String docs() {
        return Builtins.docs(id());
    }

    @Override
    public boolean typeAware() {
        return true;
    }

    @Override
    public void check(Context ctx) {
        TypeInfo ti = ctx.types().orElse(null);
        if (ti == null) return;
        SourcePositions pos = ti.trees().getSourcePositions();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitBinary(BinaryTree b, Void v) {
                if ((b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO)
                        && !isNull(b.getLeftOperand())
                        && !isNull(b.getRightOperand())
                        && ti.is(ti.typeOf(b.getLeftOperand()), "java.lang.String")
                        && ti.is(ti.typeOf(b.getRightOperand()), "java.lang.String")) {
                    int s = (int) pos.getStartPosition(ti.tree(), b), e = (int) pos.getEndPosition(ti.tree(), b);
                    String l = text(ctx, pos, ti, b.getLeftOperand()), r = text(ctx, pos, ti, b.getRightOperand());
                    if (!Trees2.primary(b.getLeftOperand())) l = "(" + l + ")";
                    String repl = (b.getKind() == Tree.Kind.NOT_EQUAL_TO ? "!" : "") + l + ".equals(" + r + ")";
                    ctx.report(
                            s,
                            e,
                            "String compared with '" + (b.getKind() == Tree.Kind.EQUAL_TO ? "==" : "!=")
                                    + "' instead of equals()",
                            Fix.replace("Use equals()", s, e, repl));
                }
                return super.visitBinary(b, v);
            }
        }.scan(ti.tree(), null);
    }

    private static boolean isNull(ExpressionTree e) {
        return e instanceof LiteralTree l && l.getKind() == Tree.Kind.NULL_LITERAL;
    }

    private static String text(Context ctx, SourcePositions pos, TypeInfo ti, Tree t) {
        return ctx.source().substring((int) pos.getStartPosition(ti.tree(), t), (int) pos.getEndPosition(ti.tree(), t));
    }
}
