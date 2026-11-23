package dev.jpm.format.intellij;

import dev.jpm.format.Comments;
import dev.jpm.format.FileContext;
import dev.jpm.format.api.Context;

import java.util.List;

/**
 * IntelliJ's "Add a space at line comment start", off by default: a line comment keeps the text right after its
 * {@code //}, so {@code //TODO} and {@code //$NON-NLS-1$} stay as written. Palantir Java Format adds a space. The
 * layout engine changes only whitespace, so the line comments of its output are those of its input, in the same
 * order; each one that had no space after {@code //} gets its text back.
 *
 * <p>IntelliJ also leaves the space before a comment that ends a line of code as it was ({@code case 'a':// fall
 * through}, {@code int x;  // why}), where the layout engine puts one space.
 */
public final class LineCommentSpacing implements IntelliJRule {
    public static final String ID = "LineCommentSpacing";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String format(Context ctx) {
        if (!(ctx instanceof FileContext fc)) return ctx.source();
        return trailingSpace(fc.layoutInput().text, lineCommentText(fc, ctx.source()));
    }

    private static String lineCommentText(FileContext fc, String src) {
        List<Comments.Comment> before = Comments.scan(fc.layoutInput().text).stream()
                .filter(Comments.Comment::line)
                .toList();
        List<Comments.Comment> after =
                Comments.scan(src).stream().filter(Comments.Comment::line).toList();
        if (before.size() != after.size()) return src;
        StringBuilder out = new StringBuilder(src.length());
        int copied = 0;
        for (int i = 0; i < after.size(); i++) {
            String was = before.get(i).text(), now = after.get(i).text();
            if (was.length() > 2 && !Character.isWhitespace(was.charAt(2)) && now.equals("// " + was.substring(2))) {
                out.append(src, copied, after.get(i).start()).append(was);
                copied = after.get(i).end();
            }
        }
        if (copied == 0) return src;
        return out.append(src, copied, src.length()).toString();
    }

    /**
     * Gives each comment that ends a line of code in both {@code input} and {@code src} its spacing from the input.
     */
    private static String trailingSpace(String input, String src) {
        List<Comments.Comment> before = Comments.scan(input), after = Comments.scan(src);
        if (before.size() != after.size()) return src;
        StringBuilder out = new StringBuilder(src.length());
        int copied = 0;
        for (int i = 0; i < after.size(); i++) {
            Comments.Comment was = before.get(i), now = after.get(i);
            if (now.text().startsWith("/**")) continue;
            String space = trailingSpace(input, was), current = trailingSpace(src, now);
            if (space == null || current == null || space.equals(current)) continue;
            out.append(src, copied, now.start() - current.length()).append(space);
            copied = now.start();
        }
        if (copied == 0) return src;
        return out.append(src, copied, src.length()).toString();
    }

    /**
     * The whitespace before {@code c} if code comes before it on its line and nothing after it, or null.
     */
    private static String trailingSpace(String text, Comments.Comment c) {
        int eol = text.indexOf('\n', c.end());
        if (!text.substring(c.end(), eol < 0 ? text.length() : eol).isBlank()) return null;
        int k = c.start();
        while (k > 0 && (text.charAt(k - 1) == ' ' || text.charAt(k - 1) == '\t')) k--;
        if (k == 0 || text.charAt(k - 1) == '\n' || text.charAt(k - 1) == '\r') return null;
        return text.substring(k, c.start());
    }
}
