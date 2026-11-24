---
"jpm-format": patch
---

The default IntelliJ style starts on any JDK, without a second JVM: only Palantir Java Format and google-java-format need javac's internals exported and JDK 21, so only a formatter.style other than "intellij" asks for them.
