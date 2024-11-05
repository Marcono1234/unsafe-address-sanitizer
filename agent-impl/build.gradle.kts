plugins {
    id("unsafe-sanitizer.java-conventions")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(libs.jetbrains.annotations)

    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    // Relocate all dependencies to not cause conflicts when the agent JAR is added to the bootstrap classpath
    isEnableRelocation = true
    relocationPrefix = "marcono1234.unsafe_sanitizer.agent_impl.deps"
    duplicatesStrategy = DuplicatesStrategy.FAIL

    // Include own `module-info.class`, see https://github.com/GradleUp/shadow/issues/710
    excludes.remove("module-info.class")

    // Exclude `module-info` from dependencies, see also https://github.com/GradleUp/shadow/issues/729
    exclude("META-INF/versions/*/module-info.class")

    // Note: Depending on the dependencies, might have to set `Multi-Release: true`, see https://github.com/GradleUp/shadow/issues/449
}


java {
    // Publish only sources to allow debugging; don't publish Javadoc because this is not public API
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // TODO: Maybe revert the following and only publish sources instead (`artifact(tasks["sourcesJar"])`)?
            // Would not actually be necessary to publish the JAR since it is included inside the agent,
            // and users are not expected to have direct dependency on it; but publish it anyway to allow
            // debugging through the code
            from(components["java"])
        }
    }
}
