package dev.jpm.format;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * One version of a file's text and its javac parse, shared by every rule that runs on that text. The parse is
 * javac's own, so it accepts every Java version exactly as the compiler does.
 */
public final class Source {
    /** A syntax error at an offset. */
    public record SyntaxError(int start, int end, String message) {}

    static final JavaCompiler JAVAC = ToolProvider.getSystemJavaCompiler();

    public final String path;
    public final String text;
    private int[] lineStarts;
    private CompilationUnitTree tree;
    private SourcePositions positions;
    private List<SyntaxError> errors;
    private List<Comments.Comment> comments;

    public Source(String path, String text) {
        this.path = path;
        this.text = text;
    }

    /** An in-memory file for javac. */
    static JavaFileObject fileObject(String path, String text) {
        String name = path.replace('\\', '/');
        if (!name.endsWith(".java")) name += ".java";
        return new SimpleJavaFileObject(URI.create("mem:///" + name.replace(" ", "%20")), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }

    private synchronized void parse() {
        if (tree != null || errors != null) return;
        if (JAVAC == null) throw new FormatException("jpm-format needs a JDK (with javac), not a JRE");
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) JAVAC.getTask(
                null, null, diags, List.of("-proc:none", "-Xlint:none"), null, List.of(fileObject(path, text)));
        List<SyntaxError> errs = new ArrayList<>();
        CompilationUnitTree cu = null;
        try {
            for (CompilationUnitTree u : task.parse()) cu = u;
        } catch (Exception | Error e) {
            errs.add(new SyntaxError(0, 0, "javac failed: " + e));
        }
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            int st = (int) Math.max(0, d.getStartPosition());
            int en = (int) Math.max(st, d.getEndPosition());
            errs.add(new SyntaxError(
                    Math.min(st, text.length()), Math.min(en, text.length()), d.getMessage(Locale.ROOT)));
        }
        errors = errs;
        if (errs.isEmpty()) {
            tree = cu;
            positions = Trees.instance(task).getSourcePositions();
        }
    }

    /** Syntax errors; empty if the file parses. */
    public List<SyntaxError> errors() {
        parse();
        return errors;
    }

    public CompilationUnitTree tree() {
        parse();
        if (tree == null) throw new IllegalStateException(path + " does not parse");
        return tree;
    }

    public int start(Tree node) {
        parse();
        return (int) positions.getStartPosition(tree, node);
    }

    public int end(Tree node) {
        parse();
        return (int) positions.getEndPosition(tree, node);
    }

    public synchronized List<Comments.Comment> comments() {
        if (comments == null) comments = Comments.scan(text);
        return comments;
    }

    private synchronized int[] lineStarts() {
        if (lineStarts == null) {
            int[] ls = new int[16];
            int n = 1;
            for (int k = 0; k < text.length(); k++) {
                if (text.charAt(k) == '\n') {
                    if (n == ls.length) ls = Arrays.copyOf(ls, n * 2);
                    ls[n++] = k + 1;
                }
            }
            lineStarts = Arrays.copyOf(ls, n);
        }
        return lineStarts;
    }

    /** The file's line separator: CRLF if its first line ends with one, else LF. */
    public static String newline(String text) {
        int i = text.indexOf('\n');
        return i > 0 && text.charAt(i - 1) == '\r' ? "\r\n" : "\n";
    }

    /** 1-based line of an offset. */
    public int line(int offset) {
        int[] ls = lineStarts();
        int k = Arrays.binarySearch(ls, offset);
        return (k >= 0 ? k : -k - 2) + 1;
    }

    /** 1-based column of an offset. */
    public int column(int offset) {
        return offset - lineStart(line(offset)) + 1;
    }

    /** Offset where a 1-based line starts (the end of the text past the last line). */
    public int lineStart(int line) {
        int[] ls = lineStarts();
        if (line < 1) return 0;
        return line > ls.length ? text.length() : ls[line - 1];
    }

    public int lineCount() {
        return lineStarts().length;
    }
}
