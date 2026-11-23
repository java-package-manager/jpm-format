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
- [ ] **Spotless formatters, natively.** `formatter.style: "eclipse"` runs Spotless's Eclipse JDT step, and
      `"files"` runs Spotless's steps on files other than Java (ktfmt, ktlint, scalafmt, JSON, YAML, SQL and the
      generic text steps), with jpm providing the jars. Still to come: the steps that need npm or a native binary
      (Prettier, ESLint, Biome, clang-format, gofmt, shfmt, buf, black), and Flexmark, SortPom and Gherkin, whose
      configs have many options.
- [ ] **Java formatters from Git.** `"formatter": { "style": "..." }` takes `palantir@<version>`,
      `google-java-format[@<version>]`, the URL of a jar and `github:author/my-java-formatter[@version|#branch]` (a
      jpm project jpm builds, with Palantir's or google-java-format's `Formatter`, or a jpm-format rule set). Still to
      come: a `#branch` of Palantir or google-java-format themselves, which build with Gradle and Maven, not jpm.
- [ ] **No Palantir fork.** Use Palantir Java Format from its own repository instead of the fork in
      `src/dev/jpm/format/palantir/`. `formatter.style: "palantir@<version>"` already runs upstream's releases as
      they are. The fork stays for the IntelliJ style: 46 changes of its own (`jpm-format:` in the code), most of
      them the `keepLineBreaks` mode, that upstream would have to take first.

## Running

- [ ] **Native binary.** jpm starts extensions on a JVM today; a GraalVM build (`jpm build --native`) needs testing
      first, since running javac inside a native image is uncommon.

## Elsewhere

- [ ] `jpm import` translates Spotless config (in jpm).
- [ ] The IntelliJ plugin runs `jpm format` on save (in jpm-intellij).
