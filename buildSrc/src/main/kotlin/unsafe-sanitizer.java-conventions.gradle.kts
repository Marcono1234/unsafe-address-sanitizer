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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    testLogging {
        events = setOf(TestLogEvent.SKIPPED, TestLogEvent.FAILED)

        showExceptions = true
        showStackTraces = true
        showCauses = true
        exceptionFormat = TestExceptionFormat.FULL

        // TODO: Simplify once https://github.com/gradle/gradle/issues/5431 is fixed
        afterSuite(KotlinClosure2({ descriptor: TestDescriptor, result: TestResult ->
            // Only handle root test suite
            if (descriptor.parent == null) {
                logger.lifecycle("${result.testCount} tests (${result.successfulTestCount} successful, ${result.skippedTestCount} skipped, ${result.failedTestCount} failed)")
            }
        }))
    }
}
