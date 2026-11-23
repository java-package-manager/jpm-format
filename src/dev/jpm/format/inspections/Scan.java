package dev.jpm.format.inspections;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import dev.jpm.format.Comments;
import dev.jpm.format.FileContext;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.TypeInfo;

import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * The file an {@link Inspection} reads: its tree (the attributed one when types are known, which has the same
 * offsets), positions, types, comments, and small questions about trees that several inspections ask.
 */
public final class Scan {
    private final Context ctx;
    private final TypeInfo types;
    private final CompilationUnitTree root;
    private final SourcePositions positions;

    Scan(Context ctx, boolean wantTypes) {
        this.ctx = ctx;
        this.types = wantTypes ? ctx.types().orElse(null) : null;
        this.root = types != null ? types.tree() : ctx.tree();
        this.positions = types != null ? types.trees().getSourcePositions() : null;
    }

    public CompilationUnitTree root() {
        return root;
    }

    public boolean typed() {
        return types != null;
    }

    public String source() {
        return ctx.source();
    }

    public String path() {
        return ctx.path();
    }

    public int start(Tree t) {
        return positions != null ? (int) positions.getStartPosition(root, t) : ctx.start(t);
    }

    public int end(Tree t) {
        return positions != null ? (int) positions.getEndPosition(root, t) : ctx.end(t);
    }

    public String text(Tree t) {
        return ctx.source().substring(start(t), end(t));
    }

    public int column(int offset) {
        return ctx.column(offset);
    }

    public void report(Tree t, String message) {
        ctx.report(start(t), end(t), message, null);
    }

    public void report(Tree t, String message, Fix fix) {
        ctx.report(start(t), end(t), message, fix);
    }

    public void report(int start, int end, String message, Fix fix) {
        ctx.report(start, end, message, fix);
    }

    /**
     * A fix that replaces {@code t} with {@code text}.
     */
    public Fix replace(String description, Tree t, String text) {
        return Fix.replace(description, start(t), end(t), text);
    }

    /**
     * The comments in the file.
     */
    public List<Comments.Comment> comments() {
        return ((FileContext) ctx).parsed().comments();
    }

    /**
     * True if a comment lies inside {@code t}.
     */
    public boolean hasComment(Tree t) {
        int s = start(t), e = end(t);
        for (Comments.Comment c : comments()) if (c.start() >= s && c.end() <= e) return true;
        return false;
    }

    /**
     * The type of {@code t}, or null without types or when javac could not resolve it.
     */
    public TypeMirror type(Tree t) {
        if (types == null) return null;
        TreePath p = TreePath.getPath(root, t);
        if (p == null) return null;
        TypeMirror m = types.trees().getTypeMirror(p);
        return m == null || m.getKind() == TypeKind.ERROR ? null : m;
    }

    /**
     * The symbol {@code t} declares or refers to, or null.
     */
    public Element element(Tree t) {
        if (types == null) return null;
        TreePath p = TreePath.getPath(root, t);
        return p == null ? null : types.trees().getElement(p);
    }

    /**
     * The symbol of the node at {@code path}, or null.
     */
    public Element element(TreePath path) {
        return types == null ? null : types.trees().getElement(path);
    }

    /**
     * True if {@code t}'s type is the class {@code qualifiedName} (erased).
     */
    public boolean is(Tree t, String qualifiedName) {
        return types != null && types.is(type(t), qualifiedName);
    }

    /**
     * True if {@code t}'s type is {@code qualifiedName} or a subtype of it (erased).
     */
    public boolean isA(Tree t, String qualifiedName) {
        return isA(type(t), qualifiedName);
    }

    public boolean isA(TypeMirror m, String qualifiedName) {
        if (types == null || m == null || m.getKind() != TypeKind.DECLARED && m.getKind() != TypeKind.TYPEVAR) {
            return false;
        }
        TypeElement e = types.elements().getTypeElement(qualifiedName);
        return e != null && types.types().isSubtype(types.types().erasure(m), types.types().erasure(e.asType()));
    }

    /**
     * True if {@code t}'s type is an array.
     */
    public boolean isArray(Tree t) {
        TypeMirror m = type(t);
        return m != null && m.getKind() == TypeKind.ARRAY;
    }

    /**
     * {@code t}'s primitive kind, or null if it is not primitive or not known.
     */
    public TypeKind primitive(Tree t) {
        TypeMirror m = type(t);
        return m != null && m.getKind().isPrimitive() ? m.getKind() : null;
    }

    public javax.lang.model.util.Types typeUtils() {
        return types == null ? null : types.types();
    }

    public javax.lang.model.util.Elements elementUtils() {
        return types == null ? null : types.elements();
    }

    /**
     * {@code e} without the parentheses around it.
     */
    public static ExpressionTree strip(ExpressionTree e) {
        while (e instanceof ParenthesizedTree p) e = p.getExpression();
        return e;
    }

    /**
     * True if {@code a} and {@code b} are the same code, ignoring layout, comments and parentheses.
     */
    public static boolean same(Tree a, Tree b) {
        if (a instanceof ExpressionTree x && b instanceof ExpressionTree y) {
            a = strip(x);
            b = strip(y);
        }
        return a != null && b != null && a.getKind() == b.getKind() && a.toString().equals(b.toString());
    }

    /**
     * True if evaluating {@code e} cannot change anything: names, field reads, literals, {@code this}.
     */
    public static boolean pure(ExpressionTree e) {
        e = strip(e);
        return switch (e) {
            case IdentifierTree i -> true;
            case LiteralTree l -> true;
            case MemberSelectTree m -> pure(m.getExpression());
            case com.sun.source.tree.ArrayAccessTree a -> pure(a.getExpression()) && pure(a.getIndex());
            case TypeCastTree c -> pure(c.getExpression());
            case UnaryTree u -> (u.getKind() == Tree.Kind.UNARY_MINUS
                    || u.getKind() == Tree.Kind.UNARY_PLUS
                    || u.getKind() == Tree.Kind.LOGICAL_COMPLEMENT
                    || u.getKind() == Tree.Kind.BITWISE_COMPLEMENT)
                    && pure(u.getExpression());
            case com.sun.source.tree.BinaryTree b -> pure(b.getLeftOperand()) && pure(b.getRightOperand());
            default -> false;
        };
    }

    /**
     * The value of an int or long literal, with a leading minus, or null.
     */
    public static Long integer(ExpressionTree e) {
        e = strip(e);
        if (e instanceof UnaryTree u && u.getKind() == Tree.Kind.UNARY_MINUS) {
            Long v = integer(u.getExpression());
            return v == null ? null : -v;
        }
        if (e instanceof LiteralTree l && l.getValue() instanceof Number n
                && (l.getKind() == Tree.Kind.INT_LITERAL || l.getKind() == Tree.Kind.LONG_LITERAL)) {
            return n.longValue();
        }
        return null;
    }

    /**
     * The value of a string literal, or null.
     */
    public static String string(ExpressionTree e) {
        e = strip(e);
        return e instanceof LiteralTree l && l.getKind() == Tree.Kind.STRING_LITERAL ? (String) l.getValue() : null;
    }

    /**
     * The value of a boolean literal, or null.
     */
    public static Boolean bool(ExpressionTree e) {
        e = strip(e);
        return e instanceof LiteralTree l && l.getKind() == Tree.Kind.BOOLEAN_LITERAL ? (Boolean) l.getValue() : null;
    }

    public static boolean isNull(ExpressionTree e) {
        return strip(e) instanceof LiteralTree l && l.getKind() == Tree.Kind.NULL_LITERAL;
    }

    /**
     * The name of the method {@code m} calls.
     */
    public static String name(MethodInvocationTree m) {
        return switch (m.getMethodSelect()) {
            case MemberSelectTree s -> s.getIdentifier().toString();
            case IdentifierTree i -> i.getName().toString();
            default -> "";
        };
    }

    /**
     * The receiver of {@code m}, or null when it has none ({@code f()}).
     */
    public static ExpressionTree receiver(MethodInvocationTree m) {
        return m.getMethodSelect() instanceof MemberSelectTree s ? s.getExpression() : null;
    }

    /**
     * True if {@code m} is {@code name(...)} with {@code args} arguments, on any receiver.
     */
    public static boolean calls(Tree t, String name, int args) {
        return t instanceof MethodInvocationTree m && name(m).equals(name) && m.getArguments().size() == args;
    }

    /**
     * True if {@code m} is {@code Type.name(...)} for a receiver written {@code Type} or {@code ...Type}.
     */
    public static boolean callsStatic(Tree t, String type, String name) {
        if (!(t instanceof MethodInvocationTree m) || !name(m).equals(name)) return false;
        ExpressionTree r = receiver(m);
        if (r == null) return false;
        String s = r.toString();
        return s.equals(type) || s.endsWith("." + type);
    }

    /**
     * The statements of {@code t}: those of a block, or {@code t} alone.
     */
    public static List<? extends StatementTree> statements(StatementTree t) {
        if (t == null) return List.of();
        return t instanceof BlockTree b ? b.getStatements() : List.of(t);
    }

    /**
     * The expression of an expression statement, or null.
     */
    public static ExpressionTree expression(StatementTree t) {
        return t instanceof ExpressionStatementTree e ? e.getExpression() : null;
    }

    /**
     * The simple name a type is written with: {@code List} for {@code java.util.List<String>}.
     */
    public static String simpleName(Tree type) {
        if (type == null) return "";
        String s = type.toString();
        int lt = s.indexOf('<');
        if (lt >= 0) s = s.substring(0, lt);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}
