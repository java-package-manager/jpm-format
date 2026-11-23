# Contributing to jpm-format

jpm-format is the `jpm format` extension: a formatter and linter. [AGENTS.md](AGENTS.md) has the same facts
for AI agents; the plan is jpm's [ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md)
(Milestone 5: IntelliJ parity, then a language server).

## Build, test, run

It needs jpm 0.7.1 or newer and a JDK 21.

```sh
jpm test
jpm build
jpm start -- --check                          # jpm-format checks its own sources, as CI does
jpm start -- <files>                          # format files with this checkout
java checks/Comments.java check src test '!src/dev/jpm/format/palantir'   # the comment lint CI runs
```

**IntelliJ parity** is measured, not guessed: `checks/Parity.java` formats the sources jars listed in
`checks/parity/corpus.txt` (real projects from Maven Central) with IntelliJ IDEA's command-line formatter and its
default code style, and with jpm-format's formatter alone (no linter, imports untouched), and counts the lines
that differ. The count is a ratchet: `checks/parity/baseline.txt` may only go down.

```sh
jpm build --bundle                                        # the jar Parity runs
java checks/Parity.java --idea <IntelliJ home> --show 10  # the count, and the 10 files that differ most
java checks/Parity.java --idea <IntelliJ home> --update   # store a lower count
```

The IntelliJ home is the folder with `bin/format.sh` (or `IDEA_HOME`); IntelliJ's output is cached per build in
`.cache/parity/`. Work on parity by picking what differs most, changing the IntelliJ rules or the layout engine,
and lowering the baseline in the same commit.

## Rules for the code

- No `//` or `/* */` comments in jpm-format's own code
  ([docs/adr/0001](docs/adr/0001-decisions-live-in-adrs.md)); decisions go into [docs/adr](docs/adr).
- `src/dev/jpm/format/palantir/` is a fork of Palantir Java Format. Its files keep their Apache 2.0
  headers and comments; jpm-format's changes to it are marked `jpm-format:` and listed in
  [third_party/palantir-java-format/UPSTREAM.md](third_party/palantir-java-format/UPSTREAM.md).
- The jpm-format sources are formatted by jpm-format (`jpm start -- --check` passes).
- No stability before 1.0.

## Workflow and releases

- One local branch per change; tests and the self-check pass before it is merged into `main`; `main` is
  pushed once.
- Change `"version"` in `project.jsonc` and push to `main`: CI tags that commit with the version once the
  tests pass, and the tag is the release.
