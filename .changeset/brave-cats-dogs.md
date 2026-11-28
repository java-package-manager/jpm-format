---
"jpm-format": patch
---

The IntelliJ style moves a `//` comment after a method's or constructor's annotations to a line of its own where IntelliJ does: always after the last annotation when the modifiers are only annotations (one indent further in without a Javadoc comment), and after any other annotation of a method when the line would end past `java.lineWidth`. On Paper, the lines that differ from IntelliJ go from 238 to 106.
