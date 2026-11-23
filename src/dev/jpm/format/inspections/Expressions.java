package dev.jpm.format.inspections;

import com.sun.source.tree.AssertTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import dev.jpm.format.api.Fix;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * IntelliJ's inspections about expressions: pointless and constant ones, comparisons, numbers, objects and threads.
 */
final class Expressions {
    private Expressions() {
    }

    static List<Inspection> all() {
        return List.of(
                new SimplifiableConditionalExpression(),
                new ConstantConditionalExpression(),
                new ConditionalExpressionWithIdenticalBranches(),
                new ManualMinMaxCalculation(),
                new DoubleNegation(),
                new UnaryPlus(),
                new UnnecessaryUnaryMinus(),
                new PointlessArithmeticExpression(),
                new PointlessBitwiseExpression(),
                new ComparisonToNaN(),
                new DivideByZero(),
                new ShiftOutOfRange(),
                new NumericOverflow(),
                new IncompatibleMask(),
                new SillyAssignment(),
                new ExpressionComparedToItself(),
                new EqualsWithItself(),
                new ComparatorResultComparison(),
                new PointlessNullCheck(),
                new AssertWithSideEffects(),
                new MathRandomCastToInt(),
                new NewObjectEquality(),
                new TrivialStringConcatenation(),
                new CollectionAddedToSelf(),
                new InstantiatingObjectToGetClassObject(),
                new ClassGetClass(),
                new RedundantClassCall(),
                new ThrowableNotThrown(),
                new ThrowablePrintStackTrace(),
                new ThrowablePrintedToSystemOut(),
                new NoExplicitFinalizeCalls(),
                new SystemRunFinalizersOnExit(),
                new ThreadRun(),
                new BusyWait(),
                new SynchronizeOnNonFinalField(),
                new SynchronizationOnLocalVariableOrMethodParameter(),
                new SynchronizationOnGetClass(),
                new NonAtomicOperationOnVolatileField(),
                new OptionalAssignedToNull(),
                new OptionalOfNullableMisuse(),
                new NumberEquality(),
                new ArrayEquals(),
                new ArrayHashCode(),
                new ImplicitArrayToString(),
                new IntegerDivisionInFloatingPointContext(),
                new IntegerMultiplicationImplicitCastToLong(),
                new MathRoundingWithIntArgument(),
                new ComparisonOfShortAndChar(),
                new LossyConversionCompoundAssignment(),
                new AccessStaticViaInstance(),
                new BigDecimalMethodWithoutRoundingCalled());
    }

    /**
     * {@code e} as an operand: with parentheses unless it is a primary expression.
     */
    static String operand(Scan s, ExpressionTree e) {
        ExpressionTree x = Scan.strip(e);
        String t = s.text(x);
        return x instanceof IdentifierTree || x instanceof LiteralTree || x instanceof MethodInvocationTree
                || x instanceof MemberSelectTree || x instanceof com.sun.source.tree.ArrayAccessTree
                || x instanceof NewClassTree ? t : "(" + t + ")";
    }

    static boolean comparison(Tree.Kind k) {
        return k == Tree.Kind.EQUAL_TO || k == Tree.Kind.NOT_EQUAL_TO || k == Tree.Kind.LESS_THAN
                || k == Tree.Kind.LESS_THAN_EQUAL || k == Tree.Kind.GREATER_THAN || k == Tree.Kind.GREATER_THAN_EQUAL;
    }

    static final class SimplifiableConditionalExpression extends Inspection {
        SimplifiableConditionalExpression() {
            super("SimplifiableConditionalExpression", "complexity", Types.NONE,
                    "A `?:` with a `boolean` literal branch: `c ? true : false` is `c`, `c ? false : true` is `!c`,"
                            + " `c ? x : false` is `c && x`, `c ? true : x` is `c || x`. The fix rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitConditionalExpression(ConditionalExpressionTree c, Void v) {
                    Boolean t = Scan.bool(c.getTrueExpression()), f = Scan.bool(c.getFalseExpression());
                    String cond = operand(s, c.getCondition());
                    String repl = null;
                    if (t != null && f != null && t != f) repl = t ? s.text(Scan.strip(c.getCondition())) : "!" + cond;
                    else if (t == null && Boolean.FALSE.equals(f)) repl = cond + " && " + operand(s, c.getTrueExpression());
                    else if (Boolean.TRUE.equals(t) && f == null) repl = cond + " || " + operand(s, c.getFalseExpression());
                    if (repl != null) s.report(c, "'" + s.text(c) + "' can be simplified to '" + repl + "'",
                            s.replace("Simplify", c, repl));
                    return super.visitConditionalExpression(c, v);
                }
            }.run();
        }
    }

    static final class ConstantConditionalExpression extends Inspection {
        ConstantConditionalExpression() {
            super("ConstantConditionalExpression", "complexity", Types.NONE,
                    "A `?:` whose condition is a `boolean` literal, `true ? a : b`. The fix keeps the branch it picks.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitConditionalExpression(ConditionalExpressionTree c, Void v) {
                    Boolean b = Scan.bool(c.getCondition());
                    if (b != null) {
                        ExpressionTree keep = b ? c.getTrueExpression() : c.getFalseExpression();
                        s.report(c, "Condition is always " + b, s.replace("Simplify", c, s.text(keep)));
                    }
                    return super.visitConditionalExpression(c, v);
                }
            }.run();
        }
    }

    static final class ConditionalExpressionWithIdenticalBranches extends Inspection {
        ConditionalExpressionWithIdenticalBranches() {
            super("ConditionalExpressionWithIdenticalBranches", "complexity", Types.NONE,
                    "A `?:` whose two branches are the same expression, `c ? x : x`. The fix keeps one.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitConditionalExpression(ConditionalExpressionTree c, Void v) {
                    if (Scan.same(c.getTrueExpression(), c.getFalseExpression())) {
                        s.report(c, "Conditional expression has identical branches",
                                Scan.pure(c.getCondition()) ? s.replace("Simplify", c, s.text(c.getTrueExpression())) : null);
                    }
                    return super.visitConditionalExpression(c, v);
                }
            }.run();
        }
    }

    static final class ManualMinMaxCalculation extends Inspection {
        ManualMinMaxCalculation() {
            super("ManualMinMaxCalculation", "style", Types.NONE,
                    "`a > b ? a : b` and the like, which is `Math.max(a, b)` or `Math.min(a, b)`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitConditionalExpression(ConditionalExpressionTree c, Void v) {
                    if (Scan.strip(c.getCondition()) instanceof BinaryTree b && Scan.pure(b)) {
                        ExpressionTree l = b.getLeftOperand(), r = b.getRightOperand();
                        ExpressionTree t = c.getTrueExpression(), f = c.getFalseExpression();
                        boolean straight = Scan.same(l, t) && Scan.same(r, f), swapped = Scan.same(l, f) && Scan.same(r, t);
                        Tree.Kind k = b.getKind();
                        boolean greater = k == Tree.Kind.GREATER_THAN || k == Tree.Kind.GREATER_THAN_EQUAL;
                        boolean less = k == Tree.Kind.LESS_THAN || k == Tree.Kind.LESS_THAN_EQUAL;
                        if ((greater || less) && (straight || swapped)) {
                            String fn = greater == straight ? "max" : "min";
                            s.report(c, "Can be replaced with 'Math." + fn + "()'", s.replace("Use Math." + fn, c,
                                    "Math." + fn + "(" + s.text(Scan.strip(l)) + ", " + s.text(Scan.strip(r)) + ")"));
                        }
                    }
                    return super.visitConditionalExpression(c, v);
                }
            }.run();
        }
    }

    static final class DoubleNegation extends Inspection {
        DoubleNegation() {
            super("DoubleNegation", "complexity", Types.NONE,
                    "A negation of a negation, `!!x` or `!(a != b)`. The fix removes both.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitUnary(UnaryTree u, Void v) {
                    if (u.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) {
                        ExpressionTree x = Scan.strip(u.getExpression());
                        if (x instanceof UnaryTree in && in.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) {
                            s.report(u, "Double negation", s.replace("Remove both negations", u, s.text(in.getExpression())));
                            return null;
                        }
                        if (x instanceof BinaryTree b && b.getKind() == Tree.Kind.NOT_EQUAL_TO) {
                            s.report(u, "Double negation", s.replace("Use ==", u,
                                    s.text(b.getLeftOperand()) + " == " + s.text(b.getRightOperand())));
                        }
                    }
                    return super.visitUnary(u, v);
                }
            }.run();
        }
    }

    static final class UnaryPlus extends Inspection {
        UnaryPlus() {
            super("UnaryPlus", "suspicious", Types.NONE,
                    "A unary `+`, which does nothing to a number and often hides a mistyped `+=` or `++`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitUnary(UnaryTree u, Void v) {
                    if (u.getKind() == Tree.Kind.UNARY_PLUS) {
                        s.report(s.start(u), s.start(u) + 1, "Unary '+' operator", Fix.delete("Remove the '+'", s.start(u), s.start(u) + 1));
                    }
                    return super.visitUnary(u, v);
                }
            }.run();
        }
    }

    static final class UnnecessaryUnaryMinus extends Inspection {
        UnnecessaryUnaryMinus() {
            super("UnnecessaryUnaryMinus", "complexity", Types.NONE,
                    "A unary minus that another operator could absorb: `-(-x)`, `a + -b` (`a - b`), `a - -b`"
                            + " (`a + b`), `a += -b` (`a -= b`).");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitUnary(UnaryTree u, Void v) {
                    if (u.getKind() == Tree.Kind.UNARY_MINUS) {
                        Tree p = parent();
                        boolean nested = Scan.strip(u.getExpression()) instanceof UnaryTree in && in.getKind() == Tree.Kind.UNARY_MINUS;
                        boolean inBinary = p instanceof BinaryTree b && b.getRightOperand() == u
                                && (b.getKind() == Tree.Kind.PLUS || b.getKind() == Tree.Kind.MINUS)
                                && Scan.string(b.getLeftOperand()) == null;
                        boolean inCompound = p instanceof CompoundAssignmentTree c && c.getExpression() == u
                                && (c.getKind() == Tree.Kind.PLUS_ASSIGNMENT || c.getKind() == Tree.Kind.MINUS_ASSIGNMENT);
                        boolean literal = Scan.strip(u.getExpression()) instanceof LiteralTree;
                        if (nested || (inBinary || inCompound) && !literal) {
                            s.report(s.start(u), s.start(u) + 1, "Unnecessary unary minus", null);
                        }
                    }
                    return super.visitUnary(u, v);
                }
            }.run();
        }
    }

    static boolean numericLiteral(ExpressionTree e, double value) {
        ExpressionTree x = Scan.strip(e);
        if (x instanceof UnaryTree u && u.getKind() == Tree.Kind.UNARY_MINUS) return numericLiteral(u.getExpression(), -value);
        return x instanceof LiteralTree l && l.getValue() instanceof Number n && !(l.getValue() instanceof Character)
                && n.doubleValue() == value && (value != 0 || !(n instanceof Double || n instanceof Float) || 1 / n.doubleValue() > 0);
    }

    static boolean integral(ExpressionTree e, long value) {
        Long v = Scan.integer(e);
        return v != null && v == value;
    }

    /**
     * True if {@code e} is numeric: by its type, or without types when it is a number literal.
     */
    static boolean numeric(Scan s, ExpressionTree e) {
        TypeKind k = s.primitive(e);
        if (k != null) return k != TypeKind.BOOLEAN;
        if (s.typed()) {
            return s.isA(e, "java.lang.Number") && !s.is(e, "java.lang.Number");
        }
        return Scan.strip(e) instanceof LiteralTree l && l.getValue() instanceof Number;
    }

    static final class PointlessArithmeticExpression extends Inspection {
        PointlessArithmeticExpression() {
            super("PointlessArithmeticExpression", "complexity", Types.OPTIONAL,
                    "Arithmetic that does nothing: `x * 1`, `x / 1`, `x - 0`, `x + 0`, or is always zero, `x * 0`."
                            + " `x + 0` is reported only when types show it is not a `String` concatenation.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                /**
                 * True if dropping the literal {@code lit} cannot change the type of the operation on {@code other}.
                 */
                private boolean sameType(ExpressionTree lit, ExpressionTree other) {
                    if (!floating(lit)) {
                        Long n = Scan.integer(lit);
                        boolean isLong = Scan.strip(lit) instanceof LiteralTree l && l.getKind() == Tree.Kind.LONG_LITERAL;
                        return n != null && (!isLong || s.primitive(other) == TypeKind.LONG);
                    }
                    TypeKind k = s.primitive(other);
                    return k == TypeKind.DOUBLE || k == TypeKind.FLOAT && Scan.strip(lit) instanceof LiteralTree l && l.getKind() == Tree.Kind.FLOAT_LITERAL;
                }

                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    ExpressionTree l = b.getLeftOperand(), r = b.getRightOperand();
                    String keep = null;
                    switch (b.getKind()) {
                        case MULTIPLY -> {
                            if (numericLiteral(r, 1) && sameType(r, l)) keep = s.text(l);
                            else if (numericLiteral(l, 1) && sameType(l, r)) keep = s.text(r);
                            else if ((integral(r, 0) || integral(l, 0)) && Scan.pure(b)) keep = "0";
                        }
                        case DIVIDE -> {
                            if (numericLiteral(r, 1) && sameType(r, l)) keep = s.text(l);
                        }
                        case MINUS -> {
                            if (numericLiteral(r, 0) && sameType(r, l)) keep = s.text(l);
                        }
                        case PLUS -> {
                            if (s.typed() && numeric(s, b.getLeftOperand()) && numeric(s, b.getRightOperand())) {
                                if (numericLiteral(r, 0)) keep = s.text(l);
                                else if (numericLiteral(l, 0)) keep = s.text(r);
                            }
                        }
                        default -> {
                        }
                    }
                    if (keep != null && !(parent() instanceof BinaryTree p && Scan.string(p.getLeftOperand()) != null)) {
                        if (keep.equals("0") && floating(l) | floating(r)) keep = null;
                    }
                    if (keep != null && !(parent() instanceof BinaryTree p && Scan.string(p.getLeftOperand()) != null)) {
                        s.report(b, "'" + s.text(b) + "' can be replaced with '" + keep + "'", s.replace("Simplify", b, keep));
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    /**
     * True if {@code e} is a {@code float} or {@code double} literal.
     */
    static boolean floating(ExpressionTree e) {
        ExpressionTree x = Scan.strip(e);
        if (x instanceof UnaryTree u) x = Scan.strip(u.getExpression());
        return x instanceof LiteralTree l && (l.getKind() == Tree.Kind.DOUBLE_LITERAL || l.getKind() == Tree.Kind.FLOAT_LITERAL);
    }

    static final class PointlessBitwiseExpression extends Inspection {
        PointlessBitwiseExpression() {
            super("PointlessBitwiseExpression", "complexity", Types.NONE,
                    "Bitwise operations that do nothing, `x | 0`, `x ^ 0`, `x & -1`, `x << 0`, or are always zero,"
                            + " `x & 0`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    ExpressionTree l = b.getLeftOperand(), r = b.getRightOperand();
                    String keep = null;
                    switch (b.getKind()) {
                        case OR, XOR -> {
                            if (integral(r, 0)) keep = s.text(l);
                            else if (integral(l, 0)) keep = s.text(r);
                        }
                        case AND -> {
                            if (integral(r, -1)) keep = s.text(l);
                            else if (integral(l, -1)) keep = s.text(r);
                            else if ((integral(r, 0) || integral(l, 0)) && Scan.pure(b)) keep = "0";
                        }
                        case LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> {
                            if (integral(r, 0)) keep = s.text(l);
                        }
                        default -> {
                        }
                    }
                    if (keep != null) {
                        s.report(b, "'" + s.text(b) + "' can be replaced with '" + keep + "'", s.replace("Simplify", b, keep));
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    static boolean isNaN(ExpressionTree e) {
        String t = Scan.strip(e).toString();
        return t.equals("Double.NaN") || t.equals("Float.NaN") || t.equals("java.lang.Double.NaN") || t.equals("java.lang.Float.NaN");
    }

    static final class ComparisonToNaN extends Inspection {
        ComparisonToNaN() {
            super("ComparisonToNaN", "correctness", Types.NONE,
                    "A comparison with `Double.NaN` or `Float.NaN`, which is always false (`!=`: always true); use"
                            + " `isNaN()`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if (comparison(b.getKind()) && (isNaN(b.getLeftOperand()) || isNaN(b.getRightOperand()))) {
                        ExpressionTree other = isNaN(b.getLeftOperand()) ? b.getRightOperand() : b.getLeftOperand();
                        String type = (isNaN(b.getLeftOperand()) ? b.getLeftOperand() : b.getRightOperand()).toString().contains("Float") ? "Float" : "Double";
                        String repl = b.getKind() == Tree.Kind.EQUAL_TO ? type + ".isNaN(" + s.text(other) + ")"
                                : b.getKind() == Tree.Kind.NOT_EQUAL_TO ? "!" + type + ".isNaN(" + s.text(other) + ")" : null;
                        s.report(b, "Comparison with NaN is always " + (b.getKind() == Tree.Kind.NOT_EQUAL_TO),
                                repl == null ? null : s.replace("Use isNaN()", b, repl));
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    static final class DivideByZero extends Inspection {
        DivideByZero() {
            super("DivideByZero", "correctness", Types.NONE,
                    "Division or remainder by the integer literal zero, `x / 0` or `x %= 0`, which throws"
                            + " `ArithmeticException`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if ((b.getKind() == Tree.Kind.DIVIDE || b.getKind() == Tree.Kind.REMAINDER) && integral(b.getRightOperand(), 0)) {
                        s.report(b, "Division by zero");
                    }
                    return super.visitBinary(b, v);
                }

                @Override
                public Void visitCompoundAssignment(CompoundAssignmentTree c, Void v) {
                    if ((c.getKind() == Tree.Kind.DIVIDE_ASSIGNMENT || c.getKind() == Tree.Kind.REMAINDER_ASSIGNMENT)
                            && integral(c.getExpression(), 0)) {
                        s.report(c, "Division by zero");
                    }
                    return super.visitCompoundAssignment(c, v);
                }
            }.run();
        }
    }

    static final class ShiftOutOfRange extends Inspection {
        ShiftOutOfRange() {
            super("ShiftOutOfRange", "correctness", Types.OPTIONAL,
                    "A shift by a constant outside 0 to 31 for an `int` (0 to 63 for a `long`), which Java takes modulo"
                            + " 32 (64). Without types, shifts of `int` literals and shifts by 64 or more are reported.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    Tree.Kind k = b.getKind();
                    if (k == Tree.Kind.LEFT_SHIFT || k == Tree.Kind.RIGHT_SHIFT || k == Tree.Kind.UNSIGNED_RIGHT_SHIFT) {
                        Long n = Scan.integer(b.getRightOperand());
                        if (n != null) {
                            TypeKind left = s.primitive(b.getLeftOperand());
                            ExpressionTree l = Scan.strip(b.getLeftOperand());
                            boolean isLong = left == TypeKind.LONG || l instanceof LiteralTree lit && lit.getKind() == Tree.Kind.LONG_LITERAL;
                            boolean isInt = left == TypeKind.INT || left == TypeKind.SHORT || left == TypeKind.BYTE || left == TypeKind.CHAR
                                    || l instanceof LiteralTree lit2 && lit2.getKind() == Tree.Kind.INT_LITERAL;
                            int max = isLong ? 63 : isInt ? 31 : 63;
                            if (n < 0 || n > max) s.report(b, "Shift by " + n + " is out of range for " + (isLong ? "'long'" : isInt ? "'int'" : "this type"));
                        }
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    /**
     * The value of an expression of integer literals and {@code + - *}, as {@code long} literal or not, or null.
     */
    static BigInteger constant(ExpressionTree e, boolean[] isLong) {
        e = Scan.strip(e);
        if (e instanceof LiteralTree l && l.getValue() instanceof Number n
                && (l.getKind() == Tree.Kind.INT_LITERAL || l.getKind() == Tree.Kind.LONG_LITERAL)) {
            if (l.getKind() == Tree.Kind.LONG_LITERAL) isLong[0] = true;
            return BigInteger.valueOf(n.longValue());
        }
        if (e instanceof UnaryTree u && u.getKind() == Tree.Kind.UNARY_MINUS) {
            BigInteger x = constant(u.getExpression(), isLong);
            return x == null ? null : x.negate();
        }
        if (e instanceof BinaryTree b) {
            BigInteger x = constant(b.getLeftOperand(), isLong), y = constant(b.getRightOperand(), isLong);
            if (x == null || y == null) return null;
            return switch (b.getKind()) {
                case PLUS -> x.add(y);
                case MINUS -> x.subtract(y);
                case MULTIPLY -> x.multiply(y);
                default -> null;
            };
        }
        return null;
    }

    static final class NumericOverflow extends Inspection {
        NumericOverflow() {
            super("NumericOverflow", "correctness", Types.NONE,
                    "A constant expression of integer literals that overflows its type, `24 * 60 * 60 * 1000 * 1000`;"
                            + " an `L` on one literal makes it `long`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if (parent() instanceof BinaryTree || parent() instanceof com.sun.source.tree.ParenthesizedTree
                            && getCurrentPath().getParentPath().getParentPath().getLeaf() instanceof BinaryTree) {
                        return super.visitBinary(b, v);
                    }
                    boolean[] isLong = {false};
                    BigInteger x = constant(b, isLong);
                    if (x != null) {
                        long min = isLong[0] ? Long.MIN_VALUE : Integer.MIN_VALUE, max = isLong[0] ? Long.MAX_VALUE : Integer.MAX_VALUE;
                        if (x.compareTo(BigInteger.valueOf(min)) < 0 || x.compareTo(BigInteger.valueOf(max)) > 0) {
                            s.report(b, "Numeric overflow in '" + s.text(b) + "'");
                        }
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    static final class IncompatibleMask extends Inspection {
        IncompatibleMask() {
            super("IncompatibleMask", "correctness", Types.NONE,
                    "`(x & a) == b` where `b` has bits that `a` lacks, or `(x | a) == b` where `a` has bits `b` lacks:"
                            + " always false (`!=`: always true).");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if (b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO) {
                        check(b, b.getLeftOperand(), b.getRightOperand());
                        check(b, b.getRightOperand(), b.getLeftOperand());
                    }
                    return super.visitBinary(b, v);
                }

                private void check(BinaryTree cmp, ExpressionTree masked, ExpressionTree other) {
                    Long c2 = Scan.integer(other);
                    if (c2 == null || !(Scan.strip(masked) instanceof BinaryTree m)) return;
                    Long c1 = Scan.integer(m.getRightOperand());
                    if (c1 == null) c1 = Scan.integer(m.getLeftOperand());
                    if (c1 == null) return;
                    boolean never = m.getKind() == Tree.Kind.AND ? (c2 & ~c1) != 0
                            : m.getKind() == Tree.Kind.OR && (c1 & ~c2) != 0;
                    if (never) s.report(cmp, "'" + s.text(cmp) + "' is always " + (cmp.getKind() == Tree.Kind.NOT_EQUAL_TO));
                }
            }.run();
        }
    }

    static final class SillyAssignment extends Inspection {
        SillyAssignment() {
            super("SillyAssignment", "suspicious", Types.NONE,
                    "A variable assigned to itself, `x = x`, often a missing `this.` in `this.x = x`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitAssignment(AssignmentTree a, Void v) {
                    if (Scan.pure(a.getExpression()) && Scan.same(a.getVariable(), a.getExpression())) {
                        s.report(a, "Variable '" + s.text(a.getVariable()) + "' is assigned to itself");
                    }
                    return super.visitAssignment(a, v);
                }
            }.run();
        }
    }

    static final class ExpressionComparedToItself extends Inspection {
        ExpressionComparedToItself() {
            super("ExpressionComparedToItself", "suspicious", Types.OPTIONAL,
                    "An expression compared with itself, `a == a` or `a < a`, which is constant. With types, `x != x`"
                            + " on a `double` or `float` (the NaN test) is not reported.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if (comparison(b.getKind()) && Scan.pure(b.getLeftOperand()) && Scan.same(b.getLeftOperand(), b.getRightOperand())
                            && !(Scan.strip(b.getLeftOperand()) instanceof LiteralTree)) {
                        TypeKind k = s.primitive(b.getLeftOperand());
                        boolean floating = k == TypeKind.DOUBLE || k == TypeKind.FLOAT;
                        if (!floating) s.report(b, "'" + s.text(b.getLeftOperand()) + "' is compared with itself");
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    static final class EqualsWithItself extends Inspection {
        EqualsWithItself() {
            super("EqualsWithItself", "suspicious", Types.NONE,
                    "`a.equals(a)`, `a.compareTo(a)` or `Objects.equals(a, a)`: an object compared with itself.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    ExpressionTree r = Scan.receiver(m);
                    boolean self = false;
                    if ((n.equals("equals") || n.equals("compareTo") || n.equals("equalsIgnoreCase") || n.equals("compareToIgnoreCase"))
                            && m.getArguments().size() == 1 && r != null) {
                        self = Scan.pure(r) && Scan.same(r, m.getArguments().get(0));
                    } else if ((Scan.callsStatic(m, "Objects", "equals") || Scan.callsStatic(m, "Objects", "deepEquals")
                            || Scan.callsStatic(m, "Arrays", "equals")) && m.getArguments().size() == 2) {
                        self = Scan.pure(m.getArguments().get(0)) && Scan.same(m.getArguments().get(0), m.getArguments().get(1));
                    }
                    if (self) s.report(m, "'" + n + "()' compares an object with itself");
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class ComparatorResultComparison extends Inspection {
        ComparatorResultComparison() {
            super("ComparatorResultComparison", "correctness", Types.NONE,
                    "The result of `compare()` or `compareTo()` compared with `1` or `-1`; only its sign is specified."
                            + " The fix compares with `0`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if (b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO) {
                        ExpressionTree call = Scan.strip(b.getLeftOperand()), lit = b.getRightOperand();
                        if (!(call instanceof MethodInvocationTree)) {
                            call = Scan.strip(b.getRightOperand());
                            lit = b.getLeftOperand();
                        }
                        Long n = Scan.integer(lit);
                        if (call instanceof MethodInvocationTree m && n != null && (n == 1 || n == -1)
                                && (Scan.name(m).equals("compareTo") && m.getArguments().size() == 1
                                        || Scan.name(m).equals("compare") && m.getArguments().size() == 2)) {
                            String op = (n == 1) == (b.getKind() == Tree.Kind.EQUAL_TO) ? " > 0" : n == 1 ? " <= 0" : " >= 0";
                            if (n == -1) op = b.getKind() == Tree.Kind.EQUAL_TO ? " < 0" : " >= 0";
                            s.report(b, "Result of '" + Scan.name(m) + "()' compared with " + n,
                                    s.replace("Compare with 0", b, s.text(m) + op));
                        }
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    static final class PointlessNullCheck extends Inspection {
        PointlessNullCheck() {
            super("PointlessNullCheck", "complexity", Types.NONE,
                    "A null check next to an `instanceof` of the same value, `x != null && x instanceof T`:"
                            + " `instanceof` is false for `null` anyway. The fix removes the null check.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if (b.getKind() == Tree.Kind.CONDITIONAL_AND
                            && Scan.strip(b.getLeftOperand()) instanceof BinaryTree n && n.getKind() == Tree.Kind.NOT_EQUAL_TO
                            && Scan.strip(b.getRightOperand()) instanceof InstanceOfTree io) {
                        ExpressionTree checked = Scan.isNull(n.getRightOperand()) ? n.getLeftOperand()
                                : Scan.isNull(n.getLeftOperand()) ? n.getRightOperand() : null;
                        if (checked != null && Scan.pure(checked) && Scan.same(checked, io.getExpression())) {
                            s.report(n, "Unnecessary null check before 'instanceof'",
                                    s.replace("Remove the null check", b, s.text(b.getRightOperand())));
                        }
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    static final class AssertWithSideEffects extends Inspection {
        AssertWithSideEffects() {
            super("AssertWithSideEffects", "suspicious", Types.NONE,
                    "An `assert` whose condition assigns or increments something, which only happens when assertions"
                            + " are enabled.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitAssert(AssertTree a, Void v) {
                    boolean effects = Flow.contains(a.getCondition(), t -> t instanceof AssignmentTree || t instanceof CompoundAssignmentTree
                            || t instanceof UnaryTree u && (u.getKind() == Tree.Kind.POSTFIX_INCREMENT || u.getKind() == Tree.Kind.PREFIX_INCREMENT
                                    || u.getKind() == Tree.Kind.POSTFIX_DECREMENT || u.getKind() == Tree.Kind.PREFIX_DECREMENT));
                    if (effects) s.report(a.getCondition(), "'assert' has side effects");
                    return super.visitAssert(a, v);
                }
            }.run();
        }
    }

    static final class MathRandomCastToInt extends Inspection {
        MathRandomCastToInt() {
            super("MathRandomCastToInt", "correctness", Types.NONE,
                    "`(int) Math.random()`, which is always 0: the cast comes before any multiplication.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitTypeCast(TypeCastTree c, Void v) {
                    String t = c.getType().toString();
                    if ((t.equals("int") || t.equals("long") || t.equals("short") || t.equals("byte") || t.equals("char"))
                            && Scan.strip(c.getExpression()) instanceof MethodInvocationTree m
                            && Scan.callsStatic(m, "Math", "random")) {
                        s.report(c, "'Math.random()' cast to '" + t + "' is always 0");
                    }
                    return super.visitTypeCast(c, v);
                }
            }.run();
        }
    }

    static final class NewObjectEquality extends Inspection {
        NewObjectEquality() {
            super("NewObjectEquality", "correctness", Types.NONE,
                    "`x == new T(...)`, which is always false: a new object is never identical to an existing one.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if ((b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO)
                            && (Scan.strip(b.getLeftOperand()) instanceof NewClassTree || Scan.strip(b.getRightOperand()) instanceof NewClassTree)) {
                        s.report(b, "New object compared with '" + (b.getKind() == Tree.Kind.EQUAL_TO ? "==" : "!=") + "'");
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    static final class TrivialStringConcatenation extends Inspection {
        TrivialStringConcatenation() {
            super("TrivialStringConcatenation", "style", Types.NONE,
                    "Concatenation with the empty string to convert a value, `\"\" + x`; `String.valueOf(x)` says so.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if (b.getKind() == Tree.Kind.PLUS && !(parent() instanceof BinaryTree p && p.getKind() == Tree.Kind.PLUS)) {
                        boolean left = "".equals(Scan.string(b.getLeftOperand())), right = "".equals(Scan.string(b.getRightOperand()));
                        ExpressionTree other = left ? b.getRightOperand() : b.getLeftOperand();
                        boolean nested = Scan.strip(other) instanceof BinaryTree ob && ob.getKind() == Tree.Kind.PLUS;
                        if ((left || right) && !nested && Scan.string(other) == null) {
                            s.report(b, "Concatenation with the empty string",
                                    s.replace("Use String.valueOf()", b, "String.valueOf(" + s.text(Scan.strip(other)) + ")"));
                        }
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    static final class CollectionAddedToSelf extends Inspection {
        CollectionAddedToSelf() {
            super("CollectionAddedToSelf", "correctness", Types.NONE,
                    "A collection added to itself, `list.add(list)` or `map.put(map, v)`, which makes `hashCode()` and"
                            + " `toString()` recurse forever.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    ExpressionTree r = Scan.receiver(m);
                    if (r != null && Scan.pure(r) && !(Scan.strip(r) instanceof LiteralTree)
                            && (n.equals("add") || n.equals("put") || n.equals("offer") || n.equals("push") || n.equals("addFirst")
                                    || n.equals("addLast"))
                            && m.getArguments().stream().anyMatch(a -> Scan.same(a, r))
                            && !r.toString().equals("this")) {
                        s.report(m, "'" + s.text(r) + "' is added to itself");
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class InstantiatingObjectToGetClassObject extends Inspection {
        InstantiatingObjectToGetClassObject() {
            super("InstantiatingObjectToGetClassObject", "performance", Types.NONE,
                    "`new T().getClass()`, which creates an object only to get `T.class`. The fix uses the class literal.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.calls(m, "getClass", 0) && Scan.strip(Scan.receiver(m)) instanceof NewClassTree n
                            && n.getClassBody() == null) {
                        String type = n.getIdentifier().toString().replaceAll("<.*>", "");
                        s.report(m, "Object created only to get its class", s.replace("Use " + type + ".class", m, type + ".class"));
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class ClassGetClass extends Inspection {
        ClassGetClass() {
            super("ClassGetClass", "suspicious", Types.NONE,
                    "`T.class.getClass()`, which is `Class.class`, not `T.class`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    ExpressionTree r = Scan.receiver(m);
                    if (Scan.calls(m, "getClass", 0) && r instanceof MemberSelectTree ms && ms.getIdentifier().contentEquals("class")) {
                        s.report(m, "'getClass()' called on a class literal");
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class RedundantClassCall extends Inspection {
        RedundantClassCall() {
            super("RedundantClassCall", "style", Types.NONE,
                    "`T.class.isInstance(x)` and `T.class.cast(x)` on a class literal, which are `x instanceof T` and"
                            + " `(T) x`. The fix rewrites them.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    ExpressionTree r = Scan.receiver(m);
                    if (m.getArguments().size() == 1 && r instanceof MemberSelectTree ms && ms.getIdentifier().contentEquals("class")
                            && !ms.getExpression().toString().contains("[")) {
                        String type = s.text(ms.getExpression()), arg = operand(s, m.getArguments().get(0));
                        if (Scan.name(m).equals("isInstance") && !(parent() instanceof com.sun.source.tree.UnaryTree)) {
                            s.report(m, "Redundant 'isInstance()' call", s.replace("Use instanceof", m, arg + " instanceof " + type));
                        } else if (Scan.name(m).equals("cast") && !(parent() instanceof MemberSelectTree)) {
                            s.report(m, "Redundant 'cast()' call", s.replace("Use a cast", m, "(" + type + ") " + arg));
                        }
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static boolean throwableName(String type) {
        return type.endsWith("Exception") || type.endsWith("Error") || type.equals("Throwable");
    }

    static final class ThrowableNotThrown extends Inspection {
        ThrowableNotThrown() {
            super("ThrowableNotThrown", "correctness", Types.OPTIONAL,
                    "An exception created and thrown away, `new IllegalStateException(\"...\");` without `throw`."
                            + " Without types, classes named `...Exception`, `...Error` or `Throwable` are taken as"
                            + " exceptions.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitExpressionStatement(ExpressionStatementTree e, Void v) {
                    if (Scan.strip(e.getExpression()) instanceof NewClassTree n && n.getClassBody() == null) {
                        boolean isThrowable = s.typed() ? s.isA(n, "java.lang.Throwable") : throwableName(Scan.simpleName(n.getIdentifier()));
                        if (isThrowable) s.report(n, "Exception '" + Scan.simpleName(n.getIdentifier()) + "' is created but not thrown");
                    }
                    return super.visitExpressionStatement(e, v);
                }
            }.run();
        }
    }

    static final class ThrowablePrintStackTrace extends Inspection {
        ThrowablePrintStackTrace() {
            super("ThrowablePrintStackTrace", "style", Types.NONE,
                    "`e.printStackTrace()`, which writes to standard error instead of a log.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.calls(m, "printStackTrace", 0) && Scan.receiver(m) != null) {
                        s.report(m, "Call to 'printStackTrace()'");
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class ThrowablePrintedToSystemOut extends Inspection {
        ThrowablePrintedToSystemOut() {
            super("ThrowablePrintedToSystemOut", "style", Types.OPTIONAL,
                    "An exception printed with `System.out.println(e)` or `System.err.println(e)`, which loses its stack"
                            + " trace. Without types, only `catch` parameters are recognized.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    ExpressionTree r = Scan.receiver(m);
                    String rs = r == null ? "" : r.toString();
                    if ((rs.equals("System.out") || rs.equals("System.err")) && (Scan.name(m).equals("println") || Scan.name(m).equals("print"))
                            && m.getArguments().size() == 1) {
                        ExpressionTree a = Scan.strip(m.getArguments().get(0));
                        boolean isThrowable = s.typed() ? s.isA(a, "java.lang.Throwable") : a instanceof IdentifierTree i && caught(i.getName().toString());
                        if (isThrowable) s.report(a, "Exception printed to '" + rs + "'");
                    }
                    return super.visitMethodInvocation(m, v);
                }

                private boolean caught(String name) {
                    for (TreePath p = getCurrentPath(); p != null; p = p.getParentPath()) {
                        if (p.getLeaf() instanceof com.sun.source.tree.CatchTree c && c.getParameter().getName().contentEquals(name)) return true;
                    }
                    return false;
                }
            }.run();
        }
    }

    static final class NoExplicitFinalizeCalls extends Inspection {
        NoExplicitFinalizeCalls() {
            super("NoExplicitFinalizeCalls", "suspicious", Types.NONE,
                    "A call to `finalize()`, which only the garbage collector should make (`super.finalize()` inside"
                            + " `finalize()` is fine).");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.calls(m, "finalize", 0)) {
                        ExpressionTree r = Scan.receiver(m);
                        boolean superCall = r != null && r.toString().equals("super");
                        if (!superCall) s.report(m, "Explicit call to 'finalize()'");
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class SystemRunFinalizersOnExit extends Inspection {
        SystemRunFinalizersOnExit() {
            super("SystemRunFinalizersOnExit", "correctness", Types.NONE,
                    "A call to `System.runFinalizersOnExit()` or `Runtime.runFinalizersOnExit()`, which is unsafe and"
                            + " removed since Java 11.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.name(m).equals("runFinalizersOnExit")) s.report(m, "Call to 'runFinalizersOnExit()'");
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class ThreadRun extends Inspection {
        ThreadRun() {
            super("ThreadRun", "correctness", Types.OPTIONAL,
                    "`thread.run()`, which runs the thread's code on the calling thread; `start()` runs it on the new"
                            + " one. Without types, only `new Thread(...).run()` is recognized.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    ExpressionTree r = Scan.receiver(m);
                    if (Scan.calls(m, "run", 0) && r != null && !r.toString().equals("super")) {
                        boolean thread = s.typed() ? s.isA(r, "java.lang.Thread")
                                : Scan.strip(r) instanceof NewClassTree n && Scan.simpleName(n.getIdentifier()).equals("Thread");
                        if (thread) s.report(m, "Call to 'Thread.run()'; 'start()' runs it on the thread", null);
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class BusyWait extends Inspection {
        BusyWait() {
            super("BusyWait", "performance", Types.NONE,
                    "`Thread.sleep()` in a loop, which waits by polling; a lock, condition or blocking queue waits for the"
                            + " event.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.callsStatic(m, "Thread", "sleep") && inLoop()) s.report(m, "Call to 'Thread.sleep()' in a loop");
                    return super.visitMethodInvocation(m, v);
                }

                private boolean inLoop() {
                    for (TreePath p = getCurrentPath().getParentPath(); p != null; p = p.getParentPath()) {
                        Tree t = p.getLeaf();
                        if (t instanceof ClassTree || t instanceof com.sun.source.tree.LambdaExpressionTree || t instanceof MethodTree) return false;
                        if (Flow.loop(t)) return true;
                    }
                    return false;
                }
            }.run();
        }
    }

    /**
     * The field {@code name} declared in a class around {@code path}, or null.
     */
    static VariableTree field(TreePath path, String name) {
        for (TreePath p = path; p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof MethodTree m) {
                for (VariableTree param : m.getParameters()) if (param.getName().contentEquals(name)) return null;
                if (m.getBody() != null && declares(m.getBody(), name)) return null;
            }
            if (p.getLeaf() instanceof ClassTree c) {
                for (Tree member : c.getMembers()) {
                    if (member instanceof VariableTree f && f.getName().contentEquals(name)) return f;
                }
            }
        }
        return null;
    }

    static boolean declares(Tree root, String name) {
        return Flow.contains(root, t -> t instanceof VariableTree v && v.getName().contentEquals(name));
    }

    /**
     * The local variable or parameter {@code name} declared in the method around {@code path}, or null.
     */
    static VariableTree local(TreePath path, String name) {
        for (TreePath p = path; p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof ClassTree) return null;
            if (p.getLeaf() instanceof MethodTree m) {
                for (VariableTree param : m.getParameters()) if (param.getName().contentEquals(name)) return param;
                VariableTree[] found = {null};
                if (m.getBody() != null) Flow.contains(m.getBody(), t -> {
                    if (t instanceof VariableTree v && v.getName().contentEquals(name)) found[0] = v;
                    return found[0] != null;
                });
                return found[0];
            }
        }
        return null;
    }

    static final class SynchronizeOnNonFinalField extends Inspection {
        SynchronizeOnNonFinalField() {
            super("SynchronizeOnNonFinalField", "correctness", Types.NONE,
                    "`synchronized (field)` on a field that is not `final`: when it changes, threads lock different"
                            + " objects.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitSynchronized(SynchronizedTree t, Void v) {
                    ExpressionTree e = Scan.strip(t.getExpression());
                    String name = e instanceof IdentifierTree i ? i.getName().toString()
                            : e instanceof MemberSelectTree m && m.getExpression().toString().equals("this") ? m.getIdentifier().toString() : null;
                    if (name != null) {
                        VariableTree f = field(getCurrentPath(), name);
                        if (f != null && !f.getModifiers().getFlags().contains(Modifier.FINAL)) {
                            s.report(e, "Synchronization on non-final field '" + name + "'");
                        }
                    }
                    return super.visitSynchronized(t, v);
                }
            }.run();
        }
    }

    static final class SynchronizationOnLocalVariableOrMethodParameter extends Inspection {
        SynchronizationOnLocalVariableOrMethodParameter() {
            super("SynchronizationOnLocalVariableOrMethodParameter", "suspicious", Types.NONE,
                    "`synchronized` on a local variable that holds a new object, or on a method parameter, which other"
                            + " threads are unlikely to lock.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitSynchronized(SynchronizedTree t, Void v) {
                    if (Scan.strip(t.getExpression()) instanceof IdentifierTree i) {
                        VariableTree var = local(getCurrentPath(), i.getName().toString());
                        if (var != null) {
                            boolean param = isParameter(getCurrentPath(), var);
                            boolean fresh = var.getInitializer() instanceof NewClassTree;
                            if (param || fresh) {
                                s.report(i, "Synchronization on " + (param ? "method parameter" : "local variable") + " '" + i.getName() + "'");
                            }
                        }
                    }
                    return super.visitSynchronized(t, v);
                }

                private boolean isParameter(TreePath path, VariableTree var) {
                    for (TreePath p = path; p != null; p = p.getParentPath()) {
                        if (p.getLeaf() instanceof MethodTree m) return m.getParameters().contains(var);
                    }
                    return false;
                }
            }.run();
        }
    }

    static final class SynchronizationOnGetClass extends Inspection {
        SynchronizationOnGetClass() {
            super("SynchronizationOnGetClass", "correctness", Types.NONE,
                    "`synchronized (getClass())`, which locks a different object in each subclass; lock `T.class`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitSynchronized(SynchronizedTree t, Void v) {
                    if (Scan.strip(t.getExpression()) instanceof MethodInvocationTree m && Scan.calls(m, "getClass", 0)
                            && (Scan.receiver(m) == null || Scan.receiver(m).toString().equals("this"))) {
                        s.report(m, "Synchronization on 'getClass()'");
                    }
                    return super.visitSynchronized(t, v);
                }
            }.run();
        }
    }

    static final class NonAtomicOperationOnVolatileField extends Inspection {
        NonAtomicOperationOnVolatileField() {
            super("NonAtomicOperationOnVolatileField", "correctness", Types.NONE,
                    "`v++`, `v += x` or `v = v + x` on a `volatile` field outside `synchronized`: the read and the write"
                            + " are separate, so updates from other threads can be lost.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitUnary(UnaryTree u, Void v) {
                    if (u.getKind() == Tree.Kind.POSTFIX_INCREMENT || u.getKind() == Tree.Kind.PREFIX_INCREMENT
                            || u.getKind() == Tree.Kind.POSTFIX_DECREMENT || u.getKind() == Tree.Kind.PREFIX_DECREMENT) {
                        check(u.getExpression(), u);
                    }
                    return super.visitUnary(u, v);
                }

                @Override
                public Void visitCompoundAssignment(CompoundAssignmentTree c, Void v) {
                    check(c.getVariable(), c);
                    return super.visitCompoundAssignment(c, v);
                }

                @Override
                public Void visitAssignment(AssignmentTree a, Void v) {
                    if (Flow.contains(a.getExpression(), t -> t instanceof ExpressionTree e && Scan.same(e, a.getVariable()))) {
                        check(a.getVariable(), a);
                    }
                    return super.visitAssignment(a, v);
                }

                private void check(ExpressionTree target, Tree op) {
                    String name = target instanceof IdentifierTree i ? i.getName().toString()
                            : target instanceof MemberSelectTree m && m.getExpression().toString().equals("this") ? m.getIdentifier().toString() : null;
                    if (name == null || synchronizedAround()) return;
                    VariableTree f = field(getCurrentPath(), name);
                    if (f != null && f.getModifiers().getFlags().contains(Modifier.VOLATILE)) {
                        s.report(op, "Non-atomic operation on volatile field '" + name + "'");
                    }
                }

                private boolean synchronizedAround() {
                    for (TreePath p = getCurrentPath(); p != null; p = p.getParentPath()) {
                        if (p.getLeaf() instanceof SynchronizedTree) return true;
                        if (p.getLeaf() instanceof MethodTree m) return m.getModifiers().getFlags().contains(Modifier.SYNCHRONIZED);
                    }
                    return false;
                }
            }.run();
        }
    }

    static boolean optionalType(Tree type) {
        String n = Scan.simpleName(type);
        return n.equals("Optional") || n.equals("OptionalInt") || n.equals("OptionalLong") || n.equals("OptionalDouble");
    }

    static final class OptionalAssignedToNull extends Inspection {
        OptionalAssignedToNull() {
            super("OptionalAssignedToNull", "correctness", Types.NONE,
                    "`null` assigned to a variable of type `Optional`, or returned from a method that returns one; use"
                            + " `Optional.empty()`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitVariable(VariableTree v, Void x) {
                    if (v.getInitializer() != null && Scan.isNull(v.getInitializer()) && optionalType(v.getType())) {
                        s.report(v.getInitializer(), "'null' assigned to Optional variable '" + v.getName() + "'");
                    }
                    return super.visitVariable(v, x);
                }

                @Override
                public Void visitReturn(ReturnTree r, Void x) {
                    if (r.getExpression() != null && Scan.isNull(r.getExpression())) {
                        for (TreePath p = getCurrentPath(); p != null; p = p.getParentPath()) {
                            if (p.getLeaf() instanceof com.sun.source.tree.LambdaExpressionTree) break;
                            if (p.getLeaf() instanceof MethodTree m) {
                                if (optionalType(m.getReturnType())) s.report(r.getExpression(), "'null' returned from a method that returns Optional");
                                break;
                            }
                        }
                    }
                    return super.visitReturn(r, x);
                }
            }.run();
        }
    }

    static final class OptionalOfNullableMisuse extends Inspection {
        OptionalOfNullableMisuse() {
            super("OptionalOfNullableMisuse", "complexity", Types.NONE,
                    "`Optional.ofNullable(null)`, which is `Optional.empty()`, or `Optional.ofNullable` of a literal or new"
                            + " object, which is never null: `Optional.of`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    if (Scan.callsStatic(m, "Optional", "ofNullable") && m.getArguments().size() == 1) {
                        ExpressionTree a = Scan.strip(m.getArguments().get(0));
                        if (Scan.isNull(a)) {
                            s.report(m, "'Optional.ofNullable(null)' is 'Optional.empty()'", s.replace("Use Optional.empty()", m, "Optional.empty()"));
                        } else if (a instanceof LiteralTree || a instanceof NewClassTree) {
                            int at = s.source().indexOf("ofNullable", s.start(m));
                            s.report(m, "Argument is never null: use 'Optional.of()'",
                                    at < 0 ? null : Fix.replace("Use Optional.of()", at, at + 10, "of"));
                        }
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static boolean boxedNumber(Scan s, ExpressionTree e) {
        return s.isA(e, "java.lang.Number") && !Scan.isNull(e) && s.primitive(e) == null
                && Set.of("Integer", "Long", "Short", "Byte", "Double", "Float", "BigInteger", "BigDecimal", "AtomicInteger", "AtomicLong")
                        .stream().anyMatch(n -> s.is(e, (n.startsWith("Big") ? "java.math." : n.startsWith("Atomic") ? "java.util.concurrent.atomic." : "java.lang.") + n));
    }

    static final class NumberEquality extends Inspection {
        NumberEquality() {
            super("NumberEquality", "correctness", Types.REQUIRED,
                    "Two boxed numbers (`Integer`, `Long`, `BigDecimal` ...) compared with `==` or `!=`, which compares"
                            + " identity; use `equals()`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if ((b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO)
                            && boxedNumber(s, b.getLeftOperand()) && boxedNumber(s, b.getRightOperand())) {
                        s.report(b, "Numbers compared with '" + (b.getKind() == Tree.Kind.EQUAL_TO ? "==" : "!=") + "'");
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    static final class ArrayEquals extends Inspection {
        ArrayEquals() {
            super("ArrayEquals", "correctness", Types.REQUIRED,
                    "`a.equals(b)` on an array, which compares identity; use `Arrays.equals(a, b)`. The fix rewrites it.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    ExpressionTree r = Scan.receiver(m);
                    if (Scan.calls(m, "equals", 1) && r != null && s.isArray(r)) {
                        s.report(m, "'equals()' between arrays", s.replace("Use Arrays.equals()", m,
                                "java.util.Arrays.equals(" + s.text(r) + ", " + s.text(m.getArguments().get(0)) + ")"));
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class ArrayHashCode extends Inspection {
        ArrayHashCode() {
            super("ArrayHashCode", "correctness", Types.REQUIRED,
                    "`a.hashCode()` on an array, which hashes its identity; use `Arrays.hashCode(a)`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    ExpressionTree r = Scan.receiver(m);
                    if (Scan.calls(m, "hashCode", 0) && r != null && s.isArray(r)) {
                        s.report(m, "'hashCode()' on an array", s.replace("Use Arrays.hashCode()", m, "java.util.Arrays.hashCode(" + s.text(r) + ")"));
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class ImplicitArrayToString extends Inspection {
        ImplicitArrayToString() {
            super("ImplicitArrayToString", "correctness", Types.REQUIRED,
                    "An array converted to text by concatenation, `print`/`println`, `append` or `String.valueOf`, which"
                            + " gives `[I@1b6d3586`; use `Arrays.toString()`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if (b.getKind() == Tree.Kind.PLUS && s.is(b, "java.lang.String")) {
                        for (ExpressionTree e : List.of(b.getLeftOperand(), b.getRightOperand())) {
                            if (s.isArray(e)) report(e);
                        }
                    }
                    return super.visitBinary(b, v);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    ExpressionTree r = Scan.receiver(m);
                    boolean printer = (n.equals("print") || n.equals("println")) && r != null
                            && (s.isA(r, "java.io.PrintStream") || s.isA(r, "java.io.PrintWriter"));
                    boolean appender = n.equals("append") && r != null && s.isA(r, "java.lang.AbstractStringBuilder");
                    boolean valueOf = Scan.callsStatic(m, "String", "valueOf");
                    if ((printer || appender || valueOf) && m.getArguments().size() == 1) {
                        ExpressionTree a = m.getArguments().get(0);
                        if (s.isArray(a) && !charArray(s, a)) report(a);
                    }
                    return super.visitMethodInvocation(m, v);
                }

                private void report(ExpressionTree e) {
                    s.report(e, "Implicit call to 'toString()' on array '" + s.text(e) + "'",
                            s.replace("Use Arrays.toString()", e, "java.util.Arrays.toString(" + s.text(e) + ")"));
                }
            }.run();
        }

        private static boolean charArray(Scan s, ExpressionTree e) {
            TypeMirror t = s.type(e);
            return t instanceof javax.lang.model.type.ArrayType a && a.getComponentType().getKind() == TypeKind.CHAR;
        }
    }

    static boolean integralKind(TypeKind k) {
        return k == TypeKind.INT || k == TypeKind.LONG || k == TypeKind.SHORT || k == TypeKind.BYTE || k == TypeKind.CHAR;
    }

    static final class IntegerDivisionInFloatingPointContext extends Inspection {
        IntegerDivisionInFloatingPointContext() {
            super("IntegerDivisionInFloatingPointContext", "suspicious", Types.REQUIRED,
                    "An integer division whose result is used as a `float` or `double`, `double d = a / b`, which drops"
                            + " the fraction before it is converted.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if (b.getKind() == Tree.Kind.DIVIDE && integralKind(s.primitive(b.getLeftOperand()))
                            && integralKind(s.primitive(b.getRightOperand())) && floatingContext()) {
                        s.report(b, "Integer division in a floating-point context");
                    }
                    return super.visitBinary(b, v);
                }

                private boolean floatingContext() {
                    TreePath p = getCurrentPath().getParentPath();
                    while (p != null && p.getLeaf() instanceof com.sun.source.tree.ParenthesizedTree) p = p.getParentPath();
                    if (p == null) return false;
                    Tree t = p.getLeaf();
                    TypeKind k = null;
                    if (t instanceof BinaryTree pb && (pb.getKind() == Tree.Kind.PLUS || pb.getKind() == Tree.Kind.MINUS
                            || pb.getKind() == Tree.Kind.MULTIPLY || pb.getKind() == Tree.Kind.DIVIDE)) k = s.primitive(pb);
                    else if (t instanceof VariableTree var) k = s.primitive(var.getType());
                    else if (t instanceof AssignmentTree a) k = s.primitive(a.getVariable());
                    return k == TypeKind.DOUBLE || k == TypeKind.FLOAT;
                }
            }.run();
        }
    }

    static final class IntegerMultiplicationImplicitCastToLong extends Inspection {
        IntegerMultiplicationImplicitCastToLong() {
            super("IntegerMultiplicationImplicitCastToLong", "suspicious", Types.REQUIRED,
                    "An `int` multiplication (or left shift) whose result is used as a `long`, `long ms = days * 86400000`,"
                            + " which overflows before it is widened.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if ((b.getKind() == Tree.Kind.MULTIPLY || b.getKind() == Tree.Kind.LEFT_SHIFT)
                            && s.primitive(b) == TypeKind.INT && longContext() && !constant(b)) {
                        s.report(b, "'int' multiplication used as a 'long' may overflow");
                    }
                    return super.visitBinary(b, v);
                }

                private boolean constant(BinaryTree b) {
                    return Scan.integer(b.getLeftOperand()) != null && Scan.integer(b.getRightOperand()) != null;
                }

                private boolean longContext() {
                    TreePath p = getCurrentPath().getParentPath();
                    while (p != null && p.getLeaf() instanceof com.sun.source.tree.ParenthesizedTree) p = p.getParentPath();
                    if (p == null) return false;
                    Tree t = p.getLeaf();
                    TypeKind k = null;
                    if (t instanceof BinaryTree pb && pb.getKind() != Tree.Kind.MULTIPLY && pb.getKind() != Tree.Kind.LEFT_SHIFT
                            && !comparison(pb.getKind())) k = s.primitive(pb);
                    else if (t instanceof VariableTree var) k = s.primitive(var.getType());
                    else if (t instanceof AssignmentTree a) k = s.primitive(a.getVariable());
                    return k == TypeKind.LONG;
                }
            }.run();
        }
    }

    static final class MathRoundingWithIntArgument extends Inspection {
        MathRoundingWithIntArgument() {
            super("MathRoundingWithIntArgument", "suspicious", Types.REQUIRED,
                    "`Math.round()`, `ceil()`, `floor()` or `rint()` of an integer, which is already whole: probably an"
                            + " integer division that should not be one.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    String n = Scan.name(m);
                    if ((n.equals("round") || n.equals("ceil") || n.equals("floor") || n.equals("rint"))
                            && (Scan.callsStatic(m, "Math", n) || Scan.callsStatic(m, "StrictMath", n))
                            && m.getArguments().size() == 1 && integralKind(s.primitive(m.getArguments().get(0)))) {
                        s.report(m, "'Math." + n + "()' with an integer argument");
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }

    static final class ComparisonOfShortAndChar extends Inspection {
        ComparisonOfShortAndChar() {
            super("ComparisonOfShortAndChar", "suspicious", Types.REQUIRED,
                    "A `short` compared with a `char` with `==` or `!=`, which can differ for values above"
                            + " `Short.MAX_VALUE`.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitBinary(BinaryTree b, Void v) {
                    if (b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO) {
                        TypeKind l = s.primitive(b.getLeftOperand()), r = s.primitive(b.getRightOperand());
                        if (l == TypeKind.SHORT && r == TypeKind.CHAR || l == TypeKind.CHAR && r == TypeKind.SHORT) {
                            s.report(b, "'short' compared with 'char'");
                        }
                    }
                    return super.visitBinary(b, v);
                }
            }.run();
        }
    }

    static int rank(TypeKind k) {
        return switch (k) {
            case BYTE -> 1;
            case SHORT, CHAR -> 2;
            case INT -> 3;
            case LONG -> 4;
            case FLOAT -> 5;
            case DOUBLE -> 6;
            default -> 0;
        };
    }

    static final class LossyConversionCompoundAssignment extends Inspection {
        LossyConversionCompoundAssignment() {
            super("LossyConversionCompoundAssignment", "suspicious", Types.REQUIRED,
                    "A compound assignment that narrows silently, `int i; i += 1.5;`: the result is cast back to the"
                            + " variable's type.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitCompoundAssignment(CompoundAssignmentTree c, Void v) {
                    TypeKind l = s.primitive(c.getVariable()), r = s.primitive(c.getExpression());
                    boolean arithmetic = c.getKind() == Tree.Kind.PLUS_ASSIGNMENT || c.getKind() == Tree.Kind.MINUS_ASSIGNMENT
                            || c.getKind() == Tree.Kind.MULTIPLY_ASSIGNMENT || c.getKind() == Tree.Kind.DIVIDE_ASSIGNMENT
                            || c.getKind() == Tree.Kind.REMAINDER_ASSIGNMENT;
                    if (arithmetic && l != null && r != null && rank(r) > rank(l) && rank(l) >= 3
                            && !(c.getExpression() instanceof LiteralTree && rank(r) <= 3)) {
                        s.report(c, "Implicit cast from '" + r.name().toLowerCase() + "' to '" + l.name().toLowerCase() + "' in compound assignment");
                    }
                    return super.visitCompoundAssignment(c, v);
                }
            }.run();
        }
    }

    static final class AccessStaticViaInstance extends Inspection {
        AccessStaticViaInstance() {
            super("AccessStaticViaInstance", "style", Types.REQUIRED,
                    "A static field or method used through an instance, `obj.staticMethod()`, which reads as if it"
                            + " depended on `obj`. The fix uses the class.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMemberSelect(MemberSelectTree m, Void v) {
                    Element e = s.element(getCurrentPath());
                    if (e != null && e.getModifiers().contains(Modifier.STATIC)
                            && (e.getKind() == ElementKind.METHOD || e.getKind() == ElementKind.FIELD)) {
                        Element q = s.element(new TreePath(getCurrentPath(), m.getExpression()));
                        boolean viaType = q == null || q.getKind().isClass() || q.getKind().isInterface() || q.getKind() == ElementKind.PACKAGE
                                || q.getKind() == ElementKind.TYPE_PARAMETER;
                        if (!viaType && !m.getExpression().toString().equals("super") && !m.getExpression().toString().equals("this")) {
                            String owner = e.getEnclosingElement().getSimpleName().toString();
                            s.report(m.getExpression(), "Static member '" + owner + "." + m.getIdentifier() + "' accessed via an instance",
                                    Scan.pure(m.getExpression()) ? s.replace("Use the class", m.getExpression(), owner) : null);
                        }
                    }
                    return super.visitMemberSelect(m, v);
                }
            }.run();
        }
    }

    static final class BigDecimalMethodWithoutRoundingCalled extends Inspection {
        BigDecimalMethodWithoutRoundingCalled() {
            super("BigDecimalMethodWithoutRoundingCalled", "correctness", Types.REQUIRED,
                    "`BigDecimal.divide(x)` or `setScale(n)` without a rounding mode, which throws `ArithmeticException`"
                            + " when the result does not fit.");
        }

        @Override
        protected void run(Scan s) {
            new Visitor(s) {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                    ExpressionTree r = Scan.receiver(m);
                    if (r != null && (Scan.calls(m, "divide", 1) || Scan.calls(m, "setScale", 1)) && s.is(r, "java.math.BigDecimal")) {
                        s.report(m, "'BigDecimal." + Scan.name(m) + "()' without a rounding mode");
                    }
                    return super.visitMethodInvocation(m, v);
                }
            }.run();
        }
    }
}
