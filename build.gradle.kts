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
        rename(".*", "agent-impl.jar")
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
        fun Test.configureSanitizerAgentForJvmStartup(agentArgs: String? = null) {
            // Requires JAR with dependencies
            dependsOn(tasks.shadowJar)

            val agentJar = tasks.shadowJar.get().archiveFile
            // Define the agent JAR as additional input to prevent Gradle from erroneously assuming
            // this task is UP-TO-DATE or can be used FROM-CACHE despite the agent JAR having changed
            inputs.file(agentJar)

            // Evaluate the arguments lazily to make sure the `shadowJar` task has already been configured
            jvmArgumentProviders.add {
                val agentPath = agentJar.get().asFile.absolutePath
                val agentArgsSuffix = agentArgs?.let { "=$it" } ?: ""
                listOf("-javaagent:${agentPath}${agentArgsSuffix}")
            }
        }

        data class AgentTestConfig(val javaVersion: Int, val agentArgs: String? = null)

        val agentTestConfigs = arrayOf(
            AgentTestConfig(17),
            AgentTestConfig(21),
            AgentTestConfig(21, "call-debug-logging=true,uninitialized-memory-tracking=false")
        )
        agentTestConfigs.forEach { testConfig ->
            // Create integration test for using agent standalone by running with `-javaagent:...`

            var testName = "agentTestJdk${testConfig.javaVersion}"
            testConfig.agentArgs?.let { testName += "Args" }

            val agentTest by register(testName, JvmTestSuite::class) {
                useJUnitJupiter(libs.versions.junit)

                // TODO: This causes the warning "Duplicate content roots detected" in IntelliJ because all the
                //   agent tests (with different configs) use the same source dir; can probably be ignored for now
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

                            configureSanitizerAgentForJvmStartup(testConfig.agentArgs)

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

        // Runs Jazzer in 'fuzzing mode'
        val jazzerFuzzingTest = register("jazzerFuzzingTest", JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)

            val jazzerTestDir = "src/jazzerFuzzingTest"
            sources {
                // Use nested `src/test/...` here because Jazzer expects resources under `src/test/resources`
                val parentDir = "${jazzerTestDir}/src/test"
                java {
                    setSrcDirs(listOf("${parentDir}/java"))
                }
                resources {
                    setSrcDirs(listOf("${parentDir}/resources"))
                }
            }

            dependencies {
                implementation(libs.jazzer.junit)
            }

            targets {
                all {
                    testTask.configure {
                        // Run regular tests first
                        shouldRunAfter(tasks.test)

                        // Set custom working dir because Jazzer expects resources under `src/test/resources`
                        workingDir = project.projectDir.resolve(jazzerTestDir)

                        environment(
                            // Enable Jazzer fuzzing mode
                            "JAZZER_FUZZ" to "1"
                        )

                        // Note: This setup assumes that the sanitizer will see all allocations made by Jazzer;
                        // otherwise this could lead to spurious sanitizer errors, for example when the native
                        // library used by Jazzer performs allocations and Jazzer then accesses that memory using Unsafe
                        configureSanitizerAgentForJvmStartup()
                    }
                }
            }
        }
        tasks.check {
            dependsOn(jazzerFuzzingTest)
        }

        // TODO: Maybe add a Jazzer test variant which installs sanitizer at runtime?

        // Runs Jazzer in 'regression mode'
        val jazzerRegressionTest = register("jazzerRegressionTest", JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)

            val jazzerTestDir = "src/jazzerRegressionTest"
            sources {
                // Use nested `src/test/...` here because Jazzer expects resources under `src/test/resources`
                val parentDir = "${jazzerTestDir}/src/test"
                java {
                    setSrcDirs(listOf("${parentDir}/java"))
                }
                resources {
                    setSrcDirs(listOf("${parentDir}/resources"))
                }
            }

            dependencies {
                // Use JUnit Platform Test Kit to manually run tests and assert their results, including expected
                // failures, see https://junit.org/junit5/docs/current/user-guide/#testkit
                implementation(libs.junit.testkit)

                implementation(libs.jazzer.junit)
            }

            targets {
                all {
                    testTask.configure {
                        // Run regular tests first
                        shouldRunAfter(tasks.test)

                        // Set custom working dir because Jazzer expects resources under `src/test/resources`
                        workingDir = project.projectDir.resolve(jazzerTestDir)

                        filter {
                            // Exclude actual test implementation because it is run manually, see code in `JazzerRegressionTest`
                            excludeTest("JazzerRegressionTest\$JazzerRegressionTestImpl", null)
                        }

                        // Note: This setup assumes that the sanitizer will see all allocations made by Jazzer;
                        // otherwise this could lead to spurious sanitizer errors, for example when the native
                        // library used by Jazzer performs allocations and Jazzer then accesses that memory using Unsafe
                        configureSanitizerAgentForJvmStartup()
                    }
                }
            }
        }
        tasks.check {
            dependsOn(jazzerRegressionTest)
        }
    }
}


// Create JAR with dependencies variant to allow standalone agent usage
tasks.shadowJar {
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
        )
    }

    // Exclude duplicated Byte Buddy classes; Byte Buddy contains the same class files for Java 5 and Java 8, but since
    // this project here is using Java > 8 can omit the Java 5 classes, see also https://github.com/raphw/byte-buddy/pull/1719
    exclude("net/bytebuddy/**")
    // Exclude conflicting license files from dependencies
    exclude("META-INF/LICENSE", "META-INF/NOTICE")
}
// Run shadow task by default
tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.javadoc {
    options {
        // Cast to standard doclet options, see https://github.com/gradle/gradle/issues/7038#issuecomment-448294937
        this as StandardJavadocDocletOptions

        encoding = "UTF-8"
        // Enable doclint, but ignore warnings for missing tags, see
        // https://docs.oracle.com/en/java/javase/17/docs/specs/man/javadoc.html#additional-options-provided-by-the-standard-doclet
        // The Gradle option methods are rather misleading, but a boolean `true` value just makes sure the flag
        // is passed to javadoc, see https://github.com/gradle/gradle/issues/2354
        // TODO: Maybe in the future suppress the lint selectively for the affected elements, see https://bugs.openjdk.org/browse/JDK-8274926
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Werror", true)
    }
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
