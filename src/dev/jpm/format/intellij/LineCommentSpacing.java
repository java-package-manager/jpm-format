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
        String src = ctx.source();
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
}
