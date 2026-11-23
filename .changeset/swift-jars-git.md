---
"jpm-format": minor
---

`formatter.style` takes a formatter that is not on a Maven repository: the URL of a jar (with `#sha256=` to pin it), or a Git repository that jpm builds (`github:author/formatter[@<version>|#<branch>]`), holding Palantir's or google-java-format's `Formatter` or a jpm-format rule set with formatters
