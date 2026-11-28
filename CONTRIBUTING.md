# Contributing to jpm-format

jpm-format is the `jpm format` extension: a formatter and linter. [AGENTS.md](AGENTS.md) has the same facts
for AI agents; what is still to come is in [ROADMAP.md](ROADMAP.md), and jpm's own plan in its
[ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md).

## Build, test, run

It needs jpm 0.7.1 or newer and a JDK 21.

```sh
jpm test
jpm build
jpm start -- --check                          # jpm-format checks its own sources, as CI does, comments included
jpm start -- <files>                          # format files with this checkout
```

**IntelliJ parity** is measured, not guessed: `checks/Parity.java` formats the sources jars listed in
`checks/parity/corpus.txt` (real projects from Maven Central) with IntelliJ IDEA's command-line formatter and its
default code style, and with jpm-format's formatter alone (no linter, imports and modifiers untouched), and counts the lines
that differ. The count is a ratchet: `checks/parity/baseline.txt` may only go down.

```sh
jpm build --bundle                                        # the jar Parity runs
java checks/Parity.java --idea <IntelliJ home> --show 10  # the count, and the 10 files that differ most
java checks/Parity.java --idea <IntelliJ home> --update   # store a lower count
```

The IntelliJ home is the folder with `bin/format.sh` (or `IDEA_HOME`); IntelliJ's output is cached per build in
`.cache/parity/`. Work on parity by picking what differs most, changing the IntelliJ rules or the layout engine,
and lowering the baseline in the same commit.

The IntelliJ style's layout engine is `src/dev/jpm/format/intellij/Layout.java`, with `Tokens` and `Syntax` beside
it ([ADR 0006](docs/adr/0006-intellij-style-without-palantir.md)). Its cases in `test/assets/intellij/layout` are
unformatted code (`.input`) and what IntelliJ's `format.sh -allowDefaults` makes of it (`.output`); `LayoutTest`
runs them.

## Rules for the code

- No `//` or `/* */` comments in the code
  ([docs/adr/0001](docs/adr/0001-decisions-live-in-adrs.md)); decisions go into [docs/adr](docs/adr).
- The jpm-format sources are formatted by jpm-format (`jpm start -- --check` passes).
- No stability before 1.0.

## Workflow and releases

- One local branch per change; tests and the self-check pass before it is merged into `main`; `main` is
  pushed once.
- CI runs the full check on every commit to `main` and on every pull request (a pull request's branch is not
  checked a second time as a push).
- Releases are made with [jpm-changeset](https://github.com/java-package-manager/jpm-changeset), and only with
  it: never change `"version"` in `project.jsonc` by hand, and never create a tag. A release is a tag, which
  `jpm add -g github:java-package-manager/jpm-format` resolves.
- Every change comes with a changeset in `.changeset/`, committed with it; `jpm check` (CI) fails without one.

  ```sh
  jpm changeset add --patch -m "Keep blank lines in switch blocks."   # a fix; --minor for a feature
  jpm changeset add --minor -m "..."                                  # below 1.0.0, --major raises the minor version
  jpm changeset add --empty -m "..."                                  # a change that needs no release (CI, docs)
  jpm changeset status                                                # what is pending, and the version it gives
  ```
- On `main`, CI ([changeset-action](https://github.com/java-package-manager/changeset-action)) keeps a
  "Version packages" pull request with the bumped version and `CHANGELOG.md`. Merging it releases: CI tags the
  commit with the version (`jpm changeset publish`), pushes the tag and creates a GitHub release with the
  changelog entry. Nothing is published to a Maven repository.
- Prereleases: `jpm changeset pre enter beta` (committed with its `.changeset/pre.json`) makes the version pull
  request release `0.4.0-beta.0`, `0.4.0-beta.1`, …; `jpm changeset pre exit` makes the next one stable.
