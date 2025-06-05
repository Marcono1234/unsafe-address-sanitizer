# Jazzer fuzzing test

Runs a test with the fuzzing library [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer), and the sanitizer agent JAR installed at JVM startup using the JVM option `-javaagent`.
Jazzer is run in 'fuzzing mode', performing actual fuzzing.

See the [root `build.gradle.kts`](/build.gradle.kts) for the configuration of the test.

