package dev.jpm.format.inspections;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.EmptyStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.Comments;
import dev.jpm.format.api.Fix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;

/**
 * IntelliJ's inspections about declarations: modifiers, literals, classes, methods and fields.
 */
final class Declarations {
    private Declarations() {
    }

    static List<Inspection> all() {
        return List.of(
                new UnnecessarySemicolon(),
                new CStyleArrayDeclaration(),
                new LongLiteralsEndingWithLowercaseL(),
                new OctalLiteral(),
                new ExtendsObject(),
                new TypeParameterExtendsObject(),
                new FinalPrivateMethod(),
                new FinalStaticMethod(),
                new FinalMethodInFinalClass(),
                new ProtectedMemberInFinalClass(),
                new MethodNameSameAsClassName(),
                new NonFinalFieldInEnum(),
                new SerialVersionUIDNotStaticFinal(),
                new NonSerializableWithSerialVersionUIDField(),
                new FinalizeNotProtected(),
                new EmptyInitializer(),
                new ConfusingMainMethod(),
                new CloneableImplementsClone(),
                new EqualsWhichDoesntCheckParameterClass(),
                new IteratorHasNextCallsIteratorNext(),
                new ComparatorMethodParameterNotUsed(),
                new RedundantMethodOverride(),
                new RedundantRecordConstructor(),
                new WrongPackageStatement(),
                new PackageInfoWithoutPackage(),
                new NewClassNamingConvention(),
                new DuplicateThrows(),
                new FieldMayBeFinal(),
                new OptionalUsedAsFieldOrParameterType());
    }

    /**
     * A fix that removes {@code modifier} from {@code mods}, or null if it is not written there.
     */
    static Fix removeModifier(Scan s, ModifiersTree mods, String modifier) {
        int start = s.start(mods), end = s.end(mods);
        if (start < 0 || end <= start) return null;
        Matcher m = Pattern.compile("\\b" + modifier + "\\b\\s*").matcher(s.source()).region(start, end);
        for (AnnotationTree a : mods.getAnnotations()) {
            if (m.find() && m.start() >= s.start(a) && m.start() < s.end(a)) return null;
            m.reset().region(start, end);
        }
        if (!m.find()) return null;
        int to = m.end();
        if (to >= end) to = m.end();
        return Fix.delete("Remove '" + modifier + "'", m.start(), to);
    }

    /**
     * The range of {@code modifier} in {@code mods}, or the modifiers' range.
     */
    static int[] modifierRange(Scan s, ModifiersTree mods, String modifier) {
        Matcher m = Pattern.compile("\\b" + modifier + "\\b").matcher(s.source()).region(s.start(mods), Math.max(s.start(mods), s.end(mods)));
        return m.find() ? new int[]{m.start(), m.end()} : new int[]{s.start(mods), s.end(mods)};
    }

    static void reportModifier(Scan s, ModifiersTree mods, String modifier, String message) {
        int[] r = modifierRange(s, mods, modifier);
        s.report(r[0], r[1], message, removeModifier(s, mods, modifier));
    }

    static boolean isVoid(Tree type) {
        return type instanceof PrimitiveTypeTree p && p.getPrimitiveTypeKind() == TypeKind.VOID;
    }

    static final class UnnecessarySemicolon extends Inspection {
        UnnecessarySemicolon() {
            super("UnnecessarySemicolon", "complexity", Types.NONE,
                    "A `;` that ends nothing: between members or imports, after a class, a lone `;` statement in a block,"
                            + " or after the last resource of a `try`. The fix deletes it.");
        }

        @Override
        protected void run(Scan s) {
            String src = s.source();
            List<int[]> comments = new ArrayList<>();
            for (Comments.Comment c : s.comments()) comments.add(new int[]{c.start(), c.end()});
            CompilationUnitTree cu = s.root();
            int from = cu.getPackage() != null ? s.end(cu.getPackage()) : 0;
            List<Tree> top = new ArrayList<>();
            if (cu.getModule() != null) top.add(cu.getModule());
            top.addAll(cu.getImports());
            top.addAll(cu.getTypeDecls());
            for (Tree t : top) {
                if (t instanceof EmptyStatementTree) continue;
                gap(s, from, s.start(t), comments);
                from = Math.max(from, s.end(t));
            }
            gap(s, from, src.length(), comments);
            new Visitor(s) {
                @Override
                public Void visitClass(ClassTree c, Void v) {
                    int open = bodyStart(c);
                    int close = s.end(c) - 1;
                    if (open >= 0 && close > open) {
                        int at = open + 1;
                        boolean isEnum = c.getKind() == Tree.Kind.ENUM;
                        List<Tree> members = new ArrayList<>(c.getMembers());
                        boolean seenNonConstant = false;
                        for (Tree m : members) {
                            if (s.start(m) < 0) continue;
                            if (s.start(m) < at) {
                                at = Math.max(at, s.end(m));
                                continue;
                            }
                            boolean constant = isEnum && m instanceof VariableTree vt && isEnumConstant(s, vt);
                            if (!(isEnum && !seenNonConstant)) gap(s, at, s.start(m), comments);
                            if (isEnum && !constant && !seenNonConstant) {
                                semicolonsAfterFirst(s, at, s.start(m), comments);
                                seenNonConstant = true;
                            }
                            at = Math.max(at, s.end(m));
                        }
                        gap(s, at, close, comments);
                    }
                    return super.visitClass(c, v);
                }

                /**
                 * The offset of the class body's {@code {}: after the name, and after a record's components.
                 */
                private int bodyStart(ClassTree c) {
                    if (c.getSimpleName().isEmpty()) return src.indexOf('{', s.start(c));
                    Matcher name = Pattern.compile("\\b" + Pattern.quote(c.getSimpleName().toString()) + "\\b")
                            .matcher(src).region(Math.max(0, s.start(c)), src.length());
                    int at = name.find() ? name.end() : s.start(c);
                    if (c.getKind() == Tree.Kind.RECORD) {
                        int depth = 0;
                        for (int k = src.indexOf('(', at); k >= 0 && k < src.length(); k++) {
                            if (src.charAt(k) == '(') depth++;
                            else if (src.charAt(k) == ')' && --depth == 0) {
                                at = k;
                                break;
                            }
                        }
                    }
                    return src.indexOf('{', at);
                }

                @Override
                public Void visitBlock(BlockTree b, Void v) {
                    for (StatementTree t : b.getStatements()) {
                        if (t instanceof EmptyStatementTree) {
                            s.report(t, "Unnecessary semicolon", ControlFlow.deleteStatement(s, "Delete it", t));
                        }
                    }
                    return super.visitBlock(b, v);
                }

                @Override
                public Void visitTry(com.sun.source.tree.TryTree t, Void v) {
                    if (!t.getResources().isEmpty()) {
                        int last = s.end(t.getResources().get(t.getResources().size() - 1));
                        int close = src.indexOf(')', last);
                        int semi = src.indexOf(';', last);
                        if (semi >= 0 && semi < close && outside(semi, comments)) {
                            s.report(semi, semi + 1, "Unnecessary semicolon", Fix.delete("Delete it", semi, semi + 1));
                        }
                    }
                    return super.visitTry(t, v);
                }
            }.run();
        }

        static boolean isEnumConstant(Scan s, VariableTree v) {
            return v instanceof com.sun.tools.javac.tree.JCTree.JCVariableDecl d
                    && (d.mods.flags & com.sun.tools.javac.code.Flags.ENUM) != 0;
        }

        private static boolean outside(int at, List<int[]> comments) {
            for (int[] c : comments) if (at >= c[0] && at < c[1]) return false;
            return true;
        }

        /**
         * Reports every {@code ;} in {@code [from, to)} that is not in a comment.
         */
        private static void gap(Scan s, int from, int to, List<int[]> comments) {
            String src = s.source();
            for (int k = Math.max(0, from); k < to && k < src.length(); k++) {
                if (src.charAt(k) == ';' && outside(k, comments)) {
                    s.report(k, k + 1, "Unnecessary semicolon", Fix.delete("Delete it", k, k + 1));
                }
            }
        }

        /**
         * In an enum, the first {@code ;} after the constants ends them; any after it is unnecessary.
         */
        private static void semicolonsAfterFirst(Scan s, int from, int to, List<int[]> comments) {
            String src = s.source();
            boolean first = true;
            for (int k = from; k < to; k++) {
                if (src.charAt(k) == ';' && outside(k, comments)) {
                    if (!first) s.report(k, k + 1, "Unnecessary semicolon", Fix.delete("Delete it", k, k + 1));
                    first = false;
                }
            }
        }
    }

    static final class CStyleArrayDeclaration extends Inspection {
        private static final Pattern DIMS = Pattern.compile("\\G\\s*((?:\\[\\s*\\]\\s*)+)");

        CStyleArrayDeclaration() {
            super("CStyleArrayDeclaration", "style", Types.NONE,
                    "Array brackets after the name, `int a[]` or `int f()[]`, instead of after the type, `int[] a`. The"
                            + " fix moves them.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitVariable(VariableTree v, Void x) {
                    if (v.getType() != null && v.getType().getKind() == Tree.Kind.ARRAY_TYPE && !v.getName().isEmpty()
                            && s.start(v.getType()) >= 0 && !(parent() instanceof LambdaExpressionTree)) {
                        Tree element = v.getType();
                        while (element instanceof com.sun.source.tree.ArrayTypeTree at) element = at.getType();
                        String src = s.source();
                        Matcher name = Pattern.compile("\\b" + Pattern.quote(v.getName().toString()) + "\\b")
                                .matcher(src).region(s.end(element), src.length());
                        if (name.find()) {
                            int after = name.end();
                            Matcher m = DIMS.matcher(src);
                            if (m.find(after) && m.start() == after && !m.group(1).isBlank()) {
                                String brackets = m.group(1).replaceAll("\\s", "");
                                String between = src.substring(s.end(element), name.start());
                                int end = after + m.group(0).stripTrailing().length();
                                Fix fix = between.contains(",") ? null : new Fix("Use a Java-style array declaration",
                                        List.of(new Fix.Edit(s.end(element), s.end(element), brackets),
                                                new Fix.Edit(after, end, "")));
                                s.report(name.start(), end, "C-style array declaration of '" + v.getName() + "'", fix);
                            }
                        }
                    }
                    return super.visitVariable(v, x);
                }

                @Override
                public Void visitMethod(MethodTree m, Void x) {
                    if (m.getReturnType() != null && m.getReturnType().getKind() == Tree.Kind.ARRAY_TYPE
                            && m.getBody() != null) {
                        String src = s.source();
                        int close = src.lastIndexOf(')', s.start(m.getBody()));
                        Matcher d = DIMS.matcher(src);
                        if (close > 0 && d.find(close + 1) && d.start() == close + 1 && !d.group(1).isBlank()) {
                            s.report(close + 1, close + 1 + d.group(0).stripTrailing().length(),
                                    "C-style array declaration of method '" + m.getName() + "'", null);
                        }
                    }
                    return super.visitMethod(m, x);
                }
            }.run();
        }
    }

    static final class LongLiteralsEndingWithLowercaseL extends Inspection {
        LongLiteralsEndingWithLowercaseL() {
            super("LongLiteralsEndingWithLowercaseL", "style", Types.NONE,
                    "A `long` literal ending in a lowercase `l`, which looks like `1`. The fix makes it `L`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitLiteral(LiteralTree l, Void v) {
                    if (l.getKind() == Tree.Kind.LONG_LITERAL) {
                        int e = s.end(l);
                        if (e > 0 && s.source().charAt(e - 1) == 'l') {
                            s.report(l, "'long' literal '" + s.text(l) + "' ends with lowercase 'l'",
                                    Fix.replace("Use 'L'", e - 1, e, "L"));
                        }
                    }
                    return null;
                }
            }.run();
        }
    }

    static final class OctalLiteral extends Inspection {
        OctalLiteral() {
            super("OctalLiteral", "suspicious", Types.NONE,
                    "An integer literal with a leading zero, `010`, which is octal (8), often by mistake.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitLiteral(LiteralTree l, Void v) {
                    if (l.getKind() == Tree.Kind.INT_LITERAL || l.getKind() == Tree.Kind.LONG_LITERAL) {
                        String t = s.text(l).replace("_", "");
                        if (t.length() > 1 && t.charAt(0) == '0' && Character.isDigit(t.charAt(1))) {
                            s.report(l, "Octal integer '" + s.text(l) + "'");
                        }
                    }
                    return null;
                }
            }.run();
        }
    }

    static final class ExtendsObject extends Inspection {
        ExtendsObject() {
            super("ExtendsObject", "complexity", Types.NONE,
                    "`class A extends Object`, which every class does anyway. The fix removes it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitClass(ClassTree c, Void v) {
                    Tree ext = c.getExtendsClause();
                    if (ext != null && (ext.toString().equals("Object") || ext.toString().equals("java.lang.Object"))) {
                        int kw = s.source().lastIndexOf("extends", s.start(ext));
                        s.report(kw, s.end(ext), "Class explicitly extends 'Object'",
                                Fix.delete("Remove 'extends Object'", kw, s.end(ext) + (s.source().charAt(s.end(ext)) == ' ' ? 1 : 0)));
                    }
                    return super.visitClass(c, v);
                }
            }.run();
        }
    }

    static final class TypeParameterExtendsObject extends Inspection {
        TypeParameterExtendsObject() {
            super("TypeParameterExtendsObject", "complexity", Types.NONE,
                    "A type parameter or wildcard bounded by `Object`, `<T extends Object>`, which every type is. The fix"
                            + " removes the bound.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitTypeParameter(TypeParameterTree t, Void v) {
                    if (t.getBounds().size() == 1 && isObject(t.getBounds().get(0))) {
                        int kw = s.source().lastIndexOf("extends", s.start(t.getBounds().get(0)));
                        int from = s.start(t) + t.getName().length();
                        s.report(kw, s.end(t.getBounds().get(0)), "Type parameter '" + t.getName() + "' explicitly extends 'Object'",
                                Fix.delete("Remove the bound", from, s.end(t.getBounds().get(0))));
                    }
                    return super.visitTypeParameter(t, v);
                }

                @Override
                public Void visitWildcard(com.sun.source.tree.WildcardTree w, Void v) {
                    if (w.getKind() == Tree.Kind.EXTENDS_WILDCARD && isObject(w.getBound())) {
                        s.report(w, "Wildcard explicitly extends 'Object'", s.replace("Remove the bound", w, "?"));
                    }
                    return super.visitWildcard(w, v);
                }

                private boolean isObject(Tree t) {
                    return t != null && (t.toString().equals("Object") || t.toString().equals("java.lang.Object"));
                }
            }.run();
        }
    }

    static final class FinalPrivateMethod extends Inspection {
        FinalPrivateMethod() {
            super("FinalPrivateMethod", "complexity", Types.NONE,
                    "`final` on a `private` method, which cannot be overridden anyway. The fix removes it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    Set<Modifier> f = m.getModifiers().getFlags();
                    if (f.contains(Modifier.PRIVATE) && f.contains(Modifier.FINAL) && !hasSafeVarargs(m)) {
                        reportModifier(s, m.getModifiers(), "final", "'private' method declared 'final'");
                    }
                    return super.visitMethod(m, v);
                }
            }.run();
        }
    }

    static boolean hasSafeVarargs(MethodTree m) {
        return m.getModifiers().getAnnotations().stream().anyMatch(a -> Scan.simpleName(a.getAnnotationType()).equals("SafeVarargs"));
    }

    static final class FinalStaticMethod extends Inspection {
        FinalStaticMethod() {
            super("FinalStaticMethod", "complexity", Types.NONE,
                    "`final` on a `static` method, which cannot be overridden anyway. The fix removes it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    Set<Modifier> f = m.getModifiers().getFlags();
                    if (f.contains(Modifier.STATIC) && f.contains(Modifier.FINAL) && !hasSafeVarargs(m)) {
                        reportModifier(s, m.getModifiers(), "final", "'static' method declared 'final'");
                    }
                    return super.visitMethod(m, v);
                }
            }.run();
        }
    }

    static final class FinalMethodInFinalClass extends Inspection {
        FinalMethodInFinalClass() {
            super("FinalMethodInFinalClass", "complexity", Types.NONE,
                    "`final` on a method of a `final` class, or of an enum or record, which cannot be subclassed. The fix"
                            + " removes it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    if (parent() instanceof ClassTree c && m.getModifiers().getFlags().contains(Modifier.FINAL)
                            && !m.getModifiers().getFlags().contains(Modifier.PRIVATE)
                            && !m.getModifiers().getFlags().contains(Modifier.STATIC) && !hasSafeVarargs(m)
                            && (c.getModifiers().getFlags().contains(Modifier.FINAL) || c.getKind() == Tree.Kind.RECORD)) {
                        reportModifier(s, m.getModifiers(), "final", "Method declared 'final' in a 'final' class");
                    }
                    return super.visitMethod(m, v);
                }
            }.run();
        }
    }

    static final class ProtectedMemberInFinalClass extends Inspection {
        ProtectedMemberInFinalClass() {
            super("ProtectedMemberInFinalClass", "style", Types.NONE,
                    "A `protected` field or method in a `final` class that extends nothing, which no subclass can use:"
                            + " it is package-private in effect.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitClass(ClassTree c, Void v) {
                    if (c.getModifiers().getFlags().contains(Modifier.FINAL) && c.getExtendsClause() == null
                            && c.getImplementsClause().isEmpty()) {
                        for (Tree m : c.getMembers()) {
                            ModifiersTree mods = m instanceof MethodTree mt ? mt.getModifiers()
                                    : m instanceof VariableTree vt ? vt.getModifiers() : null;
                            if (mods != null && mods.getFlags().contains(Modifier.PROTECTED)
                                    && !(m instanceof MethodTree mt2 && mt2.getName().contentEquals("finalize"))
                                    && !(m instanceof MethodTree mt3 && mt3.getName().contentEquals("clone"))) {
                                int[] r = modifierRange(s, mods, "protected");
                                s.report(r[0], r[1], "'protected' member in a 'final' class", null);
                            }
                        }
                    }
                    return super.visitClass(c, v);
                }
            }.run();
        }
    }

    static final class MethodNameSameAsClassName extends Inspection {
        MethodNameSameAsClassName() {
            super("MethodNameSameAsClassName", "suspicious", Types.NONE,
                    "A method named like its class, which looks like a constructor but has a return type.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    if (parent() instanceof ClassTree c && m.getReturnType() != null
                            && m.getName().contentEquals(c.getSimpleName())) {
                        int at = s.source().indexOf(m.getName().toString(), s.end(m.getReturnType()));
                        s.report(at, at + m.getName().length(), "Method '" + m.getName() + "' is named like its class", null);
                    }
                    return super.visitMethod(m, v);
                }
            }.run();
        }
    }

    static final class NonFinalFieldInEnum extends Inspection {
        NonFinalFieldInEnum() {
            super("NonFinalFieldInEnum", "suspicious", Types.NONE,
                    "A field of an enum that is not `final`: enum constants are shared, so their state should not"
                            + " change.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitClass(ClassTree c, Void v) {
                    if (c.getKind() == Tree.Kind.ENUM) {
                        for (Tree m : c.getMembers()) {
                            if (m instanceof VariableTree f && !UnnecessarySemicolon.isEnumConstant(s, f)
                                    && !f.getModifiers().getFlags().contains(Modifier.FINAL)) {
                                int at = s.source().indexOf(f.getName().toString(), s.end(f.getType()));
                                s.report(at, at + f.getName().length(), "Non-final field '" + f.getName() + "' in an enum", null);
                            }
                        }
                    }
                    return super.visitClass(c, v);
                }
            }.run();
        }
    }

    static VariableTree serialVersionUid(ClassTree c) {
        for (Tree m : c.getMembers()) {
            if (m instanceof VariableTree f && f.getName().contentEquals("serialVersionUID")) return f;
        }
        return null;
    }

    static final class SerialVersionUIDNotStaticFinal extends Inspection {
        SerialVersionUIDNotStaticFinal() {
            super("SerialVersionUIDNotStaticFinal", "correctness", Types.NONE,
                    "A `serialVersionUID` field that is not `static final` (or not `long`), which serialization"
                            + " ignores.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitClass(ClassTree c, Void v) {
                    VariableTree f = serialVersionUid(c);
                    if (f != null) {
                        Set<Modifier> m = f.getModifiers().getFlags();
                        boolean isLong = f.getType() instanceof PrimitiveTypeTree p && p.getPrimitiveTypeKind() == TypeKind.LONG;
                        if (!m.contains(Modifier.STATIC) || !m.contains(Modifier.FINAL) || !isLong) {
                            int at = s.source().indexOf("serialVersionUID", s.end(f.getType()));
                            s.report(at, at + 16, "'serialVersionUID' is not declared 'static final long'", null);
                        }
                    }
                    return super.visitClass(c, v);
                }
            }.run();
        }
    }

    static final class NonSerializableWithSerialVersionUIDField extends Inspection {
        NonSerializableWithSerialVersionUIDField() {
            super("NonSerializableWithSerialVersionUIDField", "suspicious", Types.OPTIONAL,
                    "A `serialVersionUID` in a class that is not `Serializable`, where it means nothing. Without types,"
                            + " only classes that extend and implement nothing are reported.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitClass(ClassTree c, Void v) {
                    VariableTree f = serialVersionUid(c);
                    if (f != null && c.getKind() == Tree.Kind.CLASS && !c.getSimpleName().isEmpty()) {
                        boolean notSerializable;
                        if (s.typed()) {
                            var e = s.element(c);
                            notSerializable = e != null && !s.isA(e.asType(), "java.io.Serializable");
                        } else {
                            notSerializable = c.getExtendsClause() == null && c.getImplementsClause().isEmpty();
                        }
                        if (notSerializable) {
                            int at = s.source().indexOf("serialVersionUID", s.end(f.getType()));
                            s.report(at, at + 16, "'serialVersionUID' in a class that is not 'Serializable'", null);
                        }
                    }
                    return super.visitClass(c, v);
                }
            }.run();
        }
    }

    static final class FinalizeNotProtected extends Inspection {
        FinalizeNotProtected() {
            super("FinalizeNotProtected", "suspicious", Types.NONE,
                    "A `public` `finalize()` method, which lets any code call it; it should be `protected`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    if (m.getName().contentEquals("finalize") && m.getParameters().isEmpty()
                            && m.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                        int[] r = modifierRange(s, m.getModifiers(), "public");
                        s.report(r[0], r[1], "'finalize()' should be 'protected', not 'public'",
                                Fix.replace("Make it protected", r[0], r[1], "protected"));
                    }
                    return super.visitMethod(m, v);
                }
            }.run();
        }
    }

    static final class EmptyInitializer extends Inspection {
        EmptyInitializer() {
            super("EmptyInitializer", "complexity", Types.NONE,
                    "An empty initializer block, `{}` or `static {}`, in a class. The fix deletes it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitClass(ClassTree c, Void v) {
                    for (Tree m : c.getMembers()) {
                        if (m instanceof BlockTree b && b.getStatements().isEmpty() && !s.hasComment(b)) {
                            s.report(b, "Empty " + (b.isStatic() ? "static " : "") + "initializer",
                                    ControlFlow.deleteStatement(s, "Delete it", b));
                        }
                    }
                    return super.visitClass(c, v);
                }
            }.run();
        }
    }

    static final class ConfusingMainMethod extends Inspection {
        ConfusingMainMethod() {
            super("ConfusingMainMethod", "suspicious", Types.NONE,
                    "A method named `main` that is not `public static void main(String[])` in a class, so it is not the"
                            + " program's entry point.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    if (m.getName().contentEquals("main") && parent() instanceof ClassTree c && c.getKind() == Tree.Kind.CLASS) {
                        List<? extends VariableTree> ps = m.getParameters();
                        boolean strings = ps.size() == 1 && ps.get(0).getType().getKind() == Tree.Kind.ARRAY_TYPE
                                && Scan.simpleName(((com.sun.source.tree.ArrayTypeTree) ps.get(0).getType()).getType())
                                        .equals("String");
                        boolean entry = isVoid(m.getReturnType()) && (ps.isEmpty() || strings);
                        if (!entry) {
                            int at = s.source().indexOf("main", s.end(m.getReturnType()));
                            s.report(at, at + 4, "Method 'main' is not the program's entry point", null);
                        }
                    }
                    return super.visitMethod(m, v);
                }
            }.run();
        }
    }

    static final class CloneableImplementsClone extends Inspection {
        CloneableImplementsClone() {
            super("CloneableImplementsClone", "suspicious", Types.NONE,
                    "A class that implements `Cloneable` without overriding `clone()`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitClass(ClassTree c, Void v) {
                    boolean cloneable = c.getImplementsClause().stream().anyMatch(t -> Scan.simpleName(t).equals("Cloneable"));
                    boolean hasClone = c.getMembers().stream()
                            .anyMatch(m -> m instanceof MethodTree mt && mt.getName().contentEquals("clone") && mt.getParameters().isEmpty());
                    if (cloneable && !hasClone && c.getKind() == Tree.Kind.CLASS) {
                        int at = s.source().indexOf(c.getSimpleName().toString(), s.start(c));
                        s.report(at, at + c.getSimpleName().length(),
                                "'" + c.getSimpleName() + "' is 'Cloneable' but does not define 'clone()'", null);
                    }
                    return super.visitClass(c, v);
                }
            }.run();
        }
    }

    static final class EqualsWhichDoesntCheckParameterClass extends Inspection {
        EqualsWhichDoesntCheckParameterClass() {
            super("EqualsWhichDoesntCheckParameterClass", "correctness", Types.NONE,
                    "An `equals(Object)` that never checks the class of its argument (with `instanceof`, `getClass()` or"
                            + " a cast in a `try`), so it can throw `ClassCastException`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    if (m.getName().contentEquals("equals") && m.getParameters().size() == 1 && m.getBody() != null
                            && Scan.simpleName(m.getParameters().get(0).getType()).equals("Object")) {
                        String p = m.getParameters().get(0).getName().toString();
                        boolean checks = Flow.contains(m.getBody(), t -> t instanceof InstanceOfTree
                                || t instanceof MethodInvocationTree mi && (Scan.name(mi).equals("getClass")
                                        || Scan.name(mi).equals("isInstance") || Scan.name(mi).equals("isAssignableFrom")
                                        || Scan.name(mi).equals("equals") || Scan.name(mi).equals("isEqual"))
                                || t instanceof com.sun.source.tree.TryTree
                                || t instanceof com.sun.source.tree.SwitchTree
                                || t instanceof com.sun.source.tree.SwitchExpressionTree)
                                || !Flow.contains(m.getBody(), t -> t instanceof com.sun.source.tree.TypeCastTree tc
                                        && Scan.strip(tc.getExpression()) instanceof IdentifierTree i && i.getName().contentEquals(p));
                        if (!checks) {
                            int at = s.source().indexOf("equals", s.end(m.getReturnType()));
                            s.report(at, at + 6, "'equals()' does not check the class of its parameter", null);
                        }
                    }
                    return super.visitMethod(m, v);
                }
            }.run();
        }
    }

    static final class IteratorHasNextCallsIteratorNext extends Inspection {
        IteratorHasNextCallsIteratorNext() {
            super("IteratorHasNextCallsIteratorNext", "correctness", Types.NONE,
                    "A `hasNext()` that calls `next()` on the same iterator, which moves it on.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    if (m.getName().contentEquals("hasNext") && m.getParameters().isEmpty() && m.getBody() != null) {
                        new Visitor(s) {
                            @Override
                            public Void visitMethodInvocation(MethodInvocationTree mi, Void x) {
                                ExpressionTree r = Scan.receiver(mi);
                                if (Scan.calls(mi, "next", 0) && (r == null || r.toString().equals("this"))) {
                                    s.report(mi, "'hasNext()' calls 'next()'");
                                }
                                return super.visitMethodInvocation(mi, x);
                            }

                            @Override
                            public Void visitClass(ClassTree c, Void x) {
                                return null;
                            }
                        }.scan(new TreePath(getCurrentPath(), m.getBody()), null);
                    }
                    return super.visitMethod(m, v);
                }
            }.run();
        }
    }

    static final class ComparatorMethodParameterNotUsed extends Inspection {
        ComparatorMethodParameterNotUsed() {
            super("ComparatorMethodParameterNotUsed", "correctness", Types.NONE,
                    "A `compare(a, b)` that does not use one of its parameters, so it cannot be a valid ordering.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    if (m.getName().contentEquals("compare") && m.getParameters().size() == 2 && m.getBody() != null
                            && m.getReturnType() instanceof PrimitiveTypeTree p && p.getPrimitiveTypeKind() == TypeKind.INT
                            && !Flow.contains(m.getBody(), t -> t instanceof com.sun.source.tree.ThrowTree)) {
                        for (VariableTree param : m.getParameters()) {
                            if (!uses(m.getBody(), param.getName().toString())) {
                                int at = s.source().indexOf(param.getName().toString(), s.end(param.getType()));
                                s.report(at, at + param.getName().length(),
                                        "'compare()' parameter '" + param.getName() + "' is not used", null);
                            }
                        }
                    }
                    return super.visitMethod(m, v);
                }
            }.run();
        }
    }

    static boolean uses(Tree root, String name) {
        Boolean found = new TreeScanner<Boolean, Void>() {
            @Override
            public Boolean visitIdentifier(IdentifierTree t, Void v) {
                return t.getName().contentEquals(name);
            }

            @Override
            public Boolean reduce(Boolean a, Boolean b) {
                return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
            }
        }.scan(root, null);
        return Boolean.TRUE.equals(found);
    }

    static final class RedundantMethodOverride extends Inspection {
        RedundantMethodOverride() {
            super("RedundantMethodOverride", "complexity", Types.REQUIRED,
                    "An `@Override` method that only calls the method it overrides with the same arguments,"
                            + " `return super.f(a);`, and changes nothing else: not its visibility, return type or"
                            + " exceptions.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    if (m.getBody() != null && m.getBody().getStatements().size() == 1
                            && m.getModifiers().getAnnotations().size() == 1
                            && Scan.simpleName(m.getModifiers().getAnnotations().get(0).getAnnotationType()).equals("Override")
                            && !m.getModifiers().getFlags().contains(Modifier.FINAL)
                            && !m.getModifiers().getFlags().contains(Modifier.SYNCHRONIZED)
                            && !s.hasComment(m.getBody())) {
                        StatementTree only = m.getBody().getStatements().get(0);
                        ExpressionTree e = only instanceof ReturnTree r ? r.getExpression() : Scan.expression(only);
                        boolean voidMatches = isVoid(m.getReturnType()) == !(only instanceof ReturnTree);
                        if (voidMatches && e instanceof MethodInvocationTree call
                                && Scan.receiver(call) instanceof IdentifierTree sup && sup.getName().contentEquals("super")
                                && Scan.name(call).contentEquals(m.getName())
                                && call.getArguments().size() == m.getParameters().size()) {
                            boolean same = true;
                            for (int k = 0; k < call.getArguments().size(); k++) {
                                same &= call.getArguments().get(k) instanceof IdentifierTree a
                                        && a.getName().contentEquals(m.getParameters().get(k).getName());
                            }
                            if (same && sameAsSuperSignature(s, m)) {
                                int at = s.source().indexOf(m.getName().toString(), s.end(m.getReturnType()));
                                s.report(at, at + m.getName().length(), "Method '" + m.getName() + "' only calls its super method", null);
                            }
                        }
                    }
                    return super.visitMethod(m, v);
                }
            }.run();
        }
    }

    /**
     * True if {@code m} has the visibility, return type and exceptions of the method it overrides.
     */
    static boolean sameAsSuperSignature(Scan s, MethodTree m) {
        if (!(s.element(m) instanceof javax.lang.model.element.ExecutableElement e)) return false;
        var owner = (javax.lang.model.element.TypeElement) e.getEnclosingElement();
        for (var sup : s.typeUtils().directSupertypes(owner.asType())) {
            var supEl = (javax.lang.model.element.TypeElement) s.typeUtils().asElement(sup);
            if (supEl == null) continue;
            for (var member : s.elementUtils().getAllMembers(supEl)) {
                if (member instanceof javax.lang.model.element.ExecutableElement o
                        && s.elementUtils().overrides(e, o, owner)) {
                    return visibility(o.getModifiers()) == visibility(e.getModifiers())
                            && s.typeUtils().isSameType(o.getReturnType(), e.getReturnType())
                            && o.getThrownTypes().size() == e.getThrownTypes().size()
                            && !o.getModifiers().contains(Modifier.ABSTRACT);
                }
            }
        }
        return false;
    }

    private static int visibility(Set<Modifier> m) {
        return m.contains(Modifier.PUBLIC) ? 3 : m.contains(Modifier.PROTECTED) ? 2 : m.contains(Modifier.PRIVATE) ? 0 : 1;
    }

    static final class RedundantRecordConstructor extends Inspection {
        RedundantRecordConstructor() {
            super("RedundantRecordConstructor", "complexity", Types.NONE,
                    "A record's compact constructor with nothing in it, or a canonical constructor that only assigns"
                            + " each component to its field, which the record does anyway.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitClass(ClassTree c, Void v) {
                    if (c.getKind() == Tree.Kind.RECORD) {
                        List<String> components = new ArrayList<>();
                        for (Tree m : c.getMembers()) {
                            if (m instanceof VariableTree f && !f.getModifiers().getFlags().contains(Modifier.STATIC)) {
                                components.add(f.getName().toString());
                            }
                        }
                        for (Tree m : c.getMembers()) {
                            if (m instanceof MethodTree ctor && ctor.getName().contentEquals("<init>") && ctor.getBody() != null
                                    && s.start(ctor) >= 0 && ctor.getModifiers().getAnnotations().isEmpty() && !s.hasComment(ctor.getBody())) {
                                boolean compact = !s.source().substring(s.start(ctor), s.start(ctor.getBody())).contains("(");
                                List<? extends StatementTree> st = ctor.getBody().getStatements();
                                boolean redundant = compact ? st.isEmpty() : assignsEach(st, ctor, components);
                                if (redundant) {
                                    int at = s.source().indexOf(c.getSimpleName().toString(), s.start(ctor));
                                    s.report(at, at + c.getSimpleName().length(), "Redundant record constructor", null);
                                }
                            }
                        }
                    }
                    return super.visitClass(c, v);
                }

                private boolean assignsEach(List<? extends StatementTree> st, MethodTree ctor, List<String> components) {
                    if (ctor.getParameters().size() != components.size() || st.size() != components.size()) return false;
                    for (int k = 0; k < components.size(); k++) {
                        if (!ctor.getParameters().get(k).getName().contentEquals(components.get(k))) return false;
                    }
                    Set<String> assigned = new HashSet<>();
                    for (StatementTree t : st) {
                        if (!(Scan.expression(t) instanceof AssignmentTree a)) return false;
                        if (!(a.getVariable() instanceof MemberSelectTree ms && ms.getExpression().toString().equals("this")
                                && a.getExpression() instanceof IdentifierTree id
                                && id.getName().contentEquals(ms.getIdentifier()))) {
                            return false;
                        }
                        assigned.add(id(a));
                    }
                    return assigned.containsAll(components);
                }

                private String id(AssignmentTree a) {
                    return ((MemberSelectTree) a.getVariable()).getIdentifier().toString();
                }
            }.run();
        }
    }

    static final class WrongPackageStatement extends Inspection {
        WrongPackageStatement() {
            super("WrongPackageStatement", "correctness", Types.NONE,
                    "A `package` declaration that does not match the folder the file is in.");
        }

        @Override
        protected void run(Scan s) {
            CompilationUnitTree cu = s.root();
            String path = s.path().replace('\\', '/');
            int slash = path.lastIndexOf('/');
            String dir = slash < 0 ? "" : path.substring(0, slash);
            if (cu.getPackage() == null) return;
            String pkg = cu.getPackageName().toString();
            String expected = pkg.replace('.', '/');
            if (!(dir.equals(expected) || dir.endsWith("/" + expected)) && slash >= 0) {
                s.report(cu.getPackage(), "Package '" + pkg + "' does not match the folder '" + dir + "'");
            }
        }
    }

    static final class PackageInfoWithoutPackage extends Inspection {
        PackageInfoWithoutPackage() {
            super("PackageInfoWithoutPackage", "correctness", Types.NONE,
                    "A `package-info.java` without a `package` declaration, so its Javadoc and annotations apply to"
                            + " nothing.");
        }

        @Override
        protected void run(Scan s) {
            if ((s.path().equals("package-info.java") || s.path().endsWith("/package-info.java")) && s.root().getPackage() == null) {
                s.report(0, Math.min(1, s.source().length()), "'package-info.java' has no 'package' declaration", null);
            }
        }
    }

    static final class NewClassNamingConvention extends Inspection {
        private static final Pattern NAME = Pattern.compile("[A-Z][A-Za-z\\d]*");

        NewClassNamingConvention() {
            super("NewClassNamingConvention", "style", Types.NONE,
                    "A class, interface, enum, record or annotation whose name is not UpperCamelCase (`[A-Z][A-Za-z\\d]*`).");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitClass(ClassTree c, Void v) {
                    String n = c.getSimpleName().toString();
                    if (!n.isEmpty() && !NAME.matcher(n).matches()) {
                        int at = s.source().indexOf(n, s.start(c));
                        s.report(at, at + n.length(), "Name '" + n + "' does not match '" + NAME.pattern() + "'", null);
                    }
                    return super.visitClass(c, v);
                }
            }.run();
        }
    }

    static final class DuplicateThrows extends Inspection {
        DuplicateThrows() {
            super("DuplicateThrows", "complexity", Types.NONE,
                    "An exception listed twice in a method's `throws` clause.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    Set<String> seen = new HashSet<>();
                    for (ExpressionTree t : m.getThrows()) {
                        if (!seen.add(Scan.simpleName(t))) s.report(t, "Duplicate throws '" + t + "'");
                    }
                    return super.visitMethod(m, v);
                }
            }.run();
        }
    }

    static final class FieldMayBeFinal extends Inspection {
        FieldMayBeFinal() {
            super("FieldMayBeFinal", "style", Types.NONE,
                    "A `private` field that is never assigned after it is initialized: in its declaration, or once at"
                            + " the top of every constructor (static fields: in a static initializer). It can be"
                            + " `final`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitClass(ClassTree c, Void v) {
                    Map<String, VariableTree> fields = new HashMap<>();
                    for (Tree m : c.getMembers()) {
                        if (m instanceof VariableTree f) {
                            Set<Modifier> mods = f.getModifiers().getFlags();
                            if (mods.contains(Modifier.PRIVATE) && !mods.contains(Modifier.FINAL)
                                    && !mods.contains(Modifier.VOLATILE) && !mods.contains(Modifier.TRANSIENT)
                                    && f.getModifiers().getAnnotations().isEmpty()) {
                                fields.put(f.getName().toString(), f);
                            }
                        }
                    }
                    if (!fields.isEmpty()) check(c, fields);
                    return super.visitClass(c, v);
                }

                private void check(ClassTree c, Map<String, VariableTree> fields) {
                    Map<String, Integer> elsewhere = new HashMap<>();
                    List<MethodTree> ctors = new ArrayList<>();
                    List<BlockTree> inits = new ArrayList<>();
                    for (Tree m : c.getMembers()) {
                        if (m instanceof MethodTree mt && mt.getName().contentEquals("<init>")) ctors.add(mt);
                        else if (m instanceof BlockTree b) inits.add(b);
                    }
                    for (Tree m : c.getMembers()) {
                        boolean ctor = m instanceof MethodTree mt && mt.getName().contentEquals("<init>");
                        if (ctor || m instanceof BlockTree) continue;
                        if (m instanceof VariableTree f && fields.containsKey(f.getName().toString())) {
                            count(f.getInitializer(), fields, elsewhere);
                            continue;
                        }
                        count(m, fields, elsewhere);
                    }
                    for (VariableTree f : fields.values()) {
                        String n = f.getName().toString();
                        if (elsewhere.getOrDefault(n, 0) > 0) continue;
                        boolean isStatic = f.getModifiers().getFlags().contains(Modifier.STATIC);
                        boolean ok;
                        if (f.getInitializer() != null) {
                            ok = ctors.stream().noneMatch(ct -> assigns(ct.getBody(), n) > 0)
                                    && inits.stream().noneMatch(b -> assigns(b, n) > 0);
                        } else if (isStatic) {
                            List<BlockTree> statics = inits.stream().filter(BlockTree::isStatic).toList();
                            ok = statics.size() == 1 && topLevelOnce(statics.get(0), n)
                                    && ctors.stream().noneMatch(ct -> assigns(ct.getBody(), n) > 0);
                        } else {
                            boolean byInit = inits.stream().filter(b -> !b.isStatic()).anyMatch(b -> topLevelOnce(b, n));
                            List<MethodTree> real = ctors.stream().filter(ct -> s.start(ct) >= 0 && !delegates(ct)).toList();
                            if (byInit) {
                                ok = ctors.stream().noneMatch(ct -> assigns(ct.getBody(), n) > 0);
                            } else {
                                ok = !real.isEmpty() && real.stream().allMatch(ct -> topLevelOnce(ct.getBody(), n))
                                        && ctors.stream().filter(this::delegates).noneMatch(ct -> assigns(ct.getBody(), n) > 0);
                            }
                        }
                        if (ok) {
                            int at = s.source().indexOf(n, s.end(f.getType()));
                            s.report(at, at + n.length(), "Field '" + n + "' may be 'final'", null);
                        }
                    }
                }

                private boolean delegates(MethodTree ct) {
                    if (ct.getBody() == null || ct.getBody().getStatements().isEmpty()) return false;
                    return Scan.expression(ct.getBody().getStatements().get(0)) instanceof MethodInvocationTree mi
                            && Scan.name(mi).equals("this");
                }

                private boolean topLevelOnce(BlockTree b, String n) {
                    if (b == null) return false;
                    int top = 0;
                    for (StatementTree t : b.getStatements()) {
                        if (Scan.expression(t) instanceof AssignmentTree a && target(a.getVariable(), n)
                                && assigns(a.getExpression(), n) == 0) {
                            top++;
                        }
                    }
                    return top == 1 && assigns(b, n) == 1;
                }

                private void count(Tree root, Map<String, VariableTree> fields, Map<String, Integer> out) {
                    if (root == null) return;
                    for (String n : fields.keySet()) {
                        int k = assigns(root, n);
                        if (k > 0) out.merge(n, k, Integer::sum);
                    }
                }

                private boolean target(ExpressionTree e, String n) {
                    return e instanceof IdentifierTree i && i.getName().contentEquals(n)
                            || e instanceof MemberSelectTree m && m.getIdentifier().contentEquals(n)
                                    && (m.getExpression().toString().equals("this") || Character.isUpperCase(m.getExpression().toString().charAt(0)));
                }

                private int assigns(Tree root, String n) {
                    int[] count = {0};
                    new TreeScanner<Void, Void>() {
                        @Override
                        public Void visitAssignment(AssignmentTree a, Void v) {
                            if (target(a.getVariable(), n)) count[0]++;
                            return super.visitAssignment(a, v);
                        }

                        @Override
                        public Void visitCompoundAssignment(CompoundAssignmentTree a, Void v) {
                            if (target(a.getVariable(), n)) count[0] += 2;
                            return super.visitCompoundAssignment(a, v);
                        }

                        @Override
                        public Void visitUnary(UnaryTree u, Void v) {
                            if (u.getKind() != Tree.Kind.UNARY_MINUS && u.getKind() != Tree.Kind.UNARY_PLUS
                                    && u.getKind() != Tree.Kind.LOGICAL_COMPLEMENT && u.getKind() != Tree.Kind.BITWISE_COMPLEMENT
                                    && target(u.getExpression(), n)) {
                                count[0] += 2;
                            }
                            return super.visitUnary(u, v);
                        }

                        @Override
                        public Void visitLambdaExpression(LambdaExpressionTree l, Void v) {
                            int before = count[0];
                            super.visitLambdaExpression(l, v);
                            if (count[0] > before) count[0] += 2;
                            return null;
                        }

                        @Override
                        public Void visitClass(ClassTree c, Void v) {
                            int before = count[0];
                            super.visitClass(c, v);
                            if (count[0] > before) count[0] += 2;
                            return null;
                        }
                    }.scan(root, null);
                    return count[0];
                }
            }.run();
        }
    }

    static final class OptionalUsedAsFieldOrParameterType extends Inspection {
        OptionalUsedAsFieldOrParameterType() {
            super("OptionalUsedAsFieldOrParameterType", "style", Types.NONE,
                    "A field or method parameter of type `Optional`, which is meant for return values only.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitVariable(VariableTree v, Void x) {
                    Tree p = parent();
                    boolean field = p instanceof ClassTree;
                    boolean param = p instanceof MethodTree m && m.getParameters().contains(v);
                    String type = Scan.simpleName(v.getType());
                    if ((field || param) && v.getType() != null && s.start(v.getType()) >= 0
                            && (type.equals("Optional") || type.equals("OptionalInt") || type.equals("OptionalLong")
                                    || type.equals("OptionalDouble"))
                            && !(p instanceof MethodTree m2 && m2.getModifiers().getAnnotations().stream()
                                    .anyMatch(a -> Scan.simpleName(a.getAnnotationType()).equals("Override")))) {
                        s.report(v.getType(), "'" + type + "' used as the type of " + (field ? "field" : "parameter")
                                + " '" + v.getName() + "'");
                    }
                    return super.visitVariable(v, x);
                }
            }.run();
        }
    }
}
