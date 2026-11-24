# 0003. Running on a JDK the layout engine can read

Status: accepted. Since [0006](0006-intellij-style-without-palantir.md) only the external formatters (Palantir
Java Format, google-java-format) read javac's internals; the IntelliJ style's engine uses its public API. `Main`
exports javac's internals and moves to a supported JDK only when the config picks another `formatter.style`
(`Config.otherStyle`).

## Decision

- The layout engine reads javac's internal syntax tree. When jpm starts jpm-format on a JDK whose javac
  it cannot read, `Main` relaunches the command on a supported JDK (`Jdks.supportedJava()`); jpm may
  install that JDK and shows the download.
- javac's internal packages are not exported. Before Java 24, where it causes no warning, `Exports` opens
  them in the running JVM, which saves a second start and lets tests run under any launcher; on 24 and
  newer, `Main` relaunches the JVM with `--add-exports`. `JPM_FORMAT_RELAUNCH=1` forces the relaunch
  path, to test it.
- A native image cannot load a project's custom rules, so such a command runs on a JVM instead.
- Relaunched JVMs use class data sharing from the second start onwards, when the classpath holds jars
  only: CDS refuses folders on the classpath. Their CDS log is off (`-Xlog:cds*=off`): the JVM logs
  warnings while it writes the archive, and from JDK 25 an error on every start because the JDK's base
  archive was written without `--add-exports`. The archive still works, so the log only adds noise.
- CI runs on JDK 21, where javac still has what the layout engine reads.
