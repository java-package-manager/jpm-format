# 0006. The IntelliJ style gets its own layout engine; Palantir leaves the codebase

Status: accepted; the IntelliJ style runs on the new engine. Left: removing the fork, which `"palantir"` still runs
on. Where [0002](0002-formatting-pipeline.md) says "the layout engine (the Palantir fork)", it is now `Layout` in
the IntelliJ style.

## Decision

- The `intellij` style no longer runs on the Palantir fork in `src/dev/jpm/format/palantir/`. It gets its own
  layout engine in `src/dev/jpm/format/intellij/`, and the fork is deleted once that engine replaces it.
- The engine rewrites only the whitespace between javac's tokens: the indent from the syntax tree (block
  indent, continuation indent, alignment of multiline parameters, `for` headers and try resources), the
  spaces between tokens, and the blank lines and comments the IntelliJ rules already handle. Line breaks
  stay where the code has them, except where IntelliJ's default code style always breaks or joins lines (one
  statement per line, block contents on their own lines, `{` at the end of the line, `} else`) and where it
  moves a statement body past the line width to the next line. So the engine needs no line-breaking search,
  which is most of what Palantir is.
- javac stays the parser. Palantir used it too; it is not part of what leaves.
- `"palantir"` and `"palantir@<version>"` stay as `formatter.style`, run as external formatters
  (`ExternalFormatter`) from Palantir's own jar, like `google-java-format`. Nothing of Palantir's code is
  in the repository afterwards.
- The parity check (`checks/Parity.java`) is how the new engine is judged; it compares output only, so it
  does not depend on the engine. The default switches to the new engine once its count is at or below the
  baseline in `checks/parity/baseline.txt` (it switched at 905, against the fork's 980).
- What the parity corpus does not show is pinned down in `IntelliJRulesTest` and `LayoutTest`, as input and
  expected output; `LayoutTest`'s cases are IntelliJ's own output.
  Upstream's formatter tests (`test/assets/palantir/testdata`, `PalantirTestdataTest`) leave with the fork.

## Why

- The fork is about 15,500 lines that jpm-format has to maintain, with 46 changes of its own that upstream
  would have to take before jpm-format could use upstream instead. Most of these are the `keepLineBreaks`
  mode, which switches off what Palantir is for: choosing where lines break.
- IntelliJ's formatter works on whitespace between tokens with the line breaks given, so an engine that does
  the same can follow it rule by rule, where the fork has to be bent toward it.
