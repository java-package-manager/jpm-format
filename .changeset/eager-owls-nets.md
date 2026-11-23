---
"jpm-format": patch
---

IOStreamConstructor leaves a stream of a FileDescriptor (`new FileOutputStream(FileDescriptor.out)`) alone: `Files` cannot open one
