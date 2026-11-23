package dev.jpm.format.rules;

import dev.jpm.format.Comments;
import dev.jpm.format.FileContext;
import dev.jpm.format.Source;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Formatter;

import java.time.Year;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts {@code formatter.licenseHeader} at the top of every file, replacing the comments before the package
 * declaration, like Spotless's licenseHeader step. {@code $YEAR} becomes the current year, but a year (or
 * range) already in a matching header is kept.
 */
public final class LicenseHeader implements Formatter {
    @Override
    public String id() {
        return "LicenseHeader";
    }

    @Override
    public String category() {
        return "format";
    }

    @Override
    public String docs() {
        return Builtins.docs(id());
    }

    @Override
    public String format(Context ctx) {
        FileContext fc = (FileContext) ctx;
        String header = fc.settings().licenseHeader();
        if (header == null) return ctx.source();
        String src = ctx.source();
        String nl = Source.newline(src);
        header = header.replace("\n", nl);
        List<Comments.Comment> comments = fc.parsed().comments();
        int code = 0;
        int firstCommentEnd = -1;
        for (Comments.Comment c : comments) {
            if (!Comments.blank(src, code, c.start())) break;
            if (firstCommentEnd < 0) firstCommentEnd = c.end();
            code = c.end();
        }
        while (code < src.length() && Character.isWhitespace(src.charAt(code))) code++;
        String prefix = src.substring(0, code);
        boolean declarationFirst = src.startsWith("package", code)
                || src.startsWith("import", code)
                || src.startsWith("module", code)
                || src.startsWith("open", code);

        String want = withYear(
                header, declarationFirst ? prefix : firstCommentEnd < 0 ? "" : src.substring(0, firstCommentEnd));
        if (declarationFirst) {
            return want + nl + (code < src.length() ? nl : "") + src.substring(code);
        }
        if (firstCommentEnd >= 0 && src.substring(0, firstCommentEnd).strip().equals(want)) return src;
        return want + nl + nl + src.stripLeading();
    }

    /**
     * The header with {@code $YEAR} filled in, keeping the year from {@code existing} when it matches.
     */
    static String withYear(String header, String existing) {
        String year = String.valueOf(Year.now().getValue());
        if (!header.contains("$YEAR")) return header;
        String[] parts = header.split(Pattern.quote("$YEAR"), -1);
        StringBuilder re = new StringBuilder();
        for (int k = 0; k < parts.length; k++) {
            if (k > 0) re.append("(\\d{4}(?:\\s*-\\s*\\d{4})?)");
            re.append(Pattern.quote(parts[k]));
        }
        Matcher m = Pattern.compile(re.toString()).matcher(existing.strip());
        if (m.matches()) return existing.strip();
        return header.replace("$YEAR", year);
    }
}
