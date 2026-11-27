# 0007. Formatted code stays as IntelliJ has it; broken formatting may be fixed further

Status: accepted

## Decision

- Code that is already formatted comes out of the IntelliJ style exactly as IntelliJ IDEA's formatter leaves it.
  The parity corpus (`checks/Parity.java`) is formatted code, so it checks this.
- Where IntelliJ leaves formatting that is plainly broken, and no formatted code is written that way, jpm-format
  fixes it although IntelliJ does not. The first: a line break between the modifiers, keyword or type and name
  of a class, method or variable (`record` on one line and its name on the next) is joined.
- Not fixed, because formatted code is written that way too and IntelliJ keeps it: a wrapped `extends`,
  `implements` or `throws`, the blank lines between a module's directives, and the code in a Javadoc
  `<pre>` example.

## Why

jpm-format is meant for projects whose code IntelliJ users also format, so formatting the same code must not
move it back and forth between the two. Formatting that no one writes on purpose has no such conflict, and
fixing it is what a formatter is for.
