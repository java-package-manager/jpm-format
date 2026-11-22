# 0003. Running on a JDK the layout engine can read

Status: accepted

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
  only: CDS refuses folders on the classpath.
- CI runs on JDK 21, where javac still has what the layout engine reads.
