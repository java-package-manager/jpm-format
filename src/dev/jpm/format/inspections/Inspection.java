package dev.jpm.format.inspections;

import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Lint;

/**
 * One of IntelliJ IDEA's Java inspections that are on by default, reimplemented on javac's syntax tree. The id is
 * IntelliJ's short name, so {@code @SuppressWarnings("<id>")} and {@code //noinspection <id>} written for IntelliJ
 * suppress it here too. Inspections that need types use them when the project's classpath is known; those that only
 * work with types say so with {@link Types#REQUIRED}.
 */
public abstract class Inspection implements Lint {
    /**
     * How an inspection uses type information.
     */
    public enum Types {
        /**
         * Works on the syntax tree alone.
         */
        NONE,
        /**
         * Works on the syntax tree, and more precisely with types.
         */
        OPTIONAL,
        /**
         * Needs types and is skipped without them.
         */
        REQUIRED
    }

    private final String id;
    private final String category;
    private final String summary;
    private final Types types;

    /**
     * @param summary what the inspection reports, for the documentation: sentences in Markdown
     */
    protected Inspection(String id, String category, Types types, String summary) {
        this.id = id;
        this.category = category;
        this.types = types;
        this.summary = summary;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String category() {
        return category;
    }

    /**
     * What the inspection reports, in Markdown, as docs/inspections.md shows it.
     */
    public final String summary() {
        return summary;
    }

    public final Types types() {
        return types;
    }

    @Override
    public final String docs() {
        return "https://github.com/java-package-manager/jpm-format/blob/main/docs/inspections.md#"
                + id.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public final boolean typeAware() {
        return types == Types.REQUIRED;
    }

    @Override
    public final void check(Context ctx) {
        Scan s = new Scan(ctx, types != Types.NONE);
        if (types == Types.REQUIRED && !s.typed()) return;
        run(s);
    }

    /**
     * Reports what this inspection finds in the file {@code s} reads.
     */
    protected abstract void run(Scan s);

    /**
     * A scanner over {@code s}'s tree with the path to each node, for inspections that look at parents.
     */
    protected abstract static class Visitor extends TreePathScanner<Void, Void> {
        protected final Scan s;

        protected Visitor(Scan s) {
            this.s = s;
        }

        /**
         * The parent of the node being visited, or null at the top.
         */
        protected Tree parent() {
            var p = getCurrentPath().getParentPath();
            return p == null ? null : p.getLeaf();
        }

        /**
         * Scans the whole file.
         */
        public final void run() {
            scan(new com.sun.source.util.TreePath(s.root()), null);
        }
    }
}
