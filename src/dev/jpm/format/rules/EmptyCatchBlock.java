package dev.jpm.format.rules;

import com.sun.source.tree.CatchTree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.Comments;
import dev.jpm.format.FileContext;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Lint;

import java.util.Set;

/**
 * A catch block with nothing in it, which hides the exception. Reported only, with no fix. A comment inside
 * the block, or naming the parameter {@code ignored}, {@code ignore}, {@code expected} or {@code _}, marks it
 * as deliberate, as in IntelliJ.
 */
public final class EmptyCatchBlock implements Lint {
    private static final Set<String> DELIBERATE = Set.of("ignored", "ignore", "expected", "_");

    @Override
    public String id() {
        return "EmptyCatchBlock";
    }

    @Override
    public String category() {
        return "suspicious";
    }

    @Override
    public String docs() {
        return Builtins.docs(id());
    }

    @Override
    public void check(Context ctx) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitCatch(CatchTree c, Void v) {
                if (c.getBlock().getStatements().isEmpty()
                        && !DELIBERATE.contains(c.getParameter().getName().toString())) {
                    int s = ctx.start(c.getBlock()), e = ctx.end(c.getBlock());
                    boolean commented = false;
                    for (Comments.Comment cm : ((FileContext) ctx).parsed().comments()) {
                        if (cm.start() > s && cm.end() < e) commented = true;
                    }
                    if (!commented) ctx.report(c, "Empty catch block hides the exception");
                }
                return super.visitCatch(c, v);
            }
        }.scan(ctx.tree(), null);
    }
}
