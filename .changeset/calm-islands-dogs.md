---
"jpm-format": patch
---

Type-aware rules attribute the files of a module together, in up to 512 files per javac task, instead of each file on its own; javac stops after attribution, so a crash in its flow analysis no longer drops a file's types. On a project of 1,664 files, `jpm format --check` takes 7.6 s instead of 292 s, and 1.6 GB of heap instead of 8 GB. LoggingPlaceholderCountMatchesArgumentCount counts a multi-catch parameter as an exception, and IntegerMultiplicationImplicitCastToLong no longer reports the distance of a shift.
