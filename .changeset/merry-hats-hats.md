---
"jpm-format": patch
---

The IntelliJ style's own layout engine (ADR 0006), not the default yet: with JPM_FORMAT_LAYOUT=intellij, jpm-format lays out Java code without the Palantir fork, closer to IntelliJ than the fork (905 lines differ on the parity corpus, against 980).
