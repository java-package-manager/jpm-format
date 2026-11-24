# AGENTS.md

Facts for AI agents working on jpm-format. Reasons are in [CONTRIBUTING.md](CONTRIBUTING.md) and
[docs/adr](docs/adr).

## Commands

| What | Command |
|---|---|
| Tests | `jpm test` |
| Build | `jpm build` |
| Self-check (formatting and lint of jpm-format's own code, among them: no `//` or `/* */` comments) | `jpm start -- --check` |
| Format files | `jpm start -- <files>` |
| Changeset (every change has one; `--minor`, `--major` or `--empty` instead of `--patch`) | `jpm changeset add --patch -m "<summary>"` |
| jpm's checks (among them: every change has a changeset), then the tests | `jpm check` |
| IntelliJ parity (lines that differ from IntelliJ's formatter; may only go down) | `jpm build --bundle && java checks/Parity.java --idea <IntelliJ home> [--show 10] [--update]` |

## Where things live

- `src/dev/jpm/format/Main.java`: the command line. `Engine.java`: the formatting pipeline. `Lsp.java`: the
  language server.
- `src/dev/jpm/format/intellij/`: `Layout`, the IntelliJ style's layout engine (ADR 0006), and the rules after it
  that adjust its output toward IntelliJ's code style. `src/dev/jpm/format/rules/`:
  lint rules. `src/dev/jpm/format/inspections/`: IntelliJ's default inspections, one nested class each, named by
  IntelliJ's short name; each has a case in `InspectionsTest` and its summary generates `docs/inspections.md`
  (`UPDATE_DOCS=1 jpm test`). `src/dev/jpm/format/api/`: the rule API for custom rules.
- `test/assets/intellij/layout`: unformatted code and IntelliJ's output for it, which `LayoutTest` runs.
- `ROADMAP.md`: what is not done yet; remove an item once it is done.

## Rules that must not be broken

- No `//` or `/* */` comments in the code (Javadoc is fine).
- `jpm start -- --check` passes.
- Do not push, tag, or file issues without being asked.
- Releases and prereleases are made only by jpm-changeset (CI's release job, `jpm changeset version` and
  `publish`): never change `"version"` in `project.jsonc`, edit `CHANGELOG.md` or create a tag by hand.

## Done means

`jpm check` and the self-check (which includes the comment lint) pass; a changeset in `.changeset/` describes the change;
`README.md` or `docs/rules.md` describe user-facing changes; the commit message says what changed and why.
