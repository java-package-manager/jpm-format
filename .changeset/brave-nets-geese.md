---
"jpm-format": minor
---

New lint rule NonJavadocComment, off by default: reports `//` and `/* */` comments and leaves Javadoc, suppression comments and comment markers in literals alone; its fix removes the comment (offered, not applied)
