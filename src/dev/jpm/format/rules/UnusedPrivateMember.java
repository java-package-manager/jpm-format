package dev.jpm.format.rules;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Lint;

import java.util.Set;
import javax.lang.model.element.Modifier;

/**
 * Private fields and methods nothing in the file refers to. Reported only. Annotated members (frameworks
 * may use them), serialization members and files using Lombok are skipped. Suppress with {@code unused}.
 */
public final class UnusedPrivateMember implements Lint {
    private static final Set<String> SERIAL = Set.of(
            "serialVersionUID",
            "serialPersistentFields",
            "writeObject",
            "readObject",
            "readObjectNoData",
            "writeReplace",
            "readResolve");

    @Override
    public String id() {
        return "UnusedPrivateMember";
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

    @Override
    public void check(Context ctx) {
        CompilationUnitTree cu = ctx.tree();
        for (ImportTree i : cu.getImports())
            if (i.getQualifiedIdentifier().toString().startsWith("lombok.")) return;
        Set<String> names = Trees2.names(cu);
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree c, Void v) {
                boolean record = c.getKind() == Tree.Kind.RECORD;
                for (Tree m : c.getMembers()) {
                    // a record's components appear as private fields; they are its API, not unused state
                    if (record
                            && m instanceof VariableTree rc
                            && !rc.getModifiers().getFlags().contains(Modifier.STATIC)) continue;
                    if (m instanceof VariableTree f
                            && f.getModifiers().getFlags().contains(Modifier.PRIVATE)
                            && f.getModifiers().getAnnotations().isEmpty()) {
                        String n = f.getName().toString();
                        if (!SERIAL.contains(n) && !UnusedLocalVariable.skipName(n) && !names.contains(n)) {
                            UnusedLocalVariable.report(ctx, f, "Private field '" + n + "' is never used");
                        }
                    } else if (m instanceof MethodTree mt
                            && mt.getModifiers().getFlags().contains(Modifier.PRIVATE)
                            && mt.getModifiers().getAnnotations().isEmpty()
                            && !mt.getName().contentEquals("<init>")) {
                        String n = mt.getName().toString();
                        if (!SERIAL.contains(n) && !names.contains(n)) {
                            int[] r = MissingOverrideAnnotation.nameRange(ctx.source(), n, ctx.start(mt));
                            ctx.report(r[0], r[1], "Private method '" + n + "()' is never used", null);
                        }
                    }
                }
                return super.visitClass(c, v);
            }
        }.scan(cu, null);
    }
}
