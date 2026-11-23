package dev.jpm.format.rules;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Lint;

import java.util.List;
import java.util.Set;
import javax.lang.model.element.Modifier;

/**
 * Local variables never read or written after their declaration, and parameters of private methods that the
 * body never uses. Reported only: removing a variable could drop an initializer's side effects. Names
 * starting with {@code ignored} or {@code unused}, and {@code _}, are skipped. Suppress with {@code unused}.
 */
public final class UnusedLocalVariable implements Lint {
    @Override
    public String id() {
        return "UnusedLocalVariable";
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
    public Set<String> aliases() {
        return Set.of("unused", "UnusedDeclaration");
    }

    static boolean skipName(String n) {
        return n.equals("_") || n.startsWith("ignored") || n.startsWith("unused");
    }

    @Override
    public void check(Context ctx) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitBlock(BlockTree b, Void v) {
                List<? extends StatementTree> st = b.getStatements();
                for (int k = 0; k < st.size(); k++) {
                    if (!(st.get(k) instanceof VariableTree var)) continue;
                    String name = var.getName().toString();
                    if (skipName(name)) continue;
                    boolean used = false;
                    for (int j = k + 1; j < st.size() && !used; j++) used = Trees2.usesIdentifier(st.get(j), name);
                    if (!used) report(ctx, var, "Local variable '" + name + "' is never used");
                }
                return super.visitBlock(b, v);
            }

            @Override
            public Void visitForLoop(ForLoopTree f, Void v) {
                for (StatementTree init : f.getInitializer()) {
                    if (!(init instanceof VariableTree var)
                            || skipName(var.getName().toString())) continue;
                    String n = var.getName().toString();
                    boolean used = f.getInitializer().stream().anyMatch(i -> i != init && Trees2.usesIdentifier(i, n))
                            || (f.getCondition() != null && Trees2.usesIdentifier(f.getCondition(), n))
                            || f.getUpdate().stream().anyMatch(u -> Trees2.usesIdentifier(u, n))
                            || Trees2.usesIdentifier(f.getStatement(), n);
                    if (!used) report(ctx, var, "Local variable '" + n + "' is never used");
                }
                return super.visitForLoop(f, v);
            }

            @Override
            public Void visitMethod(MethodTree m, Void v) {
                if (m.getBody() != null
                        && m.getModifiers().getFlags().contains(Modifier.PRIVATE)
                        && !Trees2.hasAnnotation(m.getModifiers(), "Override")) {
                    for (VariableTree p : m.getParameters()) {
                        String n = p.getName().toString();
                        if (!skipName(n) && !Trees2.usesIdentifier(m.getBody(), n)) {
                            report(ctx, p, "Parameter '" + n + "' is never used");
                        }
                    }
                }
                return super.visitMethod(m, v);
            }
        }.scan(ctx.tree(), null);
    }

    /**
     * Reports at the variable's name.
     */
    static void report(Context ctx, VariableTree var, String message) {
        String src = ctx.source();
        String name = var.getName().toString();
        int from = ctx.start(var);
        if (var.getType() != null && ctx.end(var.getType()) > from) from = ctx.end(var.getType());
        int to = ctx.end(var) > from ? ctx.end(var) : src.length();
        int at = indexOfWord(src, name, from, to);
        if (at < 0) at = ctx.start(var);
        ctx.report(at, at + name.length(), message, null);
    }

    private static int indexOfWord(String src, String w, int from, int to) {
        int i = from;
        while ((i = src.indexOf(w, i)) >= 0 && i < to) {
            boolean before = i == 0 || !Character.isJavaIdentifierPart(src.charAt(i - 1));
            boolean after =
                    i + w.length() >= src.length() || !Character.isJavaIdentifierPart(src.charAt(i + w.length()));
            if (before && after) return i;
            i += w.length();
        }
        return -1;
    }
}
