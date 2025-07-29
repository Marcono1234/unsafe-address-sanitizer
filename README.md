# Java `Unsafe` address sanitizer

Java Agent which validates memory access performed using `sun.misc.Unsafe`. `Unsafe` is a semi-public JDK class
which allows among others allocating native memory and directly accessing memory without bounds checks. It is
sometimes used by libraries for better performance.

The issue with `Unsafe` is that it does not detect out-of-bounds reads and writes and performs little to no argument
validation. Therefore invalid arguments can break the correctness of an application or even represent a security
vulnerability.

Note that the memory access methods of `Unsafe` have been deprecated for JDK 23 and will be removed in a future JDK
version, see [JEP 471](https://openjdk.org/jeps/471). Libraries targeting newer Java versions should prefer
[`java.lang.foreign.MemorySegment`](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/MemorySegment.html),
which is a safer alternative to `Unsafe`.

### Why this sanitizer?

Invalid `Unsafe` arguments can be noticeable when the JVM crashes due to `SIGSEGV` respectively
`EXCEPTION_ACCESS_VIOLATION`. However, if a unit test or fuzzer runs in the same JVM, then it can most likely not
properly report the failure in case of a JVM crash.\
And even if no JVM crash occurs, `Unsafe` might have performed out-of-bounds access in the memory of the Java
process. Out-of-bounds reads can lead to non-deterministic behavior due to reading arbitrary data, or it could
leak sensitive information from other parts of the memory. Out-of-bounds writes can corrupt data, which can
lead to incorrect behavior or crashes at completely unrelated code locations later on.

This sanitizer injects validation checks into the `Unsafe` methods, throwing errors when invalid arguments
are provided. The following is detected:

- Arrays:
  - Out-of-bounds reads and writes
  - Bad aligned access (e.g. reading in the middle of a `long` element of a `long[]`)
  - Unaligned primitive access (e.g. reading a `long` from a `byte[]`, but the offset is not aligned for `long`)
- Fields:
  - No field at the specified offset
  - Out-of-bounds reads and writes
- Native memory:
  - Out-of-bounds reads and writes ([CWE-125](https://cwe.mitre.org/data/definitions/125.html), [CWE-787](https://cwe.mitre.org/data/definitions/787.html))
  - Reading uninitialized memory ([EXP33-C](https://wiki.sei.cmu.edu/confluence/display/c/EXP33-C.+Do+not+read+uninitialized+memory))
  - Double free ([CWE-415](https://cwe.mitre.org/data/definitions/415.html))
  - Unaligned primitive access (e.g. reading a `long` but the address is not aligned for `long`)

Some of these checks can be disabled, see the [Usage section](#usage). For example if an application intentionally
only supports platforms where unaligned access is possible, the address alignment check can be disabled.

> [!WARNING]\
> This library is experimental and only intended for testing and fuzzing. Do not use it in production, especially do
> not rely on it as security measure in production.

## How does it work?

This project is implemented as Java Agent which uses [instrumentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.instrument/java/lang/instrument/package-summary.html)
(more specifically the [Byte Buddy](https://github.com/raphw/byte-buddy) library) to instrument `sun.misc.Unsafe` and
related classes. The `Unsafe` methods are transformed so that all calls are intercepted to first check if the memory
access is valid, handling invalid access depending on the `ErrorAction` configured for the sanitizer. This allows the
sanitizer to react to invalid memory access before it occurs, and before the JVM might crash.

### Limitations

- Only usage of the 'public' `sun.misc.Unsafe` is checked, usage of the JDK-internal `jdk.internal.misc.Unsafe` is not
  checked\
  This is normally not an issue because third-party code does not (and in recent JDK versions cannot) access the
  JDK-internal `Unsafe` class.
- Not all invalid memory access might be detected\
  For example, if there is a dangling pointer but in the meantime another part of the application coincidentally
  allocates memory at that address, access with the originally dangling pointer would be considered valid. Similarly,
  if out-of-bounds access coincidentally happens to access another unrelated allocated memory section, it would be
  considered valid as well.
- Sanitizer is unaware of allocations which occurred before it was installed, and memory which is allocated or freed
  through other means than `Unsafe` or `ByteBuffer#allocateDirect`\
  If that allocated memory is accessed afterwards, the sanitizer will consider it invalid access. There are multiple
  ways to work around this, such as:
   - Installing the agent when the JVM starts, instead of at runtime
   - Disabling native memory access sanitization (`AgentSettings.withGlobalNativeMemorySanitizer(false)`), and
     optionally instead using `UnsafeSanitizer#withScopedNativeMemoryTracking`
   - Manually registering the allocated memory with `UnsafeSanitizer#registerAllocatedMemory`
- This library has mainly been written for the HotSpot JVM\
  It might not work for other JVMs, but bug reports for this are appreciated!

## Usage

> [!NOTE]
> This library is currently not published to Maven Central. You have to build it locally, see the [Building](#building)
> section.

(requires Java 17 or newer)

The sanitizer Java agent has to be installed once to become active. It can either be installed at runtime by calling
`UnsafeSanitizer.installAgent(...)`, or when the JVM is started by adding `-javaagent` to the arguments:
```
java -javaagent:unsafe-address-sanitizer-standalone-agent.jar -jar my-application.jar
```

Using `-javaagent` should be preferred, if possible, because `UnsafeSanitizer.installAgent(...)` might not be supported
by all JVMs and future JDK versions, and it might miss allocations which occurred before the sanitizer was installed,
which could lead to spurious invalid memory access errors.

When using `-javaagent`, invalid memory access will cause an error by default. The behavior can be customized; to view
all possible options and examples, start the agent as regular JAR (without any additional arguments):
```
java -jar unsafe-address-sanitizer-standalone-agent.jar
```

### Usage with Jazzer

This sanitizer can be used in combination with the Java fuzzing library [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer),
especially its [JUnit 5 integration](https://github.com/CodeIntelligenceTesting/jazzer?tab=readme-ov-file#junit-5).

When installing the Unsafe Sanitizer at runtime using `UnsafeSanitizer.installAgent(...)`, it should be called in a
`static { ... }` block in the test class, to only call it once and not for every executed test method.

Jazzer itself internally uses `sun.misc.Unsafe`. If the Unsafe Sanitizer agent is installed at runtime it might
therefore be necessary to disable sanitization of native memory by using `AgentSettings.withGlobalNativeMemorySanitizer(false)`.\
If the Unsafe Sanitizer agent has been installed using `-javaagent` this might not be a problem. However, the
sanitizer might nonetheless decrease the Jazzer performance. So unless needed, it might be useful to disable native
memory sanitization.

## Building

This project uses Gradle for building. JDK 17 is recommended, but Gradle toolchains are used, so any newer JDK version
should work as well because the JDK versions needed by the build are downloaded by Gradle automatically.

```
./gradlew build
```

This generates the file `build/libs/unsafe-address-sanitizer-<version>-standalone-agent.jar` which you can use with the
`-javaagent` JVM argument. Or you can add it as JAR dependency to your project and then install the agent at runtime.

You can use `./gradlew publishToMavenLocal` to [add the library to your local Maven repository](https://docs.gradle.org/current/userguide/publishing_maven.html#publishing_maven:install).
The artifact coordinates are `marcono1234.unsafe_sanitizer:unsafe-address-sanitizer:<version>`.

## Similar third-party projects

- Project https://github.com/serkan-ozal/mysafe \
  Offers more functionality for native memory access tracking, but does not validate array and field access.
- Paper: "Use at your own risk: the Java unsafe API in the wild"\
  Authors: Luis Mastrangelo, Luca Ponzanelli, Andrea Mocci, Michele Lanza, Matthias Hauswirth, Nathaniel Nystrom\
  DOI: [10.1145/2858965.2814313](https://doi.org/10.1145/2858965.2814313)
- Paper: "SafeCheck: safety enhancement of Java unsafe API"\
  Authors: Shiyou Huang, Jianmei Guo, Sanhong Li, Xiang Li, Yumin Qi, Kingsum Chow, Jeff Huang\
  DOI: [10.1109/ICSE.2019.00095](https://doi.org/10.1109/ICSE.2019.00095)
