package dev.jpm.format.inspections;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import dev.jpm.format.Comments;
import dev.jpm.format.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IntelliJ's inspections about Javadoc and comments.
 */
final class Javadoc {
    private Javadoc() {
    }

    static List<Inspection> all() {
        return List.of(new DanglingJavadoc(), new JavadocDeclaration(), new CommentedOutCode());
    }

    static boolean isJavadoc(Comments.Comment c) {
        return !c.line() && c.text().startsWith("/**") && !c.text().equals("/**/") && c.text().length() > 4;
    }

    /**
     * The start of every declaration a Javadoc comment can document, mapped to the declaration.
     */
    static Map<Integer, Tree> declarations(Scan s) {
        Map<Integer, Tree> out = new HashMap<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree c, Void v) {
                if (!c.getSimpleName().isEmpty()) out.put(s.start(c), c);
                for (Tree m : c.getMembers()) {
                    if (m instanceof VariableTree f) out.put(s.start(f), f);
                    if (m instanceof MethodTree mt && s.start(mt) >= 0) out.put(s.start(mt), mt);
                    if (m instanceof com.sun.source.tree.BlockTree b) out.put(s.start(b), b);
                }
                return super.visitClass(c, v);
            }
        }.scan(s.root(), null);
        if (s.root().getPackage() != null) out.put(s.start(s.root().getPackage()), s.root().getPackage());
        if (s.root().getModule() != null) out.put(s.start(s.root().getModule()), s.root().getModule());
        return out;
    }

    /**
     * The offset of the first character after {@code from} that is neither whitespace nor in a plain comment.
     */
    static int next(Scan s, int from) {
        String src = s.source();
        int k = from;
        while (true) {
            while (k < src.length() && Character.isWhitespace(src.charAt(k))) k++;
            int at = k;
            Comments.Comment c = s.comments().stream().filter(x -> x.start() == at && !isJavadoc(x)).findFirst().orElse(null);
            if (c == null) return k;
            k = c.end();
        }
    }

    static final class DanglingJavadoc extends Inspection {
        DanglingJavadoc() {
            super("DanglingJavadoc", "style", Types.NONE,
                    "A Javadoc comment (`/** ... */`) that documents nothing: not right before a class, member,"
                            + " initializer, package or module declaration (other comments may come between). Javadoc"
                            + " ignores it; a block comment `/* ... */` says what is meant.");
        }

        @Override
        protected void run(Scan s) {
            Map<Integer, Tree> decls = declarations(s);
            String src = s.source();
            for (Comments.Comment c : s.comments()) {
                if (!isJavadoc(c)) continue;
                int at = next(s, c.end());
                if (!decls.containsKey(at) && !annotationBefore(src, at, decls)) {
                    s.report(c.start(), c.start() + 3, "Dangling Javadoc comment", null);
                }
            }
        }

        private static boolean annotationBefore(String src, int at, Map<Integer, Tree> decls) {
            return src.startsWith("@", at) && !src.startsWith("@interface", at)
                    && decls.keySet().stream().anyMatch(k -> k > at && k - at < 2000 && src.substring(at, k).trim().startsWith("@"));
        }
    }

    private static final Pattern TAG = Pattern.compile("^\\s*\\*?\\s*@(param|return|returns)\\b\\s*(<?[\\w$]*>?)", Pattern.MULTILINE);

    static final class JavadocDeclaration extends Inspection {
        JavadocDeclaration() {
            super("JavadocDeclaration", "style", Types.NONE,
                    "A Javadoc tag that does not match its declaration: `@param` for a parameter or type parameter that"
                            + " does not exist, a duplicate `@param`, or `@return` on a `void` method or constructor.");
        }

        @Override
        protected void run(Scan s) {
            Map<Integer, Tree> decls = declarations(s);
            for (Comments.Comment c : s.comments()) {
                if (!isJavadoc(c)) continue;
                Tree d = decls.get(next(s, c.end()));
                if (d == null) continue;
                Set<String> names = new HashSet<>();
                boolean method = d instanceof MethodTree;
                boolean voidish = d instanceof MethodTree m && (m.getReturnType() == null || Declarations.isVoid(m.getReturnType()));
                if (d instanceof MethodTree m) {
                    for (VariableTree p : m.getParameters()) names.add(p.getName().toString());
                    for (TypeParameterTree t : m.getTypeParameters()) names.add("<" + t.getName() + ">");
                } else if (d instanceof ClassTree ct) {
                    for (TypeParameterTree t : ct.getTypeParameters()) names.add("<" + t.getName() + ">");
                    if (ct.getKind() == Tree.Kind.RECORD) {
                        for (Tree m : ct.getMembers()) {
                            if (m instanceof VariableTree f && !f.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC)) {
                                names.add(f.getName().toString());
                            }
                        }
                    }
                } else {
                    continue;
                }
                Set<String> seen = new HashSet<>();
                Matcher m = TAG.matcher(c.text());
                while (m.find()) {
                    int at = c.start() + m.start(1) - 1;
                    if (m.group(1).equals("param")) {
                        String n = m.group(2);
                        if (n.isEmpty()) continue;
                        if (!names.contains(n)) {
                            s.report(at, at + 6 + 1 + n.length(), "'@param " + n + "' does not name a "
                                    + (n.startsWith("<") ? "type parameter" : "parameter"), null);
                        } else if (!seen.add(n)) {
                            s.report(at, at + 6 + 1 + n.length(), "Duplicate '@param " + n + "'", null);
                        }
                    } else if (method && voidish) {
                        s.report(at, at + 1 + m.group(1).length(), "'@return' on a method that returns nothing", null);
                    }
                }
            }
        }
    }

    static final class CommentedOutCode extends Inspection {
        private static final Pattern CODE_HINT = Pattern.compile("[;{}]\\s*$|^\\s*(import|package|return|if|for|while|private|public|protected)\\b",
                Pattern.MULTILINE);

        CommentedOutCode() {
            super("CommentedOutCode", "style", Types.NONE,
                    "Two or more lines of comments that are Java code: statements, members or a class that compile."
                            + " Version control keeps old code; a comment should say why.");
        }

        @Override
        protected void run(Scan s) {
            String src = s.source();
            List<Comments.Comment> cs = s.comments();
            List<List<Comments.Comment>> groups = new ArrayList<>();
            for (Comments.Comment c : cs) {
                if (isJavadoc(c)) continue;
                if (c.line() && !groups.isEmpty()) {
                    List<Comments.Comment> last = groups.get(groups.size() - 1);
                    Comments.Comment prev = last.get(last.size() - 1);
                    if (prev.line() && onNextLine(src, prev.end(), c.start())) {
                        last.add(c);
                        continue;
                    }
                }
                groups.add(new ArrayList<>(List.of(c)));
            }
            for (List<Comments.Comment> g : groups) {
                StringBuilder body = new StringBuilder();
                for (Comments.Comment c : g) {
                    String t = c.line() ? c.text().substring(2) : c.text().substring(2, c.text().length() - 2);
                    body.append(t.replaceAll("(?m)^\\s*\\*", "")).append('\n');
                }
                String code = body.toString();
                if (code.strip().lines().count() < 2 || !CODE_HINT.matcher(code).find() || code.contains("TODO") || code.contains("http"))
                    continue;
                if (parses("class X { void m() {\n" + code + "\n} }") || parses("class X {\n" + code + "\n}") || parses(code)) {
                    Comments.Comment first = g.get(0), last = g.get(g.size() - 1);
                    s.report(first.start(), last.end(), "Commented out code (" + code.strip().lines().count() + " lines)", null);
                }
            }
        }

        private static boolean onNextLine(String src, int end, int start) {
            String between = src.substring(end, start);
            return between.isBlank() && between.chars().filter(ch -> ch == '\n').count() == 1;
        }

        private static boolean parses(String code) {
            try {
                return new Source("X.java", code).errors().isEmpty();
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
