---
"jpm-format": minor
---

The Palantir Java Format fork is gone (ADR 0006): "palantir" now runs Palantir Java Format 2.103.0 from its own jar, so formatter.lineWidth and the indentation settings no longer apply to it, and jpm-format no longer depends on Guava, Functional Java and Jackson.
