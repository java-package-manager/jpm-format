# jpm-format

## 0.4.0

### Minor Changes

- New lint rule NonJavadocComment, off by default: reports `//` and `/* */` comments and leaves Javadoc, suppression comments and comment markers in literals alone; its fix removes the comment (offered, not applied)

### Patch Changes

- IOStreamConstructor leaves a stream of a FileDescriptor (`new FileOutputStream(FileDescriptor.out)`) alone: `Files` cannot open one

## 0.3.1

### Patch Changes

- The second JVM that jpm-format starts no longer prints class data sharing warnings and errors (from JDK 25, an error on every run)
