package dev.jpm.format.intellij;

import com.sun.source.tree.ImportTree;
import dev.jpm.format.FileContext;
import dev.jpm.format.Source;
import dev.jpm.format.api.Context;
import dev.jpm.format.rules.ImportOrder;

import java.util.List;

/**
 * The blank lines between imports, as IntelliJ's formatter sets them from its import layout ({@code imports.groups}):
 * none between imports of one group, one between imports of different groups. The order is left as it is;
 * {@code ImportOrder} sorts. Imports with a comment between them are left alone.
 */
public final class ImportBlankLines implements IntelliJRule {
    public static final String ID = "ImportBlankLines";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String format(Context ctx) {
        List<? extends ImportTree> imports = ctx.tree().getImports();
        if (imports.size() < 2) return ctx.source();
        String src = ctx.source();
        String nl = Source.newline(src);
        List<List<String>> groups = ((FileContext) ctx).settings().importGroups();
        StringBuilder out = new StringBuilder(src.length());
        int copied = 0;
        for (int k = 1; k < imports.size(); k++) {
            ImportTree a = imports.get(k - 1), b = imports.get(k);
            int end = ctx.end(a), start = ctx.start(b);
            if (end < 0 || start < end || !src.substring(end, start).isBlank()) continue;
            boolean same = ImportOrder.group(a.isStatic(), a.getQualifiedIdentifier().toString(), groups)
                    == ImportOrder.group(b.isStatic(), b.getQualifiedIdentifier().toString(), groups);
            int lineStart = src.lastIndexOf('\n', start - 1) + 1;
            String gap = same ? nl : nl + nl;
            String indent = src.substring(lineStart, start);
            out.append(src, copied, end).append(gap).append(indent);
            copied = start;
        }
        if (copied == 0) return src;
        return out.append(src, copied, src.length()).toString();
    }
}
