package dev.jpm.format.intellij;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DeconstructionPatternTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EmptyStatementTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.api.Context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * What the layout engine ({@link Layout}) needs to know from javac's syntax tree, per token: which operators are
 * binary, prefix or postfix, which parenthesis closes a cast, what each brace opens, which parentheses line up
 * their contents, where statements, members and case labels start, and which lines IntelliJ breaks or joins.
 *
 * <p>The tree's positions are offsets in the source; the facts are kept per token, after {@code >>} and
 * {@code >>>} that close type arguments are split into single {@code >}.
 */
final class Syntax {
    static final byte BIN = 1, PREFIX = 2, POSTFIX = 3, CAST_CLOSE = 4, CASE_COLON = 5, LABEL_COLON = 6,
            TYPE_ARGS_CLOSE = 7, EMPTY_STATEMENT = 8;

    static final byte CODE = 1, LAMBDA = 2, CLASS = 3, ANONYMOUS = 4, ENUM = 5, SWITCH = 6, SWITCH_EXPRESSION = 7,
            ARRAY = 8, SIMPLE_ENUM = 9;

    /**
     * Where a line starts. {@code CASE_LEVEL}: a statement of a {@code case} group at the label's indent, as
     * IntelliJ puts a block of the group that is not on the label's line and every statement from a block on.
     */
    static final byte STATEMENT = 1, CASE = 2, CASE_STATEMENT = 3, BODY = 4, ANNOTATED = 5, ENUM_CONSTANT = 6,
            CASE_LEVEL = 7;

    /**
     * What IntelliJ does with a line comment after a method's or constructor's annotation (on a field, a class or
     * anything else it stays): {@code IF_LONG}, it goes on a line of its own if its line would end past the line
     * width; {@code SHIFTED}, it is on a line of its own one indent further in (where no Javadoc comes first).
     */
    static final byte IF_LONG = 1, SHIFTED = 2;

    private static final Set<String> TYPE_USE_NULLNESS = Set.of("org.jspecify.annotations.Nullable",
            "org.jspecify.annotations.NonNull", "org.jetbrains.annotations.Nullable", "org.jetbrains.annotations.NotNull");
    private static final Set<String> MODIFIERS = Set.of("public", "protected", "private", "static", "abstract",
            "final", "native", "synchronized", "transient", "volatile", "strictfp", "default", "sealed", "non");

    final List<Tokens.Token> tokens;
    final byte[] role;
    final byte[] brace;
    final boolean[] align;
    /**
     * The {@code (} of a record header or a record pattern: IntelliJ wraps its components where a line would end past
     * the line width.
     */
    final boolean[] wrapIfLong;
    /**
     * The {@code (} of a record pattern: past the line width, IntelliJ puts its components on the next line and its
     * {@code )} on a line of its own.
     */
    final boolean[] patternWrap;
    final byte[] annotationComment;
    final byte[] start;
    final int[] owner;
    final boolean[] breakBefore;
    final boolean[] joinBefore;
    final boolean[] blankBefore;
    final int[] alignTo;
    final int[] inner;
    int[] nodeStart;
    int[] nodeParent;
    boolean[] nodeBinary;
    final boolean[] joinIfFits;
    final int[] match;

    private Syntax(List<Tokens.Token> tokens) {
        int n = tokens.size();
        this.tokens = tokens;
        role = new byte[n];
        brace = new byte[n];
        align = new boolean[n];
        wrapIfLong = new boolean[n];
        patternWrap = new boolean[n];
        annotationComment = new byte[n];
        start = new byte[n];
        owner = new int[n];
        breakBefore = new boolean[n];
        joinBefore = new boolean[n];
        blankBefore = new boolean[n];
        alignTo = new int[n];
        inner = new int[n];
        joinIfFits = new boolean[n];
        match = new int[n];
        Arrays.fill(owner, -1);
        Arrays.fill(match, -1);
        Arrays.fill(alignTo, -1);
        Arrays.fill(inner, -1);
    }

    static Syntax of(Context ctx, String text) {
        List<Tokens.Token> raw = Tokens.scan(text);
        Facts facts = new Facts(ctx, raw, text);
        facts.scan(ctx.tree());
        List<Tokens.Token> split = new ArrayList<>(raw.size());
        for (Tokens.Token t : raw) {
            boolean shift = t.op(">>") || t.op(">>>");
            if (shift && facts.role[t.start()] != BIN) {
                for (int k = 0; k < t.text().length(); k++) {
                    split.add(new Tokens.Token(Tokens.Kind.OP, t.start() + k, t.start() + k + 1, ">"));
                }
            } else {
                split.add(t);
            }
        }
        Syntax s = new Syntax(split);
        for (int i = 0; i < split.size(); i++) {
            int at = split.get(i).start();
            s.role[i] = facts.role[at];
            s.brace[i] = facts.brace[at];
            s.align[i] = facts.align[at];
            s.wrapIfLong[i] = facts.wrapIfLong[at];
            s.patternWrap[i] = facts.patternWrap[at];
            s.annotationComment[i] = facts.annotationComment[at];
            s.start[i] = facts.start[at];
            s.breakBefore[i] = facts.breakBefore[at];
            s.joinBefore[i] = facts.joinBefore[at];
            s.blankBefore[i] = facts.blankBefore[at];
        }
        int[] index = new int[text.length() + 1];
        Arrays.fill(index, -1);
        for (int i = 0; i < split.size(); i++) index[split.get(i).start()] = i;
        for (int i = 0; i < split.size(); i++) {
            int o = facts.owner[split.get(i).start()];
            if (o >= 0) s.owner[i] = index[o];
            int a = facts.alignTo[split.get(i).start()];
            if (a >= 0) s.alignTo[i] = index[a];
            s.inner[i] = facts.inner[split.get(i).start()];
            s.joinIfFits[i] = facts.joinIfFits[split.get(i).start()];
        }
        s.nodeStart = new int[facts.nodeStart.size()];
        s.nodeParent = new int[facts.nodeStart.size()];
        s.nodeBinary = new boolean[facts.nodeStart.size()];
        for (int k = 0; k < s.nodeStart.length; k++) {
            s.nodeStart[k] = index[facts.nodeStart.get(k)];
            s.nodeParent[k] = facts.nodeParent.get(k);
            s.nodeBinary[k] = facts.nodeBinary.get(k);
        }
        s.pair();
        return s;
    }

    private void pair() {
        Deque<Integer> stack = new ArrayDeque<>();
        Deque<Integer> angles = new ArrayDeque<>();
        for (int i = 0; i < tokens.size(); i++) {
            Tokens.Token t = tokens.get(i);
            if (t.kind() != Tokens.Kind.OP) continue;
            switch (t.text()) {
                case "{", "(", "[" -> stack.push(i);
                case "}", ")", "]" -> {
                    if (!stack.isEmpty()) {
                        int o = stack.pop();
                        match[o] = i;
                        match[i] = o;
                    }
                }
                case "<" -> {
                    if (role[i] != BIN) angles.push(i);
                }
                case ">" -> {
                    if (role[i] != BIN && !angles.isEmpty()) {
                        int o = angles.pop();
                        if (o > 0 && tokens.get(o - 1).op(".")) role[i] = TYPE_ARGS_CLOSE;
                    }
                }
                default -> {
                }
            }
        }
    }

    /**
     * The facts by source offset, as the tree gives them, before tokens are split.
     */
    private static final class Facts extends TreeScanner<Void, Void> {
        private final Context ctx;
        private final String text;
        private final List<Tokens.Token> tokens;
        private final Set<String> nullnessNames = new HashSet<>();
        private final int[] starts;
        final byte[] role;
        final byte[] brace;
        final boolean[] align;
        final boolean[] wrapIfLong;
        final boolean[] patternWrap;
        final byte[] annotationComment;
        final byte[] start;
        final int[] owner;
        final boolean[] breakBefore;
        final boolean[] joinBefore;
        final boolean[] blankBefore;
        final boolean[] joinIfFits;
        final int[] alignTo;
        final int[] inner;
        final List<Integer> nodeStart = new ArrayList<>();
        final List<Integer> nodeParent = new ArrayList<>();
        final List<Boolean> nodeBinary = new ArrayList<>();
        private final Deque<Integer> nodes = new ArrayDeque<>();
        private final Set<Tree> members = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<ClassTree> anonymous = Collections.newSetFromMap(new IdentityHashMap<>());

        Facts(Context ctx, List<Tokens.Token> tokens, String text) {
            int length = text.length();
            this.ctx = ctx;
            this.text = text;
            this.tokens = tokens;
            starts = new int[tokens.size()];
            for (int i = 0; i < starts.length; i++) starts[i] = tokens.get(i).start();
            role = new byte[length + 1];
            brace = new byte[length + 1];
            align = new boolean[length + 1];
            wrapIfLong = new boolean[length + 1];
            patternWrap = new boolean[length + 1];
            annotationComment = new byte[length + 1];
            start = new byte[length + 1];
            owner = new int[length + 1];
            breakBefore = new boolean[length + 1];
            joinBefore = new boolean[length + 1];
            blankBefore = new boolean[length + 1];
            alignTo = new int[length + 1];
            joinIfFits = new boolean[length + 1];
            inner = new int[length + 1];
            Arrays.fill(owner, -1);
            Arrays.fill(alignTo, -1);
            Arrays.fill(inner, -1);
        }

        void scan(CompilationUnitTree cu) {
            for (ImportTree i : cu.getImports()) {
                if (i.isStatic()) continue;
                String name = i.getQualifiedIdentifier().toString();
                for (String known : TYPE_USE_NULLNESS) {
                    String pkg = known.substring(0, known.lastIndexOf('.'));
                    if (name.equals(known) || name.equals(pkg + ".*"))
                        nullnessNames.add(known.substring(pkg.length() + 1));
                }
            }
            Tree previous = null;
            List<Tree> top = new ArrayList<>();
            if (cu.getPackage() != null) {
                top.add(cu.getPackage());
                for (AnnotationTree a : cu.getPackage().getAnnotations()) {
                    int next = code(e(a));
                    if (next < tokens.size() && start[at(next)] == 0) start[at(next)] = ANNOTATED;
                }
            }
            top.addAll(cu.getImports());
            top.addAll(cu.getTypeDecls());
            if (cu.getModule() != null && !top.contains(cu.getModule())) top.add(cu.getModule());
            if (cu.getModule() != null) {
                for (AnnotationTree a : cu.getModule().getAnnotations()) {
                    int next = code(e(a));
                    if (next < tokens.size() && start[at(next)] == 0) start[at(next)] = ANNOTATED;
                    if (next < tokens.size()) breakBefore[at(next)] = true;
                }
            }
            for (Tree t : top) {
                statement(t, true);
                boolean imports = previous != null && previous.getKind() == Tree.Kind.IMPORT
                        && t.getKind() == Tree.Kind.IMPORT;
                if (previous != null && !imports && s(t) >= 0) {
                    int i = index(e(previous));
                    int end = e(previous);
                    while (i < tokens.size() && tokens.get(i).comment() && !lineBetween(end, tokens.get(i).start())) {
                        end = tokens.get(i).end();
                        i++;
                    }
                    if (i < tokens.size()) blankBefore[at(i)] = true;
                }
                previous = t;
                if (t instanceof ModuleTree m) {
                    int b = braceAfter(s(m));
                    if (b >= 0) brace(b, CLASS);
                    for (Tree d : m.getDirectives()) statement(d, true);
                }
            }
            scan(cu, null);
        }

        private boolean lineBetween(int from, int to) {
            for (int k = from; k < to; k++) if (text.charAt(k) == '\n') return true;
            return false;
        }

        private int index(int offset) {
            int k = Arrays.binarySearch(starts, offset);
            return k >= 0 ? k : -k - 1;
        }

        private int code(int from) {
            int i = index(from);
            while (i < tokens.size() && tokens.get(i).comment()) i++;
            return i;
        }

        private int codeBefore(int i) {
            int k = i - 1;
            while (k >= 0 && tokens.get(k).comment()) k--;
            return k;
        }

        private int find(int from, String op) {
            int depth = 0;
            for (int i = index(from); i < tokens.size(); i++) {
                Tokens.Token t = tokens.get(i);
                if (t.kind() != Tokens.Kind.OP) continue;
                if (depth == 0 && t.is(op)) return i;
                if (t.is("(") || t.is("[")) depth++;
                else if (t.is(")") || t.is("]")) depth--;
                if (depth < 0) return -1;
            }
            return -1;
        }

        private int word(int from, String w) {
            for (int i = index(from); i < tokens.size(); i++) if (tokens.get(i).word(w)) return i;
            return -1;
        }

        private int at(int i) {
            return i >= 0 && i < tokens.size() ? tokens.get(i).start() : -1;
        }

        private int s(Tree t) {
            return t == null ? -1 : ctx.start(t);
        }

        private int e(Tree t) {
            return t == null ? -1 : ctx.end(t);
        }

        private void statement(Tree t, boolean breakLine) {
            int p = s(t);
            if (p < 0) return;
            int i = code(p);
            if (i >= tokens.size()) return;
            int prev = codeBefore(i);
            if (prev >= 0 && tokens.get(prev).op(",")) return;
            if (t instanceof BlockTree && prev >= 0 && tokens.get(prev).word("static")) i = prev;
            int at = at(i);
            if (start[at] == 0 || start[at] == ANNOTATED) start[at] = STATEMENT;
            if (breakLine) breakBefore[at] = true;
        }

        private void mark(int i, byte r) {
            int at = at(i);
            if (at >= 0) role[at] = r;
        }

        private void operatorAfter(Tree left) {
            int i = code(e(left));
            if (i < tokens.size() && tokens.get(i).kind() == Tokens.Kind.OP) mark(i, BIN);
        }

        private void annotations(ModifiersTree mods, boolean wrap) {
            if (mods == null) return;
            if (s(mods) < 0) return;
            if (!mods.getFlags().isEmpty()) {
                int end = e(mods);
                for (int i = code(s(mods)); i < tokens.size() && tokens.get(i).start() < end; i++) {
                    Tokens.Token t = tokens.get(i);
                    if (t.kind() != Tokens.Kind.WORD || !MODIFIERS.contains(t.text())) continue;
                    if (i > 0 && tokens.get(i - 1).op("@")) continue;
                    if (i + 1 < tokens.size() && !tokens.get(i + 1).comment()) joinBefore[at(i + 1)] = true;
                }
            }
            int expected = code(s(mods));
            boolean leading = true;
            for (AnnotationTree a : mods.getAnnotations()) {
                int as = s(a);
                if (as < 0) continue;
                int ai = code(as);
                if (expected >= 0 && ai != expected) leading = false;
                int next = code(e(a));
                expected = next;
                if (next >= tokens.size()) continue;
                int at = at(next);
                if (start[at] == 0) start[at] = ANNOTATED;
                if (wrap && leading && !(a == mods.getAnnotations().getLast() && typeUseNullness(a, next)
                        && !lineBetween(e(a), at))) {
                    breakBefore[at] = true;
                }
            }
        }

        /**
         * The comments after a method's or constructor's annotations. When its modifiers are only annotations, IntelliJ
         * puts a line comment on the line of the last one on a line of its own, and every comment between the last
         * annotation and the type one indent further in, unless a Javadoc comment comes first (not after a type-use
         * nullness annotation, which belongs to the type). A line comment after any other annotation goes on a line
         * of its own only when its line would end past the line width.
         */
        private void annotationComments(MethodTree t) {
            ModifiersTree mods = t.getModifiers();
            if (mods == null || s(mods) < 0 || mods.getAnnotations().isEmpty()) return;
            int first = code(s(t));
            boolean doc = first > 0 && first < tokens.size() && tokens.get(first - 1).text().startsWith("/**");
            AnnotationTree last = mods.getAnnotations().getLast();
            for (AnnotationTree a : mods.getAnnotations()) {
                if (e(a) < 0) continue;
                int k = index(e(a));
                int next = code(e(a));
                if (k >= tokens.size() || next >= tokens.size()) continue;
                Tokens.Token c = tokens.get(k);
                boolean trailing = c.kind() == Tokens.Kind.LINE_COMMENT && !lineBetween(e(a), c.start());
                if (a != last || !mods.getFlags().isEmpty() || typeUseNullness(a, next)) {
                    if (trailing) annotationComment[c.start()] = IF_LONG;
                    continue;
                }
                if (trailing) breakBefore[c.start()] = true;
                if (!doc) for (int j = k; j < next; j++) annotationComment[at(j)] = SHIFTED;
            }
        }

        /**
         * Whether IntelliJ keeps an annotation on the line of the type after it: one of the type-use nullness
         * annotations it knows, by its qualified name or by a simple name the imports give one of them, right before
         * the type.
         */
        private boolean typeUseNullness(AnnotationTree a, int next) {
            Tokens.Token t = tokens.get(next);
            if (t.kind() != Tokens.Kind.WORD || MODIFIERS.contains(t.text())
                    || Set.of("class", "interface", "enum", "record").contains(t.text())) return false;
            String name = a.getAnnotationType().toString();
            return TYPE_USE_NULLNESS.contains(name) || nullnessNames.contains(name.substring(name.lastIndexOf('.') + 1));
        }

        private void brace(int i, byte kind) {
            int at = at(i);
            if (at >= 0 && tokens.get(i).op("{") && brace[at] == 0) brace[at] = kind;
        }

        private int braceAfter(int from) {
            int depth = 0;
            for (int i = index(from); i < tokens.size(); i++) {
                Tokens.Token t = tokens.get(i);
                if (t.kind() != Tokens.Kind.OP) continue;
                if (depth == 0 && t.is("{")) return i;
                if (t.is("(") || t.is("[")) depth++;
                else if ((t.is(")") || t.is("]")) && depth > 0) depth--;
            }
            return -1;
        }

        /**
         * A class's, method's or variable's modifiers, keyword or type and name go on one line: a line break between them is
         * never how formatted code has it, so it is joined (IntelliJ keeps it).
         */
        private void header(Tree t, ModifiersTree mods, int name) {
            if (name < 0 || s(t) < 0) return;
            int from = code(mods == null || s(mods) < 0 ? s(t) : Math.max(s(t), e(mods)));
            if (mods != null && !mods.getFlags().isEmpty() && s(mods) >= 0) {
                int first = code(s(mods));
                for (AnnotationTree a : mods.getAnnotations()) if (s(a) >= 0 && code(s(a)) == first) first = code(e(a));
                from = Math.min(from, first);
            }
            for (int k = from; k < name && k < tokens.size(); k++) {
                Tokens.Token tk = tokens.get(k);
                if (tk.op(",") || tk.op("=") || tk.op(";") || tk.op("{") || tk.op("}")) return;
            }
            for (int k = from + 1; k <= name && k < tokens.size(); k++) {
                if (tokens.get(k).comment() || tokens.get(k - 1).comment()) continue;
                joinBefore[at(k)] = true;
            }
        }

        private void body(StatementTree body, Tree owner) {
            if (body == null || body instanceof BlockTree) return;
            int i = code(s(body));
            if (i >= tokens.size()) return;
            int at = at(i);
            start[at] = BODY;
            this.owner[at] = s(owner) < 0 ? -1 : at(code(s(owner)));
        }

        private void continuation(int keyword, Tree owner) {
            if (keyword < 0) return;
            int at = at(keyword);
            this.owner[at] = at(code(s(owner)));
            int prev = codeBefore(keyword);
            if (prev >= 0 && tokens.get(prev).op("}") && prev == keyword - 1) joinBefore[at] = true;
        }

        @Override
        public Void visitBinary(BinaryTree t, Void v) {
            operatorAfter(t.getLeftOperand());
            return super.visitBinary(t, v);
        }

        @Override
        public Void scan(Tree t, Void v) {
            if (t == null || t instanceof BlockTree || t instanceof ModifiersTree || t instanceof CompilationUnitTree) {
                return super.scan(t, v);
            }
            int first = s(t) < 0 ? tokens.size() : code(s(t));
            if (t instanceof VariableTree vt && first < tokens.size()) {
                int name = word(Math.max(s(t), e(vt.getType())), vt.getName().toString());
                if (name > first && tokens.get(codeBefore(name)).op(",")) first = name;
            }
            int end = e(t);
            if (first >= tokens.size() || end <= 0) return super.scan(t, v);
            int id = nodeStart.size();
            nodeStart.add(at(first));
            nodeParent.add(nodes.isEmpty() ? -1 : nodes.peek());
            nodeBinary.add(t instanceof BinaryTree);
            for (int i = first; i < tokens.size() && tokens.get(i).start() < end; i++)
                inner[tokens.get(i).start()] = id;
            nodes.push(id);
            try {
                return super.scan(t, v);
            } finally {
                nodes.pop();
            }
        }

        @Override
        public Void visitAssignment(AssignmentTree t, Void v) {
            operatorAfter(t.getVariable());
            return super.visitAssignment(t, v);
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree t, Void v) {
            operatorAfter(t.getVariable());
            return super.visitCompoundAssignment(t, v);
        }

        @Override
        public Void visitConditionalExpression(ConditionalExpressionTree t, Void v) {
            operatorAfter(t.getCondition());
            operatorAfter(t.getTrueExpression());
            return super.visitConditionalExpression(t, v);
        }

        @Override
        public Void visitUnary(UnaryTree t, Void v) {
            switch (t.getKind()) {
                case POSTFIX_INCREMENT, POSTFIX_DECREMENT -> mark(index(e(t)) - 1, POSTFIX);
                default -> mark(code(s(t)), PREFIX);
            }
            return super.visitUnary(t, v);
        }

        @Override
        public Void visitTypeCast(TypeCastTree t, Void v) {
            int i = find(e(t.getType()), ")");
            if (i >= 0) mark(i, CAST_CLOSE);
            return super.visitTypeCast(t, v);
        }

        @Override
        public Void visitEmptyStatement(EmptyStatementTree t, Void v) {
            mark(code(s(t)), EMPTY_STATEMENT);
            return super.visitEmptyStatement(t, v);
        }

        @Override
        public Void visitLabeledStatement(LabeledStatementTree t, Void v) {
            int i = find(s(t), ":");
            if (i >= 0) mark(i, LABEL_COLON);
            statement(t.getStatement(), true);
            return super.visitLabeledStatement(t, v);
        }

        @Override
        public Void visitBlock(BlockTree t, Void v) {
            brace(code(s(t)), CODE);
            for (StatementTree st : t.getStatements()) statement(st, true);
            return super.visitBlock(t, v);
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree t, Void v) {
            if (t.getBody() instanceof BlockTree b) brace(code(s(b)), LAMBDA);
            else if (t.getBody() != null) {
                int i = code(s(t.getBody()));
                if (i < tokens.size()) owner[at(i)] = at(code(s(t)));
            }
            return super.visitLambdaExpression(t, v);
        }

        @Override
        public Void visitNewClass(NewClassTree t, Void v) {
            if (t.getClassBody() != null) {
                anonymous.add(t.getClassBody());
                int from = t.getArguments().isEmpty() ? e(t.getIdentifier()) : e(t.getArguments().getLast());
                int i = braceAfter(from);
                if (i >= 0) brace(i, ANONYMOUS);
            }
            return super.visitNewClass(t, v);
        }

        @Override
        public Void visitClass(ClassTree t, Void v) {
            boolean anon = anonymous.contains(t);
            int body = anon ? -1 : 0;
            if (!anon) {
                annotations(t.getModifiers(), true);
                int from = Math.max(s(t), e(t.getModifiers()));
                header(t, t.getModifiers(), word(from, t.getSimpleName().toString()));
                if (t.getKind() == Tree.Kind.RECORD) {
                    int p = find(from, "(");
                    if (p >= 0) {
                        align[at(p)] = true;
                        wrapIfLong[at(p)] = true;
                    }
                }
                int b = braceAfter(from);
                if (b >= 0) brace(b, t.getKind() == Tree.Kind.ENUM ? ENUM : CLASS);
                body = b >= 0 ? at(b) : 0;
                if (b >= 0 && b + 1 < tokens.size() && tokens.get(b + 1).op("}") && !lineBetween(s(t), at(b))
                        && t.getKind() != Tree.Kind.ENUM) {
                    breakBefore[at(b + 1)] = true;
                }
            }
            boolean firstConstant = !simpleEnum(t, body);
            if (!firstConstant) brace[body] = SIMPLE_ENUM;
            for (Tree m : t.getMembers()) {
                if (s(m) < 0 || s(m) < body) continue;
                boolean constant = t.getKind() == Tree.Kind.ENUM && m instanceof VariableTree vt
                        && isEnumConstantSyntax(vt);
                if (constant) {
                    members.add(m);
                    int i = code(s(m));
                    int at = at(i);
                    start[at] = ENUM_CONSTANT;
                    if (firstConstant) breakBefore[at] = true;
                    firstConstant = false;
                    continue;
                }
                statement(m, true);
                members.add(m);
                if (m instanceof BlockTree b && b.getStatements().isEmpty()) {
                    int close = index(e(b)) - 1;
                    if (close >= 0 && tokens.get(close).op("}")) breakBefore[at(close)] = true;
                }
                if (m instanceof MethodTree mt) annotations(mt.getModifiers(), true);
                else if (m instanceof VariableTree vt) annotations(vt.getModifiers(), true);
            }
            return super.visitClass(t, v);
        }

        private boolean simpleEnum(ClassTree t, int body) {
            if (t.getKind() != Tree.Kind.ENUM || body <= 0) return false;
            int open = index(body);
            int close = -1;
            int depth = 0;
            for (int i = open; i < tokens.size(); i++) {
                Tokens.Token k = tokens.get(i);
                if (k.comment()) return false;
                if (k.op("{") || k.op("(")) depth++;
                else if (k.op("}") || k.op(")")) depth--;
                else if (depth == 1 && k.op(";")) return false;
                if (depth == 0) {
                    close = i;
                    break;
                }
                if (depth > 1 || i > open && k.kind() != Tokens.Kind.WORD && !k.op(",")) return false;
            }
            return close > open + 1 && !lineBetween(body, tokens.get(close).end());
        }

        private boolean isEnumConstantSyntax(VariableTree vt) {
            int modsEnd = vt.getModifiers() == null ? -1 : e(vt.getModifiers());
            int from = Math.max(s(vt), modsEnd);
            int i = word(from, vt.getName().toString());
            if (i < 0) return false;
            int prev = codeBefore(i);
            if (prev >= 0) {
                Tokens.Token p = tokens.get(prev);
                boolean annotation = modsEnd > 0 && p.start() < modsEnd;
                if (!annotation && !p.op("{") && !p.op(",")) return false;
            }
            int next = i + 1;
            while (next < tokens.size() && tokens.get(next).comment()) next++;
            if (next >= tokens.size()) return false;
            Tokens.Token n = tokens.get(next);
            return n.op(",") || n.op(";") || n.op("(") || n.op("{") || n.op("}");
        }

        @Override
        public Void visitVariable(VariableTree t, Void v) {
            annotations(t.getModifiers(), false);
            if (!members.contains(t) && t.getModifiers() != null) {
                for (AnnotationTree a : t.getModifiers().getAnnotations()) {
                    int next = index(e(a));
                    if (next < tokens.size() && tokens.get(next).kind() == Tokens.Kind.WORD
                            && MODIFIERS.contains(tokens.get(next).text())) {
                        joinBefore[at(next)] = true;
                    }
                }
            }
            if (s(t) >= 0 && !t.getName().isEmpty()) {
                boolean typed = t.getType() != null && s(t.getType()) >= 0 && e(t.getType()) > s(t.getType());
                int from = typed ? e(t.getType()) : Math.max(s(t), t.getModifiers() == null ? -1 : e(t.getModifiers()));
                int name = word(from, t.getName().toString());
                if (name >= 0 && at(name) < e(t)) header(t, t.getModifiers(), name);
            }
            return super.visitVariable(t, v);
        }

        @Override
        public Void visitMethod(MethodTree t, Void v) {
            annotationComments(t);
            int from = Math.max(s(t), e(t.getModifiers()));
            if (t.getReturnType() != null) from = Math.max(from, e(t.getReturnType()));
            if (!t.getTypeParameters().isEmpty()) from = Math.max(from, e(t.getTypeParameters().getLast()));
            int p = find(from, "(");
            if (p >= 0) align[at(p)] = true;
            if (p > 0 && (t.getBody() == null || at(p) < s(t.getBody()))
                    && tokens.get(p - 1).kind() == Tokens.Kind.WORD) {
                header(t, t.getModifiers(), p - 1);
            }
            return super.visitMethod(t, v);
        }

        @Override
        public Void visitForLoop(ForLoopTree t, Void v) {
            int p = find(s(t), "(");
            if (p >= 0) align[at(p)] = true;
            body(t.getStatement(), t);
            return super.visitForLoop(t, v);
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree t, Void v) {
            body(t.getStatement(), t);
            return super.visitEnhancedForLoop(t, v);
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree t, Void v) {
            body(t.getStatement(), t);
            return super.visitWhileLoop(t, v);
        }

        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree t, Void v) {
            body(t.getStatement(), t);
            continuation(word(e(t.getStatement()), "while"), t);
            return super.visitDoWhileLoop(t, v);
        }

        @Override
        public Void visitIf(IfTree t, Void v) {
            body(t.getThenStatement(), t);
            if (t.getElseStatement() != null) {
                body(t.getElseStatement(), t);
                continuation(word(e(t.getThenStatement()), "else"), t);
            }
            return super.visitIf(t, v);
        }

        @Override
        public Void visitTry(TryTree t, Void v) {
            if (!t.getResources().isEmpty()) {
                int p = find(s(t), "(");
                if (p >= 0) align[at(p)] = true;
            }
            for (CatchTree c : t.getCatches()) continuation(code(s(c)), t);
            if (t.getFinallyBlock() != null) {
                Tree last = t.getCatches().isEmpty() ? t.getBlock() : t.getCatches().getLast();
                continuation(word(e(last), "finally"), t);
            }
            return super.visitTry(t, v);
        }

        @Override
        public Void visitSwitch(SwitchTree t, Void v) {
            int b = braceAfter(e(t.getExpression()));
            if (b >= 0) brace(b, SWITCH);
            cases(t.getCases());
            return super.visitSwitch(t, v);
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree t, Void v) {
            int b = braceAfter(e(t.getExpression()));
            if (b >= 0) brace(b, SWITCH_EXPRESSION);
            cases(t.getCases());
            return super.visitSwitchExpression(t, v);
        }

        private void cases(List<? extends CaseTree> cases) {
            for (CaseTree c : cases) {
                int i = code(s(c));
                if (i >= tokens.size()) continue;
                int at = at(i);
                start[at] = CASE;
                breakBefore[at] = true;
                List<? extends Tree> labels = c.getLabels();
                if (labels.size() > 1) {
                    int first = code(s(labels.getFirst()));
                    for (Tree label : labels.subList(1, labels.size())) {
                        int k = code(s(label));
                        if (first < tokens.size() && k < tokens.size()) alignTo[at(k)] = at(first);
                    }
                }
                if (c.getCaseKind() == CaseTree.CaseKind.RULE && c.getBody() != null) {
                    int b = code(s(c.getBody()));
                    int prev = codeBefore(b);
                    if (b < tokens.size() && prev == b - 1 && tokens.get(prev).op("->")) joinIfFits[at(b)] = true;
                }
                if (c.getCaseKind() == CaseTree.CaseKind.STATEMENT) {
                    int colon = find(s(c), ":");
                    if (colon >= 0) mark(colon, CASE_COLON);
                    boolean first = true;
                    boolean level = false;
                    for (StatementTree st : c.getStatements()) {
                        int k = code(s(st));
                        if (k >= tokens.size()) continue;
                        int sat = at(k);
                        boolean block = st instanceof BlockTree;
                        if (block && first && colon >= 0 && k == colon + 1) joinBefore[sat] = true;
                        else if (block) level = true;
                        start[sat] = level ? CASE_LEVEL : CASE_STATEMENT;
                        breakBefore[sat] = !(first && block);
                        if (block) level = true;
                        first = false;
                    }
                }
            }
        }

        @Override
        public Void visitDeconstructionPattern(DeconstructionPatternTree t, Void v) {
            int p = find(e(t.getDeconstructor()), "(");
            if (p >= 0) {
                patternWrap[at(p)] = true;
                wrapIfLong[at(p)] = true;
            }
            return super.visitDeconstructionPattern(t, v);
        }

        @Override
        public Void visitNewArray(NewArrayTree t, Void v) {
            if (t.getInitializers() != null) {
                int i = code(s(t));
                if (i < tokens.size() && !tokens.get(i).op("{")) i = braceAfter(s(t));
                if (i >= 0) brace(i, ARRAY);
            }
            return super.visitNewArray(t, v);
        }
    }
}
