/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package dev.jpm.format.palantir.java;

import com.google.errorprone.annotations.Immutable;

/**
 * Options for a google-java-format invocation.
 *
 * <p>Like gofmt, the google-java-format CLI exposes <em>no</em> configuration options (aside from {@code --aosp}).
 *
 * <p>The goal of google-java-format is to provide consistent formatting, and to free developers from arguments over
 * style choices. It is an explicit non-goal to support developers' individual preferences, and in fact it would work
 * directly against our primary goals.
 */
@Immutable
public final class JavaFormatterOptions {

    public enum Style {
        /** The default Palantir Java Style configuration. */
        PALANTIR(2, 120),

        /** The default Google Java Style configuration. */
        GOOGLE(1, 100),

        /** The AOSP-compliant configuration. */
        AOSP(2, 100);

        private final int indentationMultiplier;
        private final int maxLineLength;

        Style(int indentationMultiplier, int maxLineLength) {
            this.indentationMultiplier = indentationMultiplier;
            this.maxLineLength = maxLineLength;
        }

        int indentationMultiplier() {
            return indentationMultiplier;
        }

        public int maxLineLength() {
            return maxLineLength;
        }
    }

    private final Style style;

    private final boolean formatJavadoc;

    // jpm-format: widths that override the style's, or 0 for the style's own
    private final int maxLineLength;

    private final int indent;

    private final int continuationIndent;

    // jpm-format: keep the input's line breaks instead of choosing them
    private final boolean keepLineBreaks;

    private JavaFormatterOptions(
            Style style,
            boolean formatJavadoc,
            int maxLineLength,
            int indent,
            int continuationIndent,
            boolean keepLineBreaks) {
        this.style = style;
        this.formatJavadoc = formatJavadoc;
        this.maxLineLength = maxLineLength;
        this.indent = indent;
        this.continuationIndent = continuationIndent;
        this.keepLineBreaks = keepLineBreaks;
    }

    /**
     * jpm-format: whether optional line breaks follow the input (taken where the input had a newline, never added),
     * with wrapped lines indented from the start of the line they continue, as IntelliJ IDEA does.
     */
    public boolean keepLineBreaks() {
        return keepLineBreaks;
    }

    /** Returns the multiplier for the unit of indent. */
    public int indentationMultiplier() {
        return style.indentationMultiplier();
    }

    public int maxLineLength() {
        return maxLineLength > 0 ? maxLineLength : style.maxLineLength();
    }

    /** jpm-format: the block indent in spaces; the style's is twice its multiplier. */
    public int indent() {
        return indent > 0 ? indent : 2 * style.indentationMultiplier();
    }

    /** jpm-format: the indent of wrapped lines in spaces; the style's is four times its multiplier. */
    public int continuationIndent() {
        return continuationIndent > 0 ? continuationIndent : 4 * style.indentationMultiplier();
    }

    public boolean formatJavadoc() {
        return formatJavadoc;
    }

    /** Returns the code style. */
    public Style style() {
        return style;
    }

    /** Returns the default formatting options. */
    public static JavaFormatterOptions defaultOptions() {
        return builder().build();
    }

    /** Returns a builder for {@link JavaFormatterOptions}. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder for {@link JavaFormatterOptions}. */
    public static final class Builder {
        // default is still GOOGLE just because lots of hand-rolled tests rely on this behaviour
        private Style style = Style.GOOGLE;

        private boolean formatJavadoc = false;

        private int maxLineLength;

        private int indent;

        private int continuationIndent;

        private boolean keepLineBreaks;

        private Builder() {}

        public Builder style(Style style) {
            this.style = style;
            return this;
        }

        public Builder formatJavadoc(boolean formatJavadoc) {
            this.formatJavadoc = formatJavadoc;
            return this;
        }

        /** jpm-format: the column limit instead of the style's. */
        public Builder maxLineLength(int maxLineLength) {
            this.maxLineLength = positive(maxLineLength, "maxLineLength");
            return this;
        }

        /** jpm-format: the block indent in spaces instead of the style's. */
        public Builder indent(int indent) {
            this.indent = positive(indent, "indent");
            return this;
        }

        /** jpm-format: the indent of wrapped lines in spaces instead of the style's. */
        public Builder continuationIndent(int continuationIndent) {
            this.continuationIndent = positive(continuationIndent, "continuationIndent");
            return this;
        }

        /** jpm-format: keep the input's line breaks. */
        public Builder keepLineBreaks(boolean keepLineBreaks) {
            this.keepLineBreaks = keepLineBreaks;
            return this;
        }

        private static int positive(int n, String what) {
            if (n <= 0) throw new IllegalArgumentException(what + " must be positive, not " + n);
            return n;
        }

        public JavaFormatterOptions build() {
            return new JavaFormatterOptions(
                    style, formatJavadoc, maxLineLength, indent, continuationIndent, keepLineBreaks);
        }
    }
}
