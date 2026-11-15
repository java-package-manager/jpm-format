# Palantir Java Format (fork)

`src/dev/jpm/format/palantir/` is a fork of [Palantir Java Format](https://github.com/palantir/palantir-java-format),
itself a fork of [google-java-format](https://github.com/google/google-java-format). Both are Apache 2.0 (see
[LICENSE](LICENSE), which also ships in the jar as `META-INF/LICENSE-palantir-java-format`); every forked file
keeps its original copyright header. jpm-format's own code is MIT.

## Where it came from

| | |
|---|---|
| Upstream | https://github.com/palantir/palantir-java-format |
| Version | 2.103.0 (tag `2.103.0`, commit `4891cee5d7e79470c2af7b0660ab0eb867442532`) |
| Copied from | `palantir-java-format/src/main/java` and `palantir-java-format-spi/src/main/java` |

## What changed

The first commit of the fork changes only this, so it formats exactly as 2.103.0:

- Package `com.palantir.javaformat` renamed to `dev.jpm.format.palantir`.
- Left out: Palantir's command-line tool (`Main`, `CommandLineOptions`, `CommandLineOptionsParser`,
  `FormatFileCallable`, `UsageException`) and the service interface for its IDE plugins (`FormatterService`,
  `FormatterServiceImpl`). jpm-format has its own CLI and language server.
- Not copied yet: upstream's GraalVM configuration (`META-INF/native-image`), needed once jpm-format builds a
  native binary.

It is built as upstream builds it: Immutables and derive4j run as annotation processors (`"dev"` dependencies in
`project.jsonc`), and javac's internal packages are opened with `--add-exports` (`"javacArgs"`).

Equivalence was checked by formatting 677 files (upstream's test data, jpm's and jpm-format's sources) in all three
styles with the 2.103.0 jar and with the fork: all 2,031 outputs are byte-identical. `PalantirTestdataTest` keeps
upstream's 226 formatter test cases (`test/assets/palantir/testdata`) as a regression suite.

Later changes are jpm-format's own, marked `jpm-format:` in the code; `git log -- src/dev/jpm/format/palantir` lists
them:

- **Free widths.** `JavaFormatterOptions` takes `maxLineLength`, `indent` and `continuationIndent` in place of the
  style's, and the visitors take the two indents instead of upstream's single multiplier (which meant blocks at 2x
  and continuation lines at 4x it). Each style's defaults are unchanged; formatting the corpus above in all three
  styles, and with `PALANTIR` plus each other style's widths, still matches 2.103.0 byte for byte.

Upstream fixes are picked up by hand: compare with the upstream commit above.
