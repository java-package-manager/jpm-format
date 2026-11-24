package dev.jpm.format.inspections;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IntelliJ's inspections about test assertions and logging calls.
 */
final class Library {
    private Library() {
    }

    static List<Inspection> all() {
        return List.of(
                new MisorderedAssertEqualsArguments(),
                new SimplifiableAssertion(),
                new LoggerInitializedWithForeignClass(),
                new LoggingPlaceholderCountMatchesArgumentCount(),
                new StringConcatenationArgumentToLogCall());
    }

    /**
     * The test framework a file uses, from its imports: "junit5", "junit4", "testng" or null.
     */
    static String framework(Scan s) {
        for (ImportTree i : s.root().getImports()) {
            String n = i.getQualifiedIdentifier().toString();
            if (n.startsWith("org.junit.jupiter.")) return "junit5";
            if (n.startsWith("org.testng.")) return "testng";
            if (n.startsWith("org.junit.") || n.startsWith("junit.framework.")) return "junit4";
        }
        return null;
    }

    /**
     * True if {@code e} looks like a constant: a literal, a class literal or an {@code UPPER_CASE} name.
     */
    static boolean constantLike(ExpressionTree e) {
        e = Scan.strip(e);
        if (e instanceof LiteralTree) return !Scan.isNull(e);
        if (e instanceof UnaryTree u && u.getKind() == Tree.Kind.UNARY_MINUS) return constantLike(u.getExpression());
        String name = e instanceof IdentifierTree i ? i.getName().toString()
                : e instanceof MemberSelectTree m ? m.getIdentifier().toString() : null;
        return name != null && (name.equals("class") || name.length() > 1 && name.equals(name.toUpperCase()) && Character.isLetter(name.charAt(0)));
    }

    static final class MisorderedAssertEqualsArguments extends Inspection {
        MisorderedAssertEqualsArguments() {
            super("MisorderedAssertEqualsArguments", "style", Types.NONE,
                    "`assertEquals(actual, CONSTANT)` with the constant second: JUnit takes the expected value first, so"
                            + " failure messages come out backwards. TestNG, which takes it second, is recognized by its"
                            + " imports.");
        }

        @Override
        protected void run(Scan s) {
            String fw = framework(s);
            if (fw == null || fw.equals("testng")) return;
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    if (n.equals("assertEquals") || n.equals("assertNotEquals") || n.equals("assertSame")
                            || n.equals("assertNotSame") || n.equals("assertArrayEquals")) {
                        List<? extends ExpressionTree> a = m.getArguments();
                        int first = fw.equals("junit4") && a.size() == 3 && (Scan.string(a.get(0)) != null) ? 1 : 0;
                        if (a.size() >= first + 2 && !(fw.equals("junit5") && a.size() == 3 && Scan.integer(a.get(2)) == null
                                && !(Scan.string(a.get(2)) != null || a.get(2) instanceof com.sun.source.tree.LambdaExpressionTree)) && a.size() <= 3) {
                            ExpressionTree expected = a.get(first), actual = a.get(first + 1);
                            if (constantLike(actual) && !constantLike(expected)) {
                                s.report(m, "Arguments to '" + n + "()' are in the wrong order");
                            }
                        }
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class SimplifiableAssertion extends Inspection {
        SimplifiableAssertion() {
            super("SimplifiableAssertion", "style", Types.NONE,
                    "An assertion a more specific one says better: `assertTrue(a.equals(b))` is `assertEquals`,"
                            + " `assertTrue(!x)` is `assertFalse(x)`, `assertTrue(x == null)` is `assertNull(x)`,"
                            + " `assertEquals(true, x)` is `assertTrue(x)`. The fix rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            if (framework(s) == null) return;
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    List<? extends ExpressionTree> a = m.getArguments();
                    String prefix = s.text(m.getMethodSelect()).substring(0, s.text(m.getMethodSelect()).length() - n.length());
                    String repl = null;
                    if ((n.equals("assertTrue") || n.equals("assertFalse")) && a.size() == 1) {
                        boolean t = n.equals("assertTrue");
                        ExpressionTree c = Scan.strip(a.get(0));
                        if (c instanceof UnaryTree u && u.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) {
                            repl = (t ? "assertFalse(" : "assertTrue(") + s.text(Scan.strip(u.getExpression())) + ")";
                        } else if (c instanceof BinaryTree b && (b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO)
                                && (Scan.isNull(b.getLeftOperand()) || Scan.isNull(b.getRightOperand()))) {
                            ExpressionTree x = Scan.isNull(b.getRightOperand()) ? b.getLeftOperand() : b.getRightOperand();
                            boolean isNull = (b.getKind() == Tree.Kind.EQUAL_TO) == t;
                            repl = (isNull ? "assertNull(" : "assertNotNull(") + s.text(x) + ")";
                        } else if (t && c instanceof MethodInvocationTree eq && Scan.calls(eq, "equals", 1) && Scan.receiver(eq) != null) {
                            repl = "assertEquals(" + s.text(Scan.receiver(eq)) + ", " + s.text(eq.getArguments().get(0)) + ")";
                        }
                    } else if (n.equals("assertEquals") && a.size() == 2) {
                        Boolean b = Scan.bool(a.get(0));
                        if (b != null) repl = (b ? "assertTrue(" : "assertFalse(") + s.text(a.get(1)) + ")";
                        else if (Scan.isNull(a.get(0))) repl = "assertNull(" + s.text(a.get(1)) + ")";
                    }
                    if (repl != null) {
                        s.report(m, "Assertion can be '" + repl.substring(0, repl.indexOf('(')) + "()'",
                                s.replace("Simplify the assertion", m, prefix + repl));
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static boolean loggerFactory(MethodInvocationTree m) {
        return Scan.callsStatic(m, "LoggerFactory", "getLogger") || Scan.callsStatic(m, "LogManager", "getLogger")
                || Scan.callsStatic(m, "Logger", "getLogger") || Scan.callsStatic(m, "LogFactory", "getLog");
    }

    static final class LoggerInitializedWithForeignClass extends Inspection {
        LoggerInitializedWithForeignClass() {
            super("LoggerInitializedWithForeignClass", "suspicious", Types.NONE,
                    "A logger created with another class, `LoggerFactory.getLogger(Other.class)`, usually copied from"
                            + " another file: its messages are logged under the wrong name.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (loggerFactory(m) && m.getArguments().size() == 1 && Scan.strip(m.getArguments().get(0)) instanceof MemberSelectTree lit
                            && lit.getIdentifier().contentEquals("class")) {
                        String named = Scan.simpleName(lit.getExpression());
                        boolean enclosing = false;
                        for (TreePath p = getCurrentPath(); p != null; p = p.getParentPath()) {
                            if (p.getLeaf() instanceof ClassTree c && c.getSimpleName().contentEquals(named))
                                enclosing = true;
                            if (p.getLeaf() instanceof NewClassTree) enclosing = true;
                        }
                        if (!enclosing) s.report(lit, "Logger initialized with foreign class '" + named + ".class'");
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static boolean logCall(Scan s, MethodInvocationTree m) {
        String n = Scan.name(m);
        if (!(n.equals("trace") || n.equals("debug") || n.equals("info") || n.equals("warn") || n.equals("error")))
            return false;
        ExpressionTree r = Scan.receiver(m);
        if (r == null) return false;
        if (s.typed()) return s.isA(r, "org.slf4j.Logger") || s.isA(r, "org.apache.logging.log4j.Logger");
        return Scan.simpleName(r).matches("(?i)log|logger");
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("(\\\\*)\\{}");

    static final class LoggingPlaceholderCountMatchesArgumentCount extends Inspection {
        LoggingPlaceholderCountMatchesArgumentCount() {
            super("LoggingPlaceholderCountMatchesArgumentCount", "correctness", Types.OPTIONAL,
                    "An SLF4J or Log4j 2 call whose message has a different number of `{}` placeholders than it has"
                            + " arguments (a last exception argument does not count). Without types, loggers are recognized"
                            + " by the names `log` and `logger`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (logCall(s, m) && !m.getArguments().isEmpty()) {
                        List<? extends ExpressionTree> a = m.getArguments();
                        int f = Scan.string(a.get(0)) != null ? 0 : a.size() > 1 && Scan.string(a.get(1)) != null ? 1 : -1;
                        if (f >= 0) {
                            String msg = Scan.string(a.get(f));
                            int holes = 0;
                            Matcher pm = PLACEHOLDER.matcher(msg);
                            while (pm.find()) if (pm.group(1).length() % 2 == 0) holes++;
                            List<? extends ExpressionTree> args = a.subList(f + 1, a.size());
                            int n = args.size();
                            if (n == 1 && (Scan.strip(args.get(0)) instanceof com.sun.source.tree.NewArrayTree || s.isArray(args.get(0))))
                                return super.visitMethodInvocation(m, v);
                            if (n == holes + 1 && throwable(args.get(n - 1))) n--;
                            if (n != holes) {
                                s.report(a.get(f), "Message has " + holes + " placeholder" + (holes == 1 ? "" : "s") + " but " + n + " argument" + (n == 1 ? "" : "s"));
                            }
                        }
                    }
                    return super.visitMethodInvocation(m, v);
                }

                private boolean throwable(ExpressionTree e) {
                    if (s.typed()) return s.isA(e, "java.lang.Throwable");
                    ExpressionTree x = Scan.strip(e);
                    if (x instanceof NewClassTree n)
                        return Expressions.throwableName(Scan.simpleName(n.getIdentifier()));
                    if (x instanceof IdentifierTree i) {
                        for (TreePath p = getCurrentPath(); p != null; p = p.getParentPath()) {
                            if (p.getLeaf() instanceof com.sun.source.tree.CatchTree c && c.getParameter().getName().contentEquals(i.getName()))
                                return true;
                        }
                        return i.getName().toString().matches("e|ex|exc|t|th|throwable|exception|cause|error|err");
                    }
                    return false;
                }
            }.run();
        }
    }

    static final class StringConcatenationArgumentToLogCall extends Inspection {
        StringConcatenationArgumentToLogCall() {
            super("StringConcatenationArgumentToLogCall", "performance", Types.OPTIONAL,
                    "A `trace`, `debug` or `info` message built by concatenation, `log.debug(\"x = \" + x)`, which builds"
                            + " the string even when the level is off; `log.debug(\"x = {}\", x)` does not.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    if (logCall(s, m) && (n.equals("trace") || n.equals("debug") || n.equals("info")) && !m.getArguments().isEmpty()
                            && Scan.strip(m.getArguments().get(0)) instanceof BinaryTree b && b.getKind() == Tree.Kind.PLUS
                            && !constant(b)) {
                        s.report(b, "String concatenation as an argument to a '" + n + "()' call");
                    }
                    return super.visitMethodInvocation(m, v);
                }

                private boolean constant(ExpressionTree e) {
                    e = Scan.strip(e);
                    if (e instanceof LiteralTree) return true;
                    if (e instanceof BinaryTree b) return constant(b.getLeftOperand()) && constant(b.getRightOperand());
                    return constantLike(e);
                }
            }.run();
        }
    }
}
