plugins {
    id("unsafe-sanitizer.java-conventions")
    alias(libs.plugins.shadow)
}

val agentJar: Configuration by configurations.creating {
    // Prevent projects depending on this one from seeing and using this configuration
    isCanBeConsumed = false
    isVisible = false
    isTransitive = false
}

dependencies {
    implementation(libs.bytebuddy)
    implementation(libs.bytebuddy.agent)
    implementation(libs.errorprone.annotations)
    implementation(libs.jetbrains.annotations)

    // For convenience add `compileOnly` dependency so that code in this project can directly reference agent classes
    // However, they are not part of the public API, and accessing them will only work after agent has been installed
    compileOnly(project(":agent-impl"))
    testCompileOnly(project(":agent-impl"))
    agentJar(project(path = ":agent-impl", configuration = "shadow"))

    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.launcher)
}

// Embed the agent JAR as resource, see `marcono1234.unsafe_sanitizer.UnsafeSanitizer#addAgentToBootstrapClasspath`
// for details
val agentJarDir = layout.buildDirectory.dir("generated/agent-impl-jar").get().asFile
val copyAgentImplJar = tasks.register<Copy>("copyAgentImplJar") {
    from(agentJar) {
        // TODO: Trailing `_` is as workaround for https://github.com/johnrengelman/shadow/issues/111 for standalone agent JAR
        rename(".*", "agent-impl.jar_")
    }
    // Add package name prefix
    into(agentJarDir.resolve("marcono1234/unsafe_sanitizer"))
}
sourceSets.main.get().output.dir(mutableMapOf<String, Any>("builtBy" to copyAgentImplJar), agentJarDir)


// TODO: Maybe configure this using test suites instead, for consistency
val testJdk21 = tasks.register<Test>("testJdk21") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
tasks.check {
    dependsOn(testJdk21)
}


@Suppress("UnstableApiUsage") // for Test Suites
testing {
    suites {
        data class TestConfig(val javaVersion: Int, val agentArgs: String? = null)

        val testConfigs = arrayOf(
            TestConfig(17),
            TestConfig(21),
            TestConfig(21, "call-debug-logging=true,uninitialized-memory-tracking=false")
        )
        testConfigs.forEach { testConfig ->
            // Create integration test for using agent standalone by running with `-javaagent:...`

            var testName = "agentTestJdk${testConfig.javaVersion}";
            testConfig.agentArgs?.let { testName += "Args" }

            val agentTest by register(testName, JvmTestSuite::class) {
                useJUnitJupiter(libs.versions.junit)

                // TODO: This causes the warning "Duplicate content roots detected" in IntelliJ; can probably be ignored for now
                sources {
                    java {
                        setSrcDirs(listOf("src/agentTest/java"))
                    }
                }

                targets {
                    all {
                        testTask.configure {
                            // Run regular tests first
                            shouldRunAfter(tasks.test)

                            // Requires JAR with dependencies
                            dependsOn(tasks.shadowJar)

                            val agentJar = tasks.shadowJar.get().archiveFile
                            // Define the agent JAR as additional input to prevent Gradle from erroneously assuming
                            // this task is UP-TO-DATE or can be used FROM-CACHE despite the agent JAR having changed
                            inputs.file(agentJar)

                            // Evaluate the arguments lazily to make sure the `shadowJar` task has already been configured
                            jvmArgumentProviders.add {
                                val agentPath = agentJar.get().asFile.absolutePath;
                                val agentArgs = testConfig.agentArgs?.let { "=$it" } ?: ""
                                listOf("-javaagent:${agentPath}${agentArgs}")
                            }

                            javaLauncher.set(javaToolchains.launcherFor {
                                languageVersion.set(JavaLanguageVersion.of(testConfig.javaVersion))
                            })
                        }
                    }
                }
            }

            tasks.check {
                dependsOn(agentTest)
            }
        }
    }
}


// Create JAR with dependencies variant to allow standalone agent usage
tasks.shadowJar {
    isEnableRelocation = false
    duplicatesStrategy = DuplicatesStrategy.FAIL
    archiveClassifier = "standalone-agent"

    manifest {
        attributes(
            // See https://docs.oracle.com/en/java/javase/17/docs/api/java.instrument/java/lang/instrument/package-summary.html
            // section "Manifest Attributes"
            "Premain-Class" to "marcono1234.unsafe_sanitizer.AgentMain",
            "Can-Retransform-Classes" to "true",
            // Main class is used for printing usage help on command line
            "Main-Class" to "marcono1234.unsafe_sanitizer.AgentMain",

            // Mark as mutli-release due to multi-release dependencies, see also https://github.com/johnrengelman/shadow/issues/449
            // TODO: Currently does not work due to https://github.com/raphw/byte-buddy/issues/1724
            // "Multi-Release" to "true",
        )
    }

    // Exclude `module-info` from dependencies, see also https://github.com/johnrengelman/shadow/issues/729
    exclude("META-INF/versions/*/module-info.class")

    // Exclude duplicated Byte Buddy classes; Byte Buddy contains the same class files for Java 5 and Java 8, but since
    // this project here is using Java > 8 can omit the Java 5 classes, see also https://github.com/raphw/byte-buddy/pull/1719
    // TODO: Currently does not work due to https://github.com/raphw/byte-buddy/issues/1724
    // exclude("net/bytebuddy/**")
}
// Run shadow task by default
tasks.build {
    dependsOn(tasks.shadowJar)
}


java {
    // Publish sources and javadoc
    withSourcesJar()
    withJavadocJar()
}

// TODO: Maybe should use `agent` as artifactId (by changing rootProject.name?)
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
