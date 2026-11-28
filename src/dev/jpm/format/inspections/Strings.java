package dev.jpm.format.inspections;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import dev.jpm.format.api.Fix;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IntelliJ's inspections about strings, text, number objects and common library calls.
 */
final class Strings {
    private Strings() {
    }

    static List<Inspection> all() {
        return List.of(
                new StringEqualsEmptyString(),
                new SizeReplaceableByIsEmpty(),
                new IndexOfReplaceableByContains(),
                new ArraysAsListWithZeroOrOneArgument(),
                new UnnecessaryToStringCall(),
                new UnnecessaryCallToStringValueOf(),
                new StringBufferReplaceableByStringBuilder(),
                new RedundantStringFormatCall(),
                new ReplaceAllDot(),
                new StringConcatenationInLoops(),
                new UnnecessaryStringEscape(),
                new UnnecessaryUnicodeEscape(),
                new TrailingWhitespacesInTextBlock(),
                new CharsetObjectCanBeUsed(),
                new SystemGetProperty(),
                new MalformedFormatString(),
                new ReplaceOnLiteralHasNoEffect(),
                new UnnecessaryTemporaryOnConversionToString(),
                new UnnecessaryTemporaryOnConversionFromString(),
                new BooleanConstructor(),
                new CachedNumberConstructorCall(),
                new UnpredictableBigDecimalConstructorCall(),
                new MismatchedStringCase(),
                new SuspiciousDateFormat(),
                new ToArrayCallWithZeroLengthArrayArgument(),
                new IOStreamConstructor());
    }

    /**
     * True if {@code e} is part of a string concatenation: with types, a {@code +} of type String.
     */
    static boolean inConcatenation(Scan s, TreePath path) {
        TreePath p = path.getParentPath();
        while (p != null && p.getLeaf() instanceof com.sun.source.tree.ParenthesizedTree) p = p.getParentPath();
        if (p == null || !(p.getLeaf() instanceof BinaryTree b) || b.getKind() != Tree.Kind.PLUS) return false;
        if (s.typed()) return s.is(b, "java.lang.String");
        return hasStringLiteral(b);
    }

    static boolean hasStringLiteral(ExpressionTree e) {
        e = Scan.strip(e);
        if (Scan.string(e) != null) return true;
        return e instanceof BinaryTree b && b.getKind() == Tree.Kind.PLUS
                && (hasStringLiteral(b.getLeftOperand()) || hasStringLiteral(b.getRightOperand()));
    }

    /**
     * True if a type has a public no-argument method {@code name}.
     */
    static boolean hasMethod(Scan s, TypeMirror t, String name) {
        if (!(t != null && t.getKind() == TypeKind.DECLARED)) return false;
        Element e = s.typeUtils().asElement(t);
        if (!(e instanceof TypeElement te)) return false;
        for (Element m : s.elementUtils().getAllMembers(te)) {
            if (m.getKind() == ElementKind.METHOD && m.getSimpleName().contentEquals(name)
                    && ((javax.lang.model.element.ExecutableElement) m).getParameters().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static final class StringEqualsEmptyString extends Inspection {
        StringEqualsEmptyString() {
            super("StringEqualsEmptyString", "performance", Types.NONE,
                    "`s.equals(\"\")`, which is `s.isEmpty()`. The fix rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    ExpressionTree r = Scan.receiver(m);
                    if (Scan.calls(m, "equals", 1) && r != null && "".equals(Scan.string(m.getArguments().get(0)))
                            && Scan.string(r) == null) {
                        s.report(m, "'equals(\"\")' can be 'isEmpty()'", s.replace("Use isEmpty()", m, Expressions.operand(s, r) + ".isEmpty()"));
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class SizeReplaceableByIsEmpty extends Inspection {
        SizeReplaceableByIsEmpty() {
            super("SizeReplaceableByIsEmpty", "style", Types.OPTIONAL,
                    "`x.size() == 0` or `x.length() > 0` and the like, which `x.isEmpty()` says directly. With types,"
                            + " any type that has `isEmpty()`; without, only `size()`. The fix rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    check(b, b.getLeftOperand(), b.getRightOperand(), false);
                    check(b, b.getRightOperand(), b.getLeftOperand(), true);
                    return super.visitBinary(b, v);
                }

                private void check(BinaryTree b, ExpressionTree call, ExpressionTree n, boolean flipped) {
                    if (!(Scan.strip(call) instanceof MethodInvocationTree m)
                            || !(Scan.calls(m, "size", 0) || Scan.calls(m, "length", 0))) return;
                    ExpressionTree r = Scan.receiver(m);
                    Long k = Scan.integer(n);
                    if (r == null || k == null || r.toString().equals("this") || r.toString().equals("super")) return;
                    if (s.typed() ? !hasMethod(s, s.type(r), "isEmpty") : Scan.name(m).equals("length")) return;
                    Tree.Kind op = b.getKind();
                    if (flipped) op = switch (op) {
                        case LESS_THAN -> Tree.Kind.GREATER_THAN;
                        case GREATER_THAN -> Tree.Kind.LESS_THAN;
                        case LESS_THAN_EQUAL -> Tree.Kind.GREATER_THAN_EQUAL;
                        case GREATER_THAN_EQUAL -> Tree.Kind.LESS_THAN_EQUAL;
                        default -> op;
                    };
                    Boolean empty = null;
                    if (k == 0 && op == Tree.Kind.EQUAL_TO || k == 1 && op == Tree.Kind.LESS_THAN || k == 0 && op == Tree.Kind.LESS_THAN_EQUAL)
                        empty = true;
                    if (k == 0 && (op == Tree.Kind.NOT_EQUAL_TO || op == Tree.Kind.GREATER_THAN) || k == 1 && op == Tree.Kind.GREATER_THAN_EQUAL)
                        empty = false;
                    if (empty != null) {
                        String repl = (empty ? "" : "!") + Expressions.operand(s, r) + ".isEmpty()";
                        s.report(b, "'" + s.text(b) + "' can be '" + repl + "'", s.replace("Use isEmpty()", b, repl));
                    }
                }
            }.run();
        }
    }

    static final class IndexOfReplaceableByContains extends Inspection {
        IndexOfReplaceableByContains() {
            super("IndexOfReplaceableByContains", "style", Types.OPTIONAL,
                    "`s.indexOf(x) != -1`, `>= 0`, `== -1` or `< 0`, which is `s.contains(x)` or its negation, for a"
                            + " string or list argument (not a `char`). Without types, calls with a string literal argument."
                            + " The fix rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if (Scan.strip(b.getLeftOperand()) instanceof MethodInvocationTree m && Scan.calls(m, "indexOf", 1)) {
                        ExpressionTree r = Scan.receiver(m);
                        Long k = Scan.integer(b.getRightOperand());
                        ExpressionTree arg0 = Scan.strip(m.getArguments().get(0));
                        boolean charArg = s.primitive(arg0) == TypeKind.CHAR
                                || arg0 instanceof LiteralTree cl && cl.getKind() == Tree.Kind.CHAR_LITERAL;
                        boolean known = r != null && !charArg && (s.typed() ? s.isA(r, "java.lang.String") || s.isA(r, "java.util.List")
                                : Scan.string(arg0) != null);
                        if (known && k != null) {
                            Tree.Kind op = b.getKind();
                            Boolean contains = null;
                            if (k == -1 && op == Tree.Kind.NOT_EQUAL_TO || k == 0 && op == Tree.Kind.GREATER_THAN_EQUAL || k == -1 && op == Tree.Kind.GREATER_THAN)
                                contains = true;
                            if (k == -1 && op == Tree.Kind.EQUAL_TO || k == 0 && op == Tree.Kind.LESS_THAN)
                                contains = false;
                            if (contains != null) {
                                String arg = s.text(m.getArguments().get(0));
                                String repl = (contains ? "" : "!") + Expressions.operand(s, r) + ".contains(" + arg + ")";
                                s.report(b, "'" + s.text(b) + "' can be '" + repl + "'", s.replace("Use contains()", b, repl));
                            }
                        }
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    static final class ArraysAsListWithZeroOrOneArgument extends Inspection {
        ArraysAsListWithZeroOrOneArgument() {
            super("ArraysAsListWithZeroOrOneArgument", "performance", Types.OPTIONAL,
                    "`Arrays.asList()` with no argument, which is `Collections.emptyList()`, or with one element (not an"
                            + " array), which is `Collections.singletonList(x)`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.callsStatic(m, "Arrays", "asList")) {
                        if (m.getArguments().isEmpty()) {
                            s.report(m, "'Arrays.asList()' with no arguments", s.replace("Use Collections.emptyList()", m, "java.util.Collections.emptyList()"));
                        } else if (m.getArguments().size() == 1) {
                            ExpressionTree a = Scan.strip(m.getArguments().get(0));
                            boolean single = s.typed() ? s.type(a) != null && !s.isArray(a)
                                    : a instanceof LiteralTree && !Scan.isNull(a) || a instanceof NewClassTree;
                            if (single) {
                                s.report(m, "'Arrays.asList()' with one argument",
                                        s.replace("Use Collections.singletonList()", m, "java.util.Collections.singletonList(" + s.text(a) + ")"));
                            }
                        }
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class UnnecessaryToStringCall extends Inspection {
        UnnecessaryToStringCall() {
            super("UnnecessaryToStringCall", "complexity", Types.OPTIONAL,
                    "`x.toString()` where the conversion happens anyway: in a string concatenation, `append()`,"
                            + " `print()`/`println()` or `String.valueOf()`. The fix removes the call.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    ExpressionTree r = Scan.receiver(m);
                    if (Scan.calls(m, "toString", 0) && r != null && !r.toString().equals("super")
                            && !(s.typed() && s.isArray(r)) && unnecessary(m)) {
                        s.report(m, "Unnecessary 'toString()' call", s.replace("Remove the call", m, s.text(r)));
                    }
                    return super.visitMethodInvocation(m, v);
                }

                private boolean unnecessary(MethodInvocationTree call) {
                    if (inConcatenation(s, getCurrentPath()) && otherIsString(call)) return true;
                    Tree p = parent();
                    if (p instanceof MethodInvocationTree outer && outer.getArguments().size() == 1 && outer.getArguments().get(0) == call) {
                        String n = Scan.name(outer);
                        ExpressionTree r = Scan.receiver(outer);
                        String rs = r == null ? "" : r.toString();
                        if (n.equals("append"))
                            return s.typed() ? r != null && s.isA(r, "java.lang.AbstractStringBuilder") : rs.matches("(?i)(sb|buf|buffer|builder|.*Builder)");
                        if (n.equals("print") || n.equals("println"))
                            return rs.equals("System.out") || rs.equals("System.err");
                        if (Scan.callsStatic(outer, "String", "valueOf")) return true;
                    }
                    return false;
                }

                private boolean otherIsString(MethodInvocationTree call) {
                    TreePath p = getCurrentPath().getParentPath();
                    while (p.getLeaf() instanceof com.sun.source.tree.ParenthesizedTree) p = p.getParentPath();
                    BinaryTree b = (BinaryTree) p.getLeaf();
                    ExpressionTree other = Scan.strip(b.getLeftOperand()) == call ? b.getRightOperand() : b.getLeftOperand();
                    if (s.typed()) return s.is(other, "java.lang.String") || Scan.strip(b.getLeftOperand()) != call;
                    return hasStringLiteral(other) || Scan.strip(b.getLeftOperand()) != call;
                }
            }.run();
        }
    }

    static final class UnnecessaryCallToStringValueOf extends Inspection {
        UnnecessaryCallToStringValueOf() {
            super("UnnecessaryCallToStringValueOf", "complexity", Types.REQUIRED,
                    "`String.valueOf(x)` where the conversion happens anyway: in a string concatenation, `append()` or"
                            + " `print()`/`println()` (not for `char[]`). The fix removes the call.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.callsStatic(m, "String", "valueOf") && m.getArguments().size() == 1) {
                        ExpressionTree a = m.getArguments().get(0);
                        TypeMirror t = s.type(a);
                        boolean chars = t instanceof javax.lang.model.type.ArrayType at && at.getComponentType().getKind() == TypeKind.CHAR;
                        if (t != null && !chars && unnecessary(m)) {
                            s.report(m, "Unnecessary 'String.valueOf()' call", s.replace("Remove the call", m, s.text(a)));
                        }
                    }
                    return super.visitMethodInvocation(m, v);
                }

                private boolean unnecessary(MethodInvocationTree call) {
                    TreePath pp = getCurrentPath().getParentPath();
                    while (pp.getLeaf() instanceof com.sun.source.tree.ParenthesizedTree) pp = pp.getParentPath();
                    if (pp.getLeaf() instanceof BinaryTree b && b.getKind() == Tree.Kind.PLUS && s.is(b, "java.lang.String")) {
                        ExpressionTree other = Scan.strip(b.getLeftOperand()) == call ? b.getRightOperand() : b.getLeftOperand();
                        return s.is(other, "java.lang.String") && !(s.primitive(call.getArguments().get(0)) == TypeKind.CHAR
                                && s.primitive(other) == TypeKind.CHAR);
                    }
                    if (parent() instanceof MethodInvocationTree outer && outer.getArguments().size() == 1) {
                        ExpressionTree r = Scan.receiver(outer);
                        String n = Scan.name(outer);
                        if (n.equals("append")) return r != null && s.isA(r, "java.lang.AbstractStringBuilder");
                        if (n.equals("print") || n.equals("println"))
                            return r != null && s.isA(r, "java.io.PrintStream");
                    }
                    return false;
                }
            }.run();
        }
    }

    static final class StringBufferReplaceableByStringBuilder extends Inspection {
        StringBufferReplaceableByStringBuilder() {
            super("StringBufferReplaceableByStringBuilder", "performance", Types.NONE,
                    "A local `StringBuffer` that never leaves its method, which needs none of `StringBuffer`'s locking:"
                            + " `StringBuilder` does the same. The fix changes the type.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethod(MethodTree m, Void v) {
                    if (m.getBody() != null) {
                        new Visitor(s) {
                            @Override
                            public Void visitVariable(VariableTree var, Void x) {
                                if (Scan.simpleName(var.getType()).equals("StringBuffer") && var.getInitializer() instanceof NewClassTree n
                                        && Scan.simpleName(n.getIdentifier()).equals("StringBuffer") && local(m.getBody(), var.getName().toString())) {
                                    int at = s.start(var.getType());
                                    int nt = s.start(n.getIdentifier());
                                    s.report(var.getType(), "'StringBuffer' '" + var.getName() + "' can be 'StringBuilder'", new Fix("Use StringBuilder",
                                            List.of(new Fix.Edit(at, s.end(var.getType()), "StringBuilder"),
                                                    new Fix.Edit(nt, s.end(n.getIdentifier()), "StringBuilder"))));
                                }
                                return super.visitVariable(var, x);
                            }

                            @Override
                            public Void visitClass(ClassTree c, Void x) {
                                return null;
                            }
                        }.scan(new TreePath(getCurrentPath(), m.getBody()), null);
                    }
                    return super.visitMethod(m, v);
                }

                /**
                 * True if every use of {@code name} is as a receiver, or as text: it never escapes.
                 */
                private boolean local(BlockTree body, String name) {
                    boolean[] escapes = {false};
                    new com.sun.source.util.TreePathScanner<Void, Void>() {
                        @Override
                        public Void visitIdentifier(IdentifierTree i, Void x) {
                            if (!i.getName().contentEquals(name)) return null;
                            Tree p = getCurrentPath().getParentPath().getLeaf();
                            boolean receiver = p instanceof MemberSelectTree ms && ms.getExpression() == i;
                            boolean text = p instanceof BinaryTree b && b.getKind() == Tree.Kind.PLUS;
                            boolean declaration = p instanceof VariableTree;
                            if (receiver && getCurrentPath().getParentPath().getParentPath().getLeaf() instanceof MethodInvocationTree mi
                                    && Scan.name(mi).equals("append") && !(getCurrentPath().getParentPath().getParentPath().getParentPath().getLeaf()
                                    instanceof com.sun.source.tree.ExpressionStatementTree)
                                    && !(getCurrentPath().getParentPath().getParentPath().getParentPath().getLeaf() instanceof MemberSelectTree)) {
                                escapes[0] = true;
                            }
                            if (!receiver && !text && !declaration) escapes[0] = true;
                            return null;
                        }

                        @Override
                        public Void visitLambdaExpression(LambdaExpressionTree l, Void x) {
                            if (Declarations.uses(l, name)) escapes[0] = true;
                            return null;
                        }

                        @Override
                        public Void visitClass(ClassTree c, Void x) {
                            if (Declarations.uses(c, name)) escapes[0] = true;
                            return null;
                        }
                    }.scan(new TreePath(new TreePath(s.root()), body), null);
                    return !escapes[0];
                }
            }.run();
        }
    }

    static boolean placeholders(String format) {
        return format.replace("%%", "").replace("%n", "").contains("%");
    }

    static final class RedundantStringFormatCall extends Inspection {
        RedundantStringFormatCall() {
            super("RedundantStringFormatCall", "performance", Types.NONE,
                    "`String.format(\"text\")` with no arguments and no format specifiers, which is the text itself. The"
                            + " fix uses the literal.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.callsStatic(m, "String", "format") && m.getArguments().size() == 1) {
                        String f = Scan.string(m.getArguments().get(0));
                        if (f != null && !placeholders(f)) {
                            String lit = s.text(m.getArguments().get(0));
                            s.report(m, "Redundant 'String.format()' call", lit.contains("%") ? null : s.replace("Use the literal", m, lit));
                        }
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class ReplaceAllDot extends Inspection {
        ReplaceAllDot() {
            super("ReplaceAllDot", "suspicious", Types.NONE,
                    "`replaceAll(\".\", ...)`, `split(\".\")` and the like: the argument is a regular expression, in which"
                            + " `.` matches any character and `|` matches the empty string.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    if ((n.equals("replaceAll") || n.equals("replaceFirst") || n.equals("split") || n.equals("matches"))
                            && !m.getArguments().isEmpty() && Scan.receiver(m) != null) {
                        String re = Scan.string(m.getArguments().get(0));
                        if (".".equals(re) || "|".equals(re)) {
                            s.report(m.getArguments().get(0), "Suspicious regular expression '" + re + "' in '" + n + "()'");
                        }
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class StringConcatenationInLoops extends Inspection {
        StringConcatenationInLoops() {
            super("StringConcatenationInLoops", "performance", Types.OPTIONAL,
                    "A `String` built with `+=` (or `s = s + ...`) in a loop, which copies it each time; a `StringBuilder`"
                            + " does not.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitCompoundAssignment(CompoundAssignmentTree c, Void v) {
                    if (c.getKind() == Tree.Kind.PLUS_ASSIGNMENT) check(c.getVariable(), c);
                    return super.visitCompoundAssignment(c, v);
                }

                @Override
                public Void visitAssignment(AssignmentTree a, Void v) {
                    if (Scan.strip(a.getExpression()) instanceof BinaryTree b && b.getKind() == Tree.Kind.PLUS
                            && Scan.same(b.getLeftOperand(), a.getVariable())) {
                        check(a.getVariable(), a);
                    }
                    return super.visitAssignment(a, v);
                }

                private void check(ExpressionTree target, Tree op) {
                    if (!(target instanceof IdentifierTree i)) return;
                    VariableTree var = Expressions.local(getCurrentPath(), i.getName().toString());
                    boolean string = s.typed() ? s.is(target, "java.lang.String") : var != null && Scan.simpleName(var.getType()).equals("String");
                    if (var == null || !string) return;
                    for (TreePath p = getCurrentPath().getParentPath(); p != null; p = p.getParentPath()) {
                        Tree t = p.getLeaf();
                        if (t instanceof MethodTree || t instanceof LambdaExpressionTree || t instanceof ClassTree)
                            return;
                        if (Flow.loop(t) && !Flow.contains(t, x -> x == var)) {
                            s.report(op, "String concatenation '" + (op instanceof CompoundAssignmentTree ? "+=" : "+") + "' in a loop");
                            return;
                        }
                    }
                }
            }.run();
        }
    }

    static final class UnnecessaryStringEscape extends Inspection {
        UnnecessaryStringEscape() {
            super("UnnecessaryStringEscape", "style", Types.NONE,
                    "An escape that is not needed: `\\'` in a string, `\\\"` in a `char` literal or in a text block (unless"
                            + " it breaks up `\"\"\"`). The fix removes the backslash.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitLiteral(LiteralTree l, Void v) {
                    if (l.getKind() != Tree.Kind.STRING_LITERAL && l.getKind() != Tree.Kind.CHAR_LITERAL) return null;
                    String t = s.text(l);
                    int base = s.start(l);
                    boolean block = t.startsWith("\"\"\"");
                    char needless = l.getKind() == Tree.Kind.CHAR_LITERAL ? '"' : block ? '"' : '\'';
                    for (int k = 0; k + 1 < t.length(); k++) {
                        if (t.charAt(k) != '\\') continue;
                        char next = t.charAt(k + 1);
                        if (next == needless && !(block && breaksTripleQuote(t, k))) {
                            s.report(base + k, base + k + 2, "'\\" + next + "' is an unnecessary escape",
                                    Fix.delete("Remove the backslash", base + k, base + k + 1));
                        }
                        k++;
                    }
                    return null;
                }

                private boolean breaksTripleQuote(String t, int k) {
                    int before = 0, after = 0;
                    for (int i = k - 1; i >= 0 && t.charAt(i) == '"'; i--) before++;
                    for (int i = k + 2; i < t.length() && t.charAt(i) == '"'; i++) after++;
                    return before + after >= 2 || k + 2 + after >= t.length() - 3;
                }
            }.run();
        }
    }

    static final class UnnecessaryUnicodeEscape extends Inspection {
        UnnecessaryUnicodeEscape() {
            super("UnnecessaryUnicodeEscape", "style", Types.NONE,
                    "A `\\uXXXX` escape for a printable ASCII character in a literal, `\\u0041` for `A`. The fix writes the"
                            + " character.");
        }

        @Override
        protected void run(Scan s) {
            Pattern p = Pattern.compile("\\\\u+([0-9a-fA-F]{4})");
            new Visitor(s) {
                @Override
                public Void visitLiteral(LiteralTree l, Void v) {
                    if (l.getKind() != Tree.Kind.STRING_LITERAL && l.getKind() != Tree.Kind.CHAR_LITERAL) return null;
                    String t = s.text(l);
                    Matcher m = p.matcher(t);
                    while (m.find()) {
                        if (m.start() > 0 && backslashes(t, m.start()) % 2 == 1) continue;
                        int c = Integer.parseInt(m.group(1), 16);
                        if (c > 0x20 && c < 0x7f && c != '"' && c != '\'' && c != '\\') {
                            int at = s.start(l) + m.start();
                            s.report(at, at + m.group().length(), "Unnecessary Unicode escape for '" + (char) c + "'",
                                    Fix.replace("Use the character", at, at + m.group().length(), String.valueOf((char) c)));
                        }
                    }
                    return null;
                }

                private int backslashes(String t, int at) {
                    int n = 0;
                    for (int i = at - 1; i >= 0 && t.charAt(i) == '\\'; i--) n++;
                    return n;
                }
            }.run();
        }
    }

    static final class TrailingWhitespacesInTextBlock extends Inspection {
        TrailingWhitespacesInTextBlock() {
            super("TrailingWhitespacesInTextBlock", "suspicious", Types.NONE,
                    "Spaces at the end of a line in a text block, which the compiler strips; `\\s` or `\\040` keeps one.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitLiteral(LiteralTree l, Void v) {
                    String t = s.text(l);
                    if (l.getKind() == Tree.Kind.STRING_LITERAL && t.startsWith("\"\"\"")) {
                        int base = s.start(l);
                        Matcher m = Pattern.compile("[ \\t]+(?=\\r?\\n)").matcher(t);
                        while (m.find()) {
                            int ls = t.lastIndexOf('\n', m.start() - 1);
                            if (ls >= 0 && !t.substring(ls + 1, m.start()).isBlank()) {
                                s.report(base + m.start(), base + m.end(), "Trailing whitespace in a text block", null);
                            }
                        }
                    }
                    return null;
                }
            }.run();
        }
    }

    private static final Map<String, String> CHARSETS = Map.of(
            "UTF-8", "UTF_8", "US-ASCII", "US_ASCII", "ISO-8859-1", "ISO_8859_1", "UTF-16", "UTF_16",
            "UTF-16BE", "UTF_16BE", "UTF-16LE", "UTF_16LE");

    static final class CharsetObjectCanBeUsed extends Inspection {
        CharsetObjectCanBeUsed() {
            super("CharsetObjectCanBeUsed", "performance", Types.NONE,
                    "A standard charset named by a string, `getBytes(\"UTF-8\")`, where `StandardCharsets.UTF_8` can be"
                            + " passed: no lookup, and no `UnsupportedEncodingException`. The fix uses the constant.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    boolean candidate = n.equals("getBytes") && m.getArguments().size() == 1 && Scan.receiver(m) != null
                            || Scan.callsStatic(m, "Charset", "forName") && m.getArguments().size() == 1
                            || (Scan.callsStatic(m, "URLEncoder", "encode") || Scan.callsStatic(m, "URLDecoder", "decode")) && m.getArguments().size() == 2
                            || n.equals("toString") && m.getArguments().size() == 1 && Scan.receiver(m) != null;
                    if (candidate) last(m.getArguments(), Scan.callsStatic(m, "Charset", "forName") ? m : null);
                    return super.visitMethodInvocation(m, v);
                }

                @Override
                public Void visitNewClass(NewClassTree c, Void v) {
                    String t = Scan.simpleName(c.getIdentifier());
                    if ((t.equals("String") && (c.getArguments().size() == 2 || c.getArguments().size() == 4))
                            || (t.equals("InputStreamReader") || t.equals("OutputStreamWriter")) && c.getArguments().size() == 2) {
                        last(c.getArguments(), null);
                    }
                    return super.visitNewClass(c, v);
                }

                private void last(List<? extends ExpressionTree> args, Tree whole) {
                    ExpressionTree a = args.get(args.size() - 1);
                    String name = Scan.string(a);
                    if (name == null) return;
                    String constant = CHARSETS.get(name.toUpperCase(Locale.ROOT));
                    if (constant == null) return;
                    Tree target = whole != null ? whole : a;
                    s.report(a, "'StandardCharsets." + constant + "' can be used instead of \"" + name + "\"",
                            s.replace("Use StandardCharsets." + constant, target, "java.nio.charset.StandardCharsets." + constant));
                }
            }.run();
        }
    }

    static final class SystemGetProperty extends Inspection {
        private static final Map<String, String> PROPERTIES = Map.of(
                "line.separator", "System.lineSeparator()",
                "file.separator", "java.io.File.separator",
                "path.separator", "java.io.File.pathSeparator",
                "file.encoding", "java.nio.charset.Charset.defaultCharset().name()");

        SystemGetProperty() {
            super("SystemGetProperty", "style", Types.NONE,
                    "`System.getProperty()` of a property the library gives directly: `line.separator` is"
                            + " `System.lineSeparator()`, `file.separator` and `path.separator` are `File.separator` and"
                            + " `File.pathSeparator`. The fix uses them.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.callsStatic(m, "System", "getProperty") && m.getArguments().size() == 1) {
                        String key = Scan.string(m.getArguments().get(0));
                        String repl = key == null ? null : PROPERTIES.get(key);
                        if (repl != null)
                            s.report(m, "'System.getProperty(\"" + key + "\")' can be '" + repl.replace("java.io.", "").replace("java.nio.charset.", "") + "'",
                                    s.replace("Simplify", m, repl));
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    private static final Pattern SPEC = Pattern.compile("%(\\d+\\$|<)?([-#+ 0,(]*)?(\\d+)?(\\.\\d+)?([tT])?(.)?");

    /**
     * The number of arguments {@code format} needs, or -1 if it is malformed.
     */
    static int needs(String format) {
        Matcher m = SPEC.matcher(format);
        int ordinal = 0, explicit = 0;
        while (m.find()) {
            String conv = m.group(6);
            if (conv == null) return -1;
            if (m.group(5) != null) {
                if (!"HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc".contains(conv)) return -1;
            } else if (conv.equals("%") || conv.equals("n")) {
                continue;
            } else if (!"bBhHsScCdoxXeEfgGaA".contains(conv)) {
                return -1;
            }
            if (m.group(1) == null) ordinal++;
            else if (!m.group(1).equals("<"))
                explicit = Math.max(explicit, Integer.parseInt(m.group(1).replace("$", "")));
        }
        return Math.max(ordinal, explicit);
    }

    static final class MalformedFormatString extends Inspection {
        MalformedFormatString() {
            super("MalformedFormatString", "correctness", Types.OPTIONAL,
                    "A format string (`String.format`, `printf`, `formatted`) with an unknown conversion, or with more or"
                            + " fewer specifiers than arguments.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    List<? extends ExpressionTree> args = m.getArguments();
                    int f = -1;
                    if ((Scan.callsStatic(m, "String", "format") || n.equals("printf") && Scan.receiver(m) != null) && !args.isEmpty()) {
                        f = Scan.string(args.get(0)) != null ? 0 : args.size() > 1 && Scan.string(args.get(1)) != null && locale(args.get(0)) ? 1 : -1;
                    }
                    if (f >= 0) {
                        check(args.get(f), args.subList(f + 1, args.size()));
                    } else if (n.equals("formatted") && Scan.string(Scan.receiver(m)) != null) {
                        check(Scan.receiver(m), args);
                    }
                    return super.visitMethodInvocation(m, v);
                }

                private boolean locale(ExpressionTree e) {
                    return s.typed() ? s.is(e, "java.util.Locale") : e.toString().startsWith("Locale.") || e.toString().contains("Locale");
                }

                private void check(ExpressionTree format, List<? extends ExpressionTree> args) {
                    String fmt = Scan.string(format);
                    int need = needs(fmt);
                    if (need < 0) {
                        s.report(format, "Malformed format string");
                        return;
                    }
                    if (args.size() == 1 && (Scan.strip(args.get(0)) instanceof NewArrayTree || s.isArray(args.get(0))
                            || !s.typed() && Scan.strip(args.get(0)) instanceof IdentifierTree)) {
                        return;
                    }
                    if (need != args.size()) {
                        s.report(format, "Format string needs " + need + " argument" + (need == 1 ? "" : "s") + " but gets " + args.size());
                    }
                }
            }.run();
        }
    }

    static final class ReplaceOnLiteralHasNoEffect extends Inspection {
        ReplaceOnLiteralHasNoEffect() {
            super("ReplaceOnLiteralHasNoEffect", "suspicious", Types.NONE,
                    "`\"text\".replace(\"x\", ...)` on a literal that does not contain what it replaces, which returns the"
                            + " literal unchanged.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String lit = Scan.string(Scan.receiver(m));
                    if (lit != null && Scan.calls(m, "replace", 2)) {
                        ExpressionTree a = Scan.strip(m.getArguments().get(0));
                        String target = Scan.string(a);
                        if (target == null && a instanceof LiteralTree l && l.getKind() == Tree.Kind.CHAR_LITERAL)
                            target = String.valueOf(l.getValue());
                        if (target != null && !lit.contains(target))
                            s.report(m, "'replace()' has no effect: the literal does not contain \"" + target + "\"");
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    private static final Map<String, String> PARSE = Map.of(
            "Integer", "parseInt", "Long", "parseLong", "Short", "parseShort", "Byte", "parseByte",
            "Double", "parseDouble", "Float", "parseFloat", "Boolean", "parseBoolean");

    /**
     * The wrapper class a {@code new Integer(x)} or {@code Integer.valueOf(x)} creates, and its argument, or null.
     */
    static String[] temporary(Scan s, ExpressionTree e) {
        e = Scan.strip(e);
        if (e instanceof NewClassTree n && n.getArguments().size() == 1 && n.getClassBody() == null) {
            String t = Scan.simpleName(n.getIdentifier());
            if (PARSE.containsKey(t) || t.equals("Character")) return new String[]{t, s.text(n.getArguments().get(0))};
        }
        if (e instanceof MethodInvocationTree m && m.getArguments().size() == 1 && Scan.name(m).equals("valueOf")) {
            ExpressionTree r = Scan.receiver(m);
            String t = r == null ? "" : Scan.simpleName(r);
            if (PARSE.containsKey(t) || t.equals("Character")) return new String[]{t, s.text(m.getArguments().get(0))};
        }
        return null;
    }

    static final class UnnecessaryTemporaryOnConversionToString extends Inspection {
        UnnecessaryTemporaryOnConversionToString() {
            super("UnnecessaryTemporaryOnConversionToString", "performance", Types.NONE,
                    "`new Integer(x).toString()` or `Integer.valueOf(x).toString()`, which creates an object only to"
                            + " convert it: `Integer.toString(x)`. The fix rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.calls(m, "toString", 0)) {
                        String[] t = temporary(s, Scan.receiver(m));
                        if (t != null) {
                            s.report(m, "Unnecessary temporary object", s.replace("Use " + t[0] + ".toString()", m, t[0] + ".toString(" + t[1] + ")"));
                        }
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class UnnecessaryTemporaryOnConversionFromString extends Inspection {
        UnnecessaryTemporaryOnConversionFromString() {
            super("UnnecessaryTemporaryOnConversionFromString", "performance", Types.OPTIONAL,
                    "`Integer.valueOf(s).intValue()` or `new Integer(s).intValue()` on a `String`, which is"
                            + " `Integer.parseInt(s)`. Without types, only string literals are recognized. The fix rewrites"
                            + " it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    if (m.getArguments().isEmpty() && n.endsWith("Value")) {
                        ExpressionTree r = Scan.strip(Scan.receiver(m));
                        String[] t = temporary(s, r);
                        ExpressionTree arg = t == null ? null : r instanceof NewClassTree nc ? nc.getArguments().get(0)
                                : ((MethodInvocationTree) r).getArguments().get(0);
                        boolean string = arg != null && (Scan.string(arg) != null || s.is(arg, "java.lang.String"));
                        if (t != null && PARSE.containsKey(t[0]) && string && n.equals(t[0].toLowerCase(Locale.ROOT).replace("integer", "int") + "Value")) {
                            s.report(m, "Unnecessary temporary object", s.replace("Use " + t[0] + "." + PARSE.get(t[0]) + "()", m,
                                    t[0] + "." + PARSE.get(t[0]) + "(" + t[1] + ")"));
                        }
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class BooleanConstructor extends Inspection {
        BooleanConstructor() {
            super("BooleanConstructor", "performance", Types.NONE,
                    "`new Boolean(x)`, deprecated for removal: `Boolean.valueOf(x)` returns the shared instances. The fix"
                            + " rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitNewClass(NewClassTree c, Void v) {
                    if (Scan.simpleName(c.getIdentifier()).equals("Boolean") && c.getArguments().size() == 1 && c.getClassBody() == null) {
                        ExpressionTree a = c.getArguments().get(0);
                        Boolean b = Scan.bool(a);
                        String repl = b != null ? "Boolean." + String.valueOf(b).toUpperCase(Locale.ROOT) : "Boolean.valueOf(" + s.text(a) + ")";
                        s.report(c, "Boolean constructor call", s.replace("Use " + repl, c, repl));
                    }
                    return super.visitNewClass(c, v);
                }
            }.run();
        }
    }

    static final class CachedNumberConstructorCall extends Inspection {
        CachedNumberConstructorCall() {
            super("CachedNumberConstructorCall", "performance", Types.NONE,
                    "`new Integer(x)` and the like for `Long`, `Short`, `Byte` and `Character`, deprecated for removal:"
                            + " `valueOf` uses the cache. The fix rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitNewClass(NewClassTree c, Void v) {
                    String t = Scan.simpleName(c.getIdentifier());
                    if ((t.equals("Integer") || t.equals("Long") || t.equals("Short") || t.equals("Byte") || t.equals("Character"))
                            && c.getArguments().size() == 1 && c.getClassBody() == null) {
                        s.report(c, "Number constructor call with a cached value", s.replace("Use " + t + ".valueOf()", c,
                                t + ".valueOf(" + s.text(c.getArguments().get(0)) + ")"));
                    }
                    return super.visitNewClass(c, v);
                }
            }.run();
        }
    }

    static final class UnpredictableBigDecimalConstructorCall extends Inspection {
        UnpredictableBigDecimalConstructorCall() {
            super("UnpredictableBigDecimalConstructorCall", "correctness", Types.NONE,
                    "`new BigDecimal(0.1)` with a `double` literal, which is the binary approximation"
                            + " (0.1000000000000000055...); `new BigDecimal(\"0.1\")` is exact. The fix uses the string.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitNewClass(NewClassTree c, Void v) {
                    if (Scan.simpleName(c.getIdentifier()).equals("BigDecimal") && !c.getArguments().isEmpty()
                            && Scan.strip(c.getArguments().get(0)) instanceof LiteralTree l
                            && (l.getKind() == Tree.Kind.DOUBLE_LITERAL || l.getKind() == Tree.Kind.FLOAT_LITERAL)) {
                        String text = s.text(l).replaceAll("[dDfF]$", "");
                        s.report(c, "Unpredictable 'new BigDecimal()' with a floating-point literal",
                                s.replace("Use a string", l, "\"" + text + "\""));
                    }
                    return super.visitNewClass(c, v);
                }
            }.run();
        }
    }

    static final class MismatchedStringCase extends Inspection {
        MismatchedStringCase() {
            super("MismatchedStringCase", "correctness", Types.NONE,
                    "`s.toUpperCase().equals(\"abc\")` and the like: a string in one case compared with a literal that has"
                            + " letters of the other case, which never matches.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    if (m.getArguments().size() == 1 && (n.equals("equals") || n.equals("startsWith") || n.equals("endsWith")
                            || n.equals("contains"))) {
                        check(m, Scan.receiver(m), m.getArguments().get(0));
                        if (n.equals("equals")) check(m, m.getArguments().get(0), Scan.receiver(m));
                    }
                    return super.visitMethodInvocation(m, v);
                }

                private void check(MethodInvocationTree m, ExpressionTree cased, ExpressionTree literal) {
                    if (!(Scan.strip(cased) instanceof MethodInvocationTree c)) return;
                    String lit = Scan.string(literal);
                    if (lit == null) return;
                    boolean upper = Scan.name(c).equals("toUpperCase"), lower = Scan.name(c).equals("toLowerCase");
                    if (!upper && !lower || c.getArguments().size() > 1) return;
                    boolean mismatch = upper ? !lit.equals(lit.toUpperCase(Locale.ROOT)) : !lit.equals(lit.toLowerCase(Locale.ROOT));
                    if (mismatch)
                        s.report(m, "Result of '" + Scan.name(c) + "()' is compared with \"" + lit + "\", which never matches");
                }
            }.run();
        }
    }

    static final class SuspiciousDateFormat extends Inspection {
        SuspiciousDateFormat() {
            super("SuspiciousDateFormat", "suspicious", Types.NONE,
                    "A date pattern with `YYYY` (week-based year) next to month and day, or `DD` (day of year) next to a"
                            + " month: almost always meant as `yyyy` and `dd`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitNewClass(NewClassTree c, Void v) {
                    if (Scan.simpleName(c.getIdentifier()).equals("SimpleDateFormat") && !c.getArguments().isEmpty())
                        check(c.getArguments().get(0));
                    return super.visitNewClass(c, v);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.callsStatic(m, "DateTimeFormatter", "ofPattern") && !m.getArguments().isEmpty())
                        check(m.getArguments().get(0));
                    return super.visitMethodInvocation(m, v);
                }

                private void check(ExpressionTree e) {
                    String p = Scan.string(e);
                    if (p == null) return;
                    String bare = p.replaceAll("'[^']*'", "");
                    boolean month = bare.contains("M"), weeks = bare.contains("w");
                    if (bare.contains("Y") && month && bare.contains("d") && !weeks) {
                        s.report(e, "'Y' (week year) in a date with month and day; did you mean 'y'?");
                    } else if (bare.contains("D") && month) {
                        s.report(e, "'D' (day of year) in a date with a month; did you mean 'd'?");
                    }
                }
            }.run();
        }
    }

    static final class ToArrayCallWithZeroLengthArrayArgument extends Inspection {
        ToArrayCallWithZeroLengthArrayArgument() {
            super("ToArrayCallWithZeroLengthArrayArgument", "performance", Types.NONE,
                    "`c.toArray(new T[c.size()])`, which is slower than `c.toArray(new T[0])` and not safe if `c` changes"
                            + " meanwhile. The fix passes an empty array.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    ExpressionTree r = Scan.receiver(m);
                    if (Scan.calls(m, "toArray", 1) && r != null && Scan.strip(m.getArguments().get(0)) instanceof NewArrayTree a
                            && a.getDimensions().size() == 1 && Scan.strip(a.getDimensions().get(0)) instanceof MethodInvocationTree size
                            && Scan.calls(size, "size", 0) && Scan.receiver(size) != null && Scan.same(Scan.receiver(size), r)) {
                        s.report(a, "Call to 'toArray()' with a pre-sized array", s.replace("Use an empty array", a.getDimensions().get(0), "0"));
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class IOStreamConstructor extends Inspection {
        IOStreamConstructor() {
            super("IOStreamConstructor", "performance", Types.NONE,
                    "`new FileInputStream(...)` or `new FileOutputStream(...)`, which have a finalizer;"
                            + " `Files.newInputStream()` and `Files.newOutputStream()` do not. A stream of a"
                            + " `FileDescriptor` (`FileDescriptor.out`), which `Files` cannot open, is left alone.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitNewClass(NewClassTree c, Void v) {
                    String t = Scan.simpleName(c.getIdentifier());
                    if ((t.equals("FileInputStream") || t.equals("FileOutputStream")) && c.getArguments().size() == 1 && c.getClassBody() == null
                            && !c.getArguments().get(0).toString().contains("FileDescriptor")) {
                        s.report(c, "'" + t + "' can be replaced with 'Files.new" + (t.startsWith("FileIn") ? "Input" : "Output") + "Stream()'");
                    }
                    return super.visitNewClass(c, v);
                }
            }.run();
        }
    }
}
