# Rules

Every rule's id is the name used in config (`linter.rules.<category>.<id>`), in
`// jpm-ignore lint/<category>/<id>: <reason>`, in `@SuppressWarnings("<id>")` and in `//noinspection <id>`.
Formatters are applied by `jpm format`; their findings in `--check` are differences from the formatted
text. Lint rules only report.

## format

### JavaFormat

The layout of the code: indentation, line breaks, spacing. jpm-format's fork of Palantir Java Format, close to
IntelliJ's default style: 120 columns, 4-space indents, 8-space continuation indents. Configure with
`formatter.lineWidth` (20 to 1000), `formatter.indentWidth` (1 to 16), `formatter.continuationIndentWidth`
(1 to 32; twice `indentWidth` by default, as IntelliJ's 4 and 8) and `formatter.useTabs`. Text blocks are never re-indented. Turn the whole formatter off with
`formatter.enabled: false`.

### EmptyBodies

IntelliJ's "Keep when reformatting: simple methods / classes / blocks in one line", off by default: an empty body is
written `{` and `}` on two lines, the brace under the line that opens it. Applies to methods and constructors, named
classes, initializer blocks and the blocks of `if`, `else`, loops, `try`, `catch`, `finally` and `synchronized`.
Lambdas, anonymous classes, `switch` cases and array initializers keep `{}`. Runs after `JavaFormat`; off with
`formatter.style: "palantir"`.

### MemberBlankLines

IntelliJ's "Minimum blank lines": one blank line around methods, nested classes and initializer blocks; none
required between fields, after the class header or before its end. The blank line goes before the member's
comments (its Javadoc, a line comment), which Palantir Java Format leaves out. Runs after `JavaFormat`; off with
`formatter.style: "palantir"`.

### KeepBlankLines

IntelliJ's "Keep maximum blank lines" (in declarations, in code and before `}`): blank lines the code already had
stay, up to `formatter.maxBlankLines` (2 by default, 1 to 10). Palantir Java Format keeps one and none before a
closing brace. Lines the layout engine joins stay joined. Runs after `JavaFormat`; off with
`formatter.style: "palantir"`.

### JavadocLayout

IntelliJ's default Javadoc formatting, which Palantir Java Format does not do:

- "Align parameter descriptions" and "Align thrown exception descriptions": the descriptions of the `@param`
  tags start in one column, after the longest name, and so do those of the `@throws` and `@exception` tags; their
  continuation lines start under the description
- "Blank lines: after description": a blank line between the description and the first block tag
- "Do not wrap one line comments", off: `/** Text. */` becomes three lines
- "Indent continuation lines", off: continuation lines of the other block tags start under the tag
- "Generate `<p>` on empty lines": an empty line of the description that more text follows gets a `<p>`
- a description written on the line after its `@param` or `@throws` name moves up to the tag

Text is not re-wrapped. Comments without a `*` at the start of every line, and comments with `<pre>` in their
tags, are left as they are. Runs after `KeepBlankLines`; off with `formatter.style: "palantir"`.

### ImportOrder

Sorts imports into `imports.groups` (default: all other imports, then `java.*` and `javax.*`, then static
imports), each group alphabetical and separated by a blank line. A pattern is `*`, a prefix like `java.*`,
or an exact name; prefix with `static ` for static imports; the most specific pattern wins. Duplicates are
dropped. With `imports.wildcardThreshold` > 0, that many imports from one package become `package.*`.
Imports with comments between them are left alone. Off with `imports.sort: false`.

### LicenseHeader

Puts `formatter.licenseHeader` (a comment, or `{ "file": "…" }`) at the top of every file, replacing the
comments before the `package` line, like Spotless. `$YEAR` is the current year, but a year or range already in
a matching header is kept. In a file without a package, other leading comments such as a class's Javadoc are
kept after the header. Only runs when a header is configured.

## style

### UnusedImport

Removes unused imports, redundant ones (`java.lang.*` and the file's own package) and duplicates. Names used
only in Javadoc (`{@link}`, `{@linkplain}`, `{@value}`, `@see`, `@throws`) count as used. Wildcard imports are
removed only when redundant or duplicated. Off with `imports.removeUnused: false`.

### UnnecessaryModifier

Removes modifiers the language implies: `public` and `abstract` on interface methods, `public static final` on
interface fields, `public static` on types in interfaces, `static` on nested interfaces, enums and records,
`final` on records, `private` on enum constructors. Removing them never changes behaviour.

### MissingOverrideAnnotation

*Needs types.* A method that overrides or implements a supertype's method but has no `@Override`. The fix
adds it (offered, not applied).

## correctness

### StringEquality

*Needs types.* Two strings compared with `==` or `!=`, which compares identity, not content. Comparisons
with `null` are fine. The fix, `a.equals(b)`, is offered, not applied: it throws if `a` is null.

### UnusedLocalVariable

A local variable never used after its declaration, or a parameter of a private method that its body never
uses. Names starting with `ignored` or `unused`, and `_`, are skipped. Reported only, since removing the
variable could drop side effects of its initializer. Also suppressed by `unused`.

### UnusedPrivateMember

A private field or method that nothing in the file refers to. Annotated members (frameworks may use them),
serialization members (`serialVersionUID`, `readObject` …), record components and files that import Lombok
are skipped. Also suppressed by `unused`.

## complexity

### PointlessBooleanExpression

A comparison with a boolean literal that can be simpler: `x == true` is `x`, `x != true` and `x == false`
are `!x`, `x && true` and `x || false` are `x`. The fix is offered, not applied: with a `Boolean`, the
simpler form has a different static type.

## suspicious

### EmptyCatchBlock

A catch block with nothing in it hides the exception. A comment in the block, or a parameter named
`ignored`, `ignore`, `expected` or `_`, marks it as deliberate. Reported only.

## suppressions

### UnusedSuppression

A `jpm-ignore` comment that suppresses nothing. Only reported when the rules it names ran.

### InvalidSuppression

A `jpm-ignore` comment without a reason, with an unknown target, or a `jpm-ignore-start` without its
`jpm-ignore-end`. It suppresses nothing.

## Always on

- `syntax/ParseError`: the file does not parse; nothing else runs on it, and it is left unchanged.
- `internal/NotIdempotent` (`--debug`): formatting a formatter's output again changes it.
- `internal/RuleCrash`: a rule threw, or a formatter produced code that does not parse (its change is dropped).
