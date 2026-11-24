# 0002. The layout engine first, then IntelliJ's rules

Status: accepted. Since [0006](0006-intellij-style-without-palantir.md) the layout engine is `Layout`, and
`"palantir"` is Palantir's own output, without jpm-format's widths.

## Decision

- Formatting is a pipeline. The layout engine (the Palantir fork) runs first, then the adjustments toward
  IntelliJ's code style, then the other formatting rules (`Builtins`). The IntelliJ rules are on with the
  layout engine, unless the style is `"palantir"` (`Config`).
- Palantir's style runs with jpm-format's widths. Its GOOGLE and AOSP styles differ only in these
  (`JavaFormat`). IntelliJ's continuation indent is 8 with its 4-space indent.
- IntelliJ's "keep maximum blank lines" (in code, in declarations and before `}`) is at least 1: the
  layout engine keeps one blank line wherever the code had any. Blank lines inside an expression that the
  layout engine puts on one line are not put back. `KeepBlankLines` gives up, keeping the engine's output,
  when the texts no longer line up or an earlier rule changed more than whitespace.
- A record's components are fields in its header, not members of its body (`MemberBlankLines`). An
  anonymous class keeps `{}`; `{}` that is not written as `{}` (a comment inside, or already split) is
  left alone (`EmptyBodies`).
- In check mode, the reference is what `jpm format` makes of the file. So what `jpm format` produces
  passes `--check`, even where a formatter on its own would change it back, and the IntelliJ rules never
  report what the layout engine would undo (`Engine`).
- A difference is reported once: by the first formatter in pipeline order that touched it (for
  unformatted code, the layout engine). Formatters that make one change together are reported together.
  When nothing changes, there is nothing to report. Debug mode also checks that formatting is idempotent.
