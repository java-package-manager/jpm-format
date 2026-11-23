# Roadmap

What jpm-format does not do yet. jpm's own plan is in its
[ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md) (Milestone 5: IntelliJ parity,
then a language server).

## Top priority

- [ ] **Drop the other copies of `checks/Comments.java`.** Once a jpm-format release has `NonJavadocComment`,
      jpm-changeset and jpm turn it on (`"linter": { "rules": { "style": { "NonJavadocComment": "error" } } }`)
      and remove `checks/Comments.java`, `checks/comments-baseline.txt` and the CI step that runs it.

## Formatting

- [ ] **IntelliJ parity.** The rest of IntelliJ's default Javadoc formatting, and IntelliJ's code style options,
      as rules in `src/dev/jpm/format/intellij/`, measured against IntelliJ's own formatter on real projects
      (`checks/Parity.java`, see [CONTRIBUTING.md](CONTRIBUTING.md)). Of IntelliJ's default inspections, null checks
      and data flow are left.
- [ ] **Spotless formatters, natively.** Run Spotless's formatter steps as jpm-format formatters, configured in
      `extensions.format`.
- [ ] **Well-known Java formatters by name.** `"formatter": { "style": "<name or URL>" }` picks the formatter; a URL
      is a jar release of it:
  - `palantir[@version|#branch]`: Palantir Java Format, without having to give its URL;
  - `google-java-format[@version|#branch]`: the same for google-java-format;
  - `github:author/my-java-formatter[@version|#branch]`: any formatter from a Git repository, as a jpm-format
    extension (the rule API) or as a Spotless step.

  `palantir` and `google-java-format` are only shorthands, so that choosing a style is easy.
- [ ] **No Palantir fork.** Use Palantir Java Format from its own repository instead of the fork in
      `src/dev/jpm/format/palantir/`. Needs native Spotless support first.

## Running

- [ ] **Native binary.** jpm starts extensions on a JVM today; a GraalVM build (`jpm build --native`) needs testing
      first, since running javac inside a native image is uncommon.

## Elsewhere

- [ ] `jpm import` translates Spotless config (in jpm).
- [ ] The IntelliJ plugin runs `jpm format` on save (in jpm-intellij).
