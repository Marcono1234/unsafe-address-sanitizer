# Jazzer regression test

Runs a test with the fuzzing library [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer), and the sanitizer agent JAR installed at JVM startup using the JVM option `-javaagent`.
Jazzer is run in 'regression mode', using fuzzing inputs which have been generated before.

See the [root `build.gradle.kts`](/build.gradle.kts) for the configuration of the test.

## Jazzer test inputs

The [`src/test/resources`](src/test/resources) directory contains inputs used by Jazzer for performing the regression testing, see also the [Jazzer documentation](https://github.com/CodeIntelligenceTesting/jazzer?tab=readme-ov-file#junit-5).

For the `..._error` test methods the `crash` file should lead to invalid memory access which causes an expected test
failure. For the `..._success` test methods the `no-crash` file is the same as the `crash` one for the corresponding
`..._error` test method, but it should not cause a test failure because the test method does not perform invalid memory
access.
