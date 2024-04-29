---
name: Bug report
about: Report an Unsafe Sanitizer bug.
title: ''
labels: bug
assignees: ''

---

### Unsafe Sanitizer version
<!-- Version you are using, respectively Git commit hash, e.g. `ab12cd34` -->


### Agent settings

Agent installation:
- [ ] At JVM start (`-javaagent`)
- [ ] At runtime (`UnsafeSanitizer.installAgent(...)`)

Settings:
- instrumentation-logging: true | false
- global-native-memory-sanitizer: true | false
- uninitialized-memory-tracking: true | false
- error-action: none | throw | print | print-skip
- call-debug-logging: true | false


### Java version
<!-- Full output of `java -version` -->


### Description
<!-- Describe the bug you experienced -->


### Expected behavior
<!-- What behavior did you expect? -->


### Actual behavior
<!-- What happened instead? -->


### Example code
<!-- Provide a small self-contained code example for reproducing the bug -->
<!-- Add comments in the code to indicate where an unexpected error was thrown / where an expected error was missing -->
```java
...
```
