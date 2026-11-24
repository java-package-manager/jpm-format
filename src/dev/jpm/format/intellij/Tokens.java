package dev.jpm.format.intellij;

import java.util.ArrayList;
import java.util.List;

/**
 * The tokens of a Java source, comments included, for the layout engine ({@link Layout}). Only what the layout needs
 * is told apart: words (identifiers, keywords and literals that are words), numbers, strings, characters, text
 * blocks, comments and operators. Operators are read longest first; {@link Syntax} splits {@code >>} and
 * {@code >>>} where they close type arguments.
 */
final class Tokens {
    enum Kind {
        WORD, NUMBER, STRING, CHAR, TEXT_BLOCK, LINE_COMMENT, BLOCK_COMMENT, OP
    }

    /**
     * A token at {@code [start, end)} of the source.
     */
    record Token(Kind kind, int start, int end, String text) {
        boolean comment() {
            return kind == Kind.LINE_COMMENT || kind == Kind.BLOCK_COMMENT;
        }

        boolean is(String s) {
            return text.equals(s);
        }

        boolean op(String s) {
            return kind == Kind.OP && text.equals(s);
        }

        boolean word(String s) {
            return kind == Kind.WORD && text.equals(s);
        }
    }

    private static final String[] OPERATORS = {
            ">>>=", "<<=", ">>=", ">>>", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=", "+=", "-=",
            "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>"
    };

    private Tokens() {
    }

    static List<Token> scan(String s) {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                i++;
                continue;
            }
            int start = i;
            Kind kind;
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
                kind = Kind.LINE_COMMENT;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int close = s.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
                kind = Kind.BLOCK_COMMENT;
            } else if (s.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < n && !s.startsWith("\"\"\"", i)) i += s.charAt(i) == '\\' ? 2 : 1;
                i = Math.min(n, i + 3);
                kind = Kind.TEXT_BLOCK;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && s.charAt(i) != c && s.charAt(i) != '\n') i += s.charAt(i) == '\\' ? 2 : 1;
                i = Math.min(n, i + 1);
                kind = c == '"' ? Kind.STRING : Kind.CHAR;
            } else if (Character.isJavaIdentifierStart(c) || c == '\\') {
                i++;
                while (i < n && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '\\')) i++;
                kind = Kind.WORD;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                boolean hex = c == '0' && i + 1 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X');
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '.') {
                        i++;
                        boolean exponent = hex ? d == 'p' || d == 'P' : d == 'e' || d == 'E';
                        if (exponent && i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                    } else {
                        break;
                    }
                }
                kind = Kind.NUMBER;
            } else {
                int len = 1;
                for (String op : OPERATORS) {
                    if (s.startsWith(op, i)) {
                        len = op.length();
                        break;
                    }
                }
                i += len;
                kind = Kind.OP;
            }
            out.add(new Token(kind, start, i, s.substring(start, i)));
        }
        return out;
    }
}
