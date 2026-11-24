# Architecture decision records

Why jpm-format's code is the way it is, where that is not obvious from the code. jpm-format's own sources
have no comments (see [0001](0001-decisions-live-in-adrs.md)).

| ADR | Decision |
|---|---|
| [0001](0001-decisions-live-in-adrs.md) | Decisions live in ADRs; the forked layout engine keeps its comments |
| [0002](0002-formatting-pipeline.md) | The layout engine first, then IntelliJ's rules |
| [0003](0003-running-on-a-supported-jdk.md) | Running on a JDK the layout engine can read |
| [0004](0004-configuration-suppressions-cache.md) | Configuration, suppressions and the cache |
| [0005](0005-rules.md) | Decisions in individual rules |
| [0006](0006-intellij-style-without-palantir.md) | The IntelliJ style gets its own layout engine; Palantir leaves the codebase |
