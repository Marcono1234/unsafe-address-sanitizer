import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

// Conventions plugin to share common configuration for all sub-projects,
// see https://docs.gradle.org/8.5/samples/sample_convention_plugins.html

plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

group = "marcono1234.unsafe_sanitizer"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Additionally set desired release version to allow building with newer JDK but still targeting older Java version
tasks.compileJava {
    options.release = 17
}

// TODO: Maybe configure this using test suites instead, for consistency
tasks.withType<Test> {
    useJUnitPlatform()

    testLogging {
        events = setOf(TestLogEvent.SKIPPED, TestLogEvent.FAILED)

        showExceptions = true
        showStackTraces = true
        showCauses = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}
