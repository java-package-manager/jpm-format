package dev.jpm.format.rules;

import com.sun.source.tree.MethodTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.Lint;
import dev.jpm.format.api.TypeInfo;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A method that overrides or implements a supertype's method without {@code @Override}. Needs types. The fix,
 * adding {@code @Override}, is offered but not applied, since it needs the classpath to be right.
 */
public final class MissingOverrideAnnotation implements Lint {
    @Override
    public String id() {
        return "MissingOverrideAnnotation";
    }

    @Override
    public String category() {
        return "style";
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
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree m, Void v) {
                Set<Modifier> flags = m.getModifiers().getFlags();
                if (!m.getName().contentEquals("<init>")
                        && !flags.contains(Modifier.STATIC)
                        && !flags.contains(Modifier.PRIVATE)
                        && !Trees2.hasAnnotation(m.getModifiers(), "Override")) {
                    Element el = ti.trees().getElement(getCurrentPath());
                    if (el instanceof ExecutableElement ee && el.getKind() == ElementKind.METHOD && overrides(ti, ee)) {
                        int start = (int) pos.getStartPosition(ti.tree(), m);
                        int[] name = nameRange(ctx.source(), m.getName().toString(), start);
                        String indent = Trees2.indentAt(ctx.source(), start);
                        String insert = indent == null ? "@Override " : "@Override\n" + indent;
                        ctx.report(
                                name[0],
                                name[1],
                                "Method '" + m.getName() + "' overrides a supertype method but has no @Override",
                                Fix.insert("Add @Override", start, insert));
                    }
                }
                return super.visitMethod(m, v);
            }
        }.scan(ti.tree(), null);
    }

    static int[] nameRange(String src, String name, int from) {
        Matcher mt = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(src);
        if (mt.find(Math.max(0, from))) return new int[]{mt.start(), mt.start() + name.length()};
        return new int[]{from, from};
    }

    private static boolean overrides(TypeInfo ti, ExecutableElement m) {
        if (!(m.getEnclosingElement() instanceof TypeElement owner)) return false;
        Deque<TypeMirror> todo = new ArrayDeque<>(ti.types().directSupertypes(owner.asType()));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            TypeMirror t = todo.pop();
            if (t.getKind() != TypeKind.DECLARED) continue;
            TypeElement te = (TypeElement) ((DeclaredType) t).asElement();
            if (!seen.add(te.getQualifiedName().toString())) continue;
            for (ExecutableElement other : ElementFilter.methodsIn(te.getEnclosedElements())) {
                if (other.getSimpleName().equals(m.getSimpleName())
                        && ti.elements().overrides(m, other, owner)) return true;
            }
            todo.addAll(ti.types().directSupertypes(t));
        }
        return false;
    }
}
