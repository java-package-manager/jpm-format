package dev.jpm.format;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import dev.jpm.format.api.Context;
import dev.jpm.format.api.Finding;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.Lint;
import dev.jpm.format.api.Severity;
import dev.jpm.format.api.TypeInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** The {@link Context} of one rule on one version of a file. */
public final class FileContext implements Context {
    private final Source src;
    private final Lint rule;
    private final Severity level;
    private final Config.Settings settings;
    private final Supplier<TypeInfo> types;
    private final List<Finding> sink;
    private final Source layoutInput;

    FileContext(
            Source src,
            Lint rule,
            Severity level,
            Config.Settings settings,
            Supplier<TypeInfo> types,
            List<Finding> sink) {
        this(src, rule, level, settings, types, sink, null);
    }

    FileContext(
            Source src,
            Lint rule,
            Severity level,
            Config.Settings settings,
            Supplier<TypeInfo> types,
            List<Finding> sink,
            Source layoutInput) {
        this.src = src;
        this.rule = rule;
        this.level = level;
        this.settings = settings;
        this.types = types;
        this.sink = sink;
        this.layoutInput = layoutInput;
    }

    /** The file's settings, for built-in rules. */
    public Config.Settings settings() {
        return settings;
    }

    /**
     * The file as the layout engine (JavaFormat) received it, for the rules that adjust its output; the current
     * source if the layout engine has not run. The layout engine only changes whitespace, so the two have the same
     * non-whitespace text, which is how its output lines up with what it replaced.
     */
    public Source layoutInput() {
        return layoutInput != null ? layoutInput : src;
    }

    /** The parsed source, for built-in rules. */
    public Source parsed() {
        return src;
    }

    @Override
    public String path() {
        return src.path;
    }

    @Override
    public String source() {
        return src.text;
    }

    @Override
    public CompilationUnitTree tree() {
        return src.tree();
    }

    @Override
    public int start(Tree node) {
        return src.start(node);
    }

    @Override
    public int end(Tree node) {
        return src.end(node);
    }

    @Override
    public int line(int offset) {
        return src.line(offset);
    }

    @Override
    public int column(int offset) {
        return src.column(offset);
    }

    @Override
    public int lineStart(int line) {
        return src.lineStart(line);
    }

    @Override
    public Optional<TypeInfo> types() {
        return Optional.ofNullable(types == null ? null : types.get());
    }

    @Override
    public Map<String, Object> options() {
        return settings.options(rule.id());
    }

    @Override
    public void report(int start, int end, String message, Fix fix) {
        int s = clamp(start), e = Math.max(s, clamp(end));
        synchronized (sink) {
            sink.add(finding(src, rule.id(), rule.category(), level, s, e, message, fix));
        }
    }

    private int clamp(int o) {
        return Math.max(0, Math.min(o, src.text.length()));
    }

    static Finding finding(
            Source src, String rule, String category, Severity level, int s, int e, String message, Fix fix) {
        return new Finding(
                rule,
                category,
                level,
                src.path,
                s,
                e,
                src.line(s),
                src.column(s),
                src.line(e),
                src.column(e),
                message,
                fix);
    }
}
