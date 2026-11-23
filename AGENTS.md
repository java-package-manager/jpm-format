# AGENTS.md

Facts for AI agents working on jpm-format. Reasons are in [CONTRIBUTING.md](CONTRIBUTING.md) and
[docs/adr](docs/adr).

## Commands

| What | Command |
|---|---|
| Tests | `jpm test` |
| Build | `jpm build` |
| Self-check (formatting and lint of jpm-format's own code) | `jpm start -- --check` |
| Format files | `jpm start -- <files>` |
| Comment lint | `java checks/Comments.java check src test '!src/dev/jpm/format/palantir'` |
| IntelliJ parity (lines that differ from IntelliJ's formatter; may only go down) | `jpm build --bundle && java checks/Parity.java --idea <IntelliJ home> [--show 10] [--update]` |

## Where things live

- `src/dev/jpm/format/Main.java`: the command line. `Engine.java`: the formatting pipeline. `Lsp.java`: the
  language server.
- `src/dev/jpm/format/palantir/`: the layout engine, a fork of Palantir Java Format.
- `src/dev/jpm/format/intellij/`: adjustments toward IntelliJ's code style. `src/dev/jpm/format/rules/`:
  lint rules. `src/dev/jpm/format/api/`: the rule API for custom rules.
- `test/assets/palantir/testdata`: upstream's formatter tests, kept as a regression suite.

## Rules that must not be broken

- No `//` or `/* */` comments outside `src/dev/jpm/format/palantir/`. Never remove the copyright headers or
  `jpm-format:` markers inside it.
- `jpm start -- --check` passes.
- Do not push, tag, or file issues without being asked.

## Done means

`jpm test`, the self-check and the comment lint pass; `README.md` or `docs/rules.md` describe user-facing
changes; the commit message says what changed and why.
