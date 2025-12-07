package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.UnsafeInterceptors.*;
import marcono1234.unsafe_sanitizer.agent_impl.UnsafeSanitizerImpl;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;
import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.Nullable;
import sun.misc.Unsafe;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.instrument.Instrumentation;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarFile;

import static marcono1234.unsafe_sanitizer.agent_impl.DirectByteBufferHelper.DEALLOCATOR_CLASS_NAME;
import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Class for installing and configuring the {@link Unsafe} sanitizer agent.
 *
 * <p>The agent can be installed either at runtime using one of the {@link #installAgent(AgentSettings)} methods
 * or when starting the JVM by using {@code -javaagent}:
 * <pre>
 * java -javaagent:unsafe-address-sanitizer-standalone-agent.jar -jar my-application.jar
 * </pre>
 *
 * <p>When installed using {@code -javaagent}, by default the sanitizer will use {@link ErrorAction#THROW}.
 * Custom agent settings can be specified; to view all possible options and examples, start the agent as regular JAR
 * (without any additional arguments):
 * <pre>
 * java -jar unsafe-address-sanitizer-standalone-agent.jar
 * </pre>
 *
 * <p>Once installed subsequent {@code Unsafe} usage will be checked and bad memory access will be reported.
 * Which cases of bad memory access will be detected and how they are reported depends on the {@link AgentSettings}.
 *
 * <p>Most methods of this class assume that the sanitizer agent has already been installed, otherwise an
 * {@link IllegalStateException} will be thrown.
 */
public class UnsafeSanitizer {
    private UnsafeSanitizer() {
    }

    /**
     * Unsafe Sanitizer agent settings. Use {@link #defaultSettings()} for defaults, or customize settings
     * by calling the constructor or the {@code withX} methods. Some of the settings can also be changed
     * afterwards at runtime using methods of {@link UnsafeSanitizer}.
     *
     * @param instrumentationLogging
     *      Whether to log information during instrumentation. Can be useful for troubleshooting.
     * @param addressAlignmentChecking
     *      Whether to check if native addresses are aligned when reading or writing primitive values.
     *      It depends on the operating system and CPU whether unaligned access is safe or leads to undefined behavior.
     *
     *      <p>Can be changed at runtime with {@link ModifySettings#setAddressAlignmentChecking(boolean)}.
     * @param globalNativeMemorySanitizer
     *      Whether to enable the native memory sanitizer globally.
     *
     *      <p>When disabled only field and array memory access is sanitized. Disabling this can be useful when
     *      the tested code is known to not use native memory, but the test or fuzzing framework is using
     *      native memory and the sanitizer could interfere with it or slow it down.
     *
     *      <p>Regardless of whether the sanitizer is enabled globally, it is always possible to sanitize native memory
     *      access in a local scope using {@link #withScopedNativeMemoryTracking(boolean, MemoryAction)}.
     *
     *      <p>Can be disabled at runtime with {@link ModifySettings#disableNativeMemorySanitizer()}.
     * @param uninitializedMemoryTracking
     *     Whether to track if native memory is initialized or not.
     *
     *     <p>Disabling this can improve performance, but will then not report any errors when uninitialized
     *     memory is read. Has no effect for global tracking when native memory sanitization is
     *     {@linkplain #withGlobalNativeMemorySanitizer(boolean) disabled}, but can affect {@linkplain #withScopedNativeMemoryTracking(MemoryAction) scoped tracking}.
     * @param errorAction
     *      Defines how to handle bad memory access; can be changed at runtime with
     *      {@link ModifySettings#setErrorAction(ErrorAction)}.
     * @param printErrorsToSystemConsole
     *      Whether to print bad memory access errors to {@link System#console()} (if available) instead of {@link System#err}.
     *
     *      <p>Using the system console has the advantage that the output will be visible, even if {@link System#setErr(PrintStream)}
     *      has been used to replace {@code System.err} (and the new output stream possibly discards all output).
     *      However, {@link System#console()} writes to the 'out' stream instead of the 'err' stream of the process.
     *      If the console is not available ({@code console == null}) {@code System.err} is used as fallback.
     * @param includeSanitizerErrorStackFrames
     *      Whether errors reported by the sanitizer should include sanitizer classes in the stack trace.
     *
     *      <p>Disabling this can make the error stack traces easier to read, but on the other hand can make
     *      troubleshooting more difficult in case it is not clear why the sanitizer reported an error or when trying
     *      to set a breakpoint within the sanitizer code.
     *
     *      <p>Can be changed at runtime with {@link ModifySettings#setIncludeSanitizerErrorStackFrames(boolean)}
     * @param callDebugLogging
     *      Whether to log debug information about called {@code Unsafe} methods; can be changed at runtime
     *      with {@link ModifySettings#setCallDebugLogging(boolean)}.
     */
    public record AgentSettings(
        boolean instrumentationLogging,
        boolean addressAlignmentChecking,
        boolean globalNativeMemorySanitizer,
        boolean uninitializedMemoryTracking,
        ErrorAction errorAction,
        boolean printErrorsToSystemConsole,
        boolean includeSanitizerErrorStackFrames,
        boolean callDebugLogging
    ) {
        public AgentSettings {
            Objects.requireNonNull(errorAction);
        }

        /**
         * Creates 'default' agent settings suitable for most use cases. The settings have the following values:
         * <ul>
         *     <li>{@link #instrumentationLogging()}: true</li>
         *     <li>{@link #addressAlignmentChecking()}: true</li>
         *     <li>{@link #globalNativeMemorySanitizer()}: true</li>
         *     <li>{@link #uninitializedMemoryTracking()}: true</li>
         *     <li>{@link #errorAction()}: {@link ErrorAction#THROW}</li>
         *     <li>{@link #printErrorsToSystemConsole()}: false</li>
         *     <li>{@link #includeSanitizerErrorStackFrames()}: true</li>
         *     <li>{@link #callDebugLogging()}: false</li>
         * </ul>
         *
         * <p>The returned settings can be customized further using the {@code withX} methods of this
         * class, for example:
         * <pre>
         * var settings = AgentSettings.defaultSettings().withCallDebugLogging(true);
         * </pre>
         */
        public static AgentSettings defaultSettings() {
            return new AgentSettings(
                true,
                true,
                // Note: If this default for `globalNativeMemorySanitizer` causes issues even for `premain` when
                // there have already been allocations before, leading to false positive errors, could consider
                // skipping native memory access sanitization for all threads which already existed when `premain`
                // was called (except for 'main' thread)
                true,
                true,
                ErrorAction.THROW,
                false,
                true,
                false
            );
        }

        /**
         * Creates new agent settings with modified {@link #instrumentationLogging()} value.
         */
        public AgentSettings withInstrumentationLogging(boolean instrumentationLogging) {
            return new AgentSettings(
                instrumentationLogging,
                addressAlignmentChecking,
                globalNativeMemorySanitizer,
                uninitializedMemoryTracking,
                errorAction,
                printErrorsToSystemConsole,
                includeSanitizerErrorStackFrames,
                callDebugLogging
            );
        }

        /**
         * Creates new agent settings with modified {@link #addressAlignmentChecking()} value.
         */
        public AgentSettings withAddressAlignmentChecking(boolean addressAlignmentChecking) {
            return new AgentSettings(
                instrumentationLogging,
                addressAlignmentChecking,
                globalNativeMemorySanitizer,
                uninitializedMemoryTracking,
                errorAction,
                printErrorsToSystemConsole,
                includeSanitizerErrorStackFrames,
                callDebugLogging
            );
        }

        /**
         * Creates new agent settings with modified {@link #globalNativeMemorySanitizer()} value.
         */
        public AgentSettings withGlobalNativeMemorySanitizer(boolean globalNativeMemorySanitizer) {
            return new AgentSettings(
                instrumentationLogging,
                addressAlignmentChecking,
                globalNativeMemorySanitizer,
                uninitializedMemoryTracking,
                errorAction,
                printErrorsToSystemConsole,
                includeSanitizerErrorStackFrames,
                callDebugLogging
            );
        }

        /**
         * Creates new agent settings with modified {@link #uninitializedMemoryTracking()} value.
         */
        public AgentSettings withUninitializedMemoryTracking(boolean uninitializedMemoryTracking) {
            return new AgentSettings(
                instrumentationLogging,
                addressAlignmentChecking,
                globalNativeMemorySanitizer,
                uninitializedMemoryTracking,
                errorAction,
                printErrorsToSystemConsole,
                includeSanitizerErrorStackFrames,
                callDebugLogging
            );
        }

        /**
         * Creates new agent settings with modified {@link #errorAction()} value.
         */
        public AgentSettings withErrorAction(ErrorAction errorAction) {
            return new AgentSettings(
                instrumentationLogging,
                addressAlignmentChecking,
                globalNativeMemorySanitizer,
                uninitializedMemoryTracking,
                errorAction,
                printErrorsToSystemConsole,
                includeSanitizerErrorStackFrames,
                callDebugLogging
            );
        }

        /**
         * Creates new agent settings with modified {@link #printErrorsToSystemConsole()} value.
         */
        public AgentSettings withPrintErrorsToSystemConsole(boolean printErrorsToSystemConsole) {
            return new AgentSettings(
                instrumentationLogging,
                addressAlignmentChecking,
                globalNativeMemorySanitizer,
                uninitializedMemoryTracking,
                errorAction,
                printErrorsToSystemConsole,
                includeSanitizerErrorStackFrames,
                callDebugLogging
            );
        }

        /**
         * Creates new agent settings with modified {@link #includeSanitizerErrorStackFrames()} value.
         */
        public AgentSettings withIncludeSanitizerErrorStackFrames(boolean includeStackFrames) {
            return new AgentSettings(
                instrumentationLogging,
                addressAlignmentChecking,
                globalNativeMemorySanitizer,
                uninitializedMemoryTracking,
                errorAction,
                printErrorsToSystemConsole,
                includeStackFrames,
                callDebugLogging
            );
        }

        /**
         * Creates new agent settings with modified {@link #callDebugLogging()} value.
         */
        public AgentSettings withCallDebugLogging(boolean callDebugLogging) {
            return new AgentSettings(
                instrumentationLogging,
                addressAlignmentChecking,
                globalNativeMemorySanitizer,
                uninitializedMemoryTracking,
                errorAction,
                printErrorsToSystemConsole,
                includeSanitizerErrorStackFrames,
                callDebugLogging
            );
        }
    }

    private static volatile boolean isInstalled = false;
    private static final Lock installLock = new ReentrantLock();
    @Nullable("when the agent has not been installed yet")
    private static AgentSettings agentSettings;

    /**
     * Dynamically installs the agent at runtime. Has no effect if the agent has already been installed.
     *
     * <h4>Warning</h4>
     * Dynamically installing agents at runtime might not be supported by all JVMs and might require a full JDK instead
     * of just a JRE (or certain JDK modules not included by a JRE by default), see {@link ByteBuddyAgent#install()}
     * for details. Additionally, future JDK versions will disallow dynamically installing agents by default,
     * see <a href="https://openjdk.org/jeps/451">JEP 451</a>.
     *
     * <p>If possible prefer installing the agent by starting the JVM with {@code -javaagent}, or use
     * {@link #installAgent(Instrumentation, AgentSettings)} if an {@link Instrumentation} instance is already
     * available.
     *
     * @param settings
     *      settings for the sanitizer agent; if the agent has been installed before, these settings must
     *      match the previous ones, otherwise an exception is thrown
     * @throws IllegalArgumentException
     *      if this agent had already been installed before but with different {@link AgentSettings}
     */
    public static void installAgent(AgentSettings settings) throws IllegalArgumentException {
        installAgent(ByteBuddyAgent.install(), settings);
    }

    /**
     * Installs the agent, using a provided {@link Instrumentation} instance. Has no effect if the agent has already
     * been installed.
     *
     * <p>This method is intended for third-party agents which already have an {@code Instrumentation} instance
     * available and want to additionally install this unsafe-sanitizer agent.
     *
     * @param instrumentation
     *      instrumentation instance used for installing the agent
     * @param settings
     *      settings for the sanitizer agent; if the agent has been installed before, these settings must
     *      match the previous ones, otherwise an exception is thrown
     * @throws IllegalArgumentException
     *      if this agent had already been installed before but with different {@link AgentSettings}
     */
    public static void installAgent(Instrumentation instrumentation, AgentSettings settings) throws IllegalArgumentException {
        Objects.requireNonNull(instrumentation);
        Objects.requireNonNull(settings);

        // Note: Don't return fast here if `isInstalled == true` without acquiring lock;
        // instead always acquire lock to make sure that once method returns agent has been fully installed
        // (possibly by different thread though)
        installLock.lock();
        try {
            if (isInstalled) {
                assert UnsafeSanitizer.agentSettings != null;
                if (!UnsafeSanitizer.agentSettings.equals(settings)) {
                    throw new IllegalArgumentException(
                        "Settings of installed agent do not match provided settings:\n  previous settings: "
                        + UnsafeSanitizer.agentSettings + "\n  new settings: " + settings
                    );
                }
                return;
            }

            UnsafeSanitizer.agentSettings = settings;
            addAgentToBootstrapClasspath(instrumentation);

            UnsafeSanitizerImpl.setCheckAddressAlignment(settings.addressAlignmentChecking);
            if (!settings.globalNativeMemorySanitizer) {
                UnsafeSanitizerImpl.disableNativeMemorySanitizer();
            }
            UnsafeSanitizerImpl.enableUninitializedMemoryTracking(settings.uninitializedMemoryTracking);
            UnsafeSanitizerImpl.setErrorAction(settings.errorAction.getAgentErrorAction());
            UnsafeSanitizerImpl.setIsPrintErrorsToConsole(settings.printErrorsToSystemConsole);
            UnsafeSanitizerImpl.setIsIncludeSanitizerStackFrames(settings.includeSanitizerErrorStackFrames);
            UnsafeSanitizerImpl.setIsDebugLogging(settings.callDebugLogging);

            // Allow the agent to access the values of the internal `DirectByteBuffer$Deallocator` class
            Module agentModule = UnsafeSanitizerImpl.class.getModule();
            instrumentation.redefineModule(
                ByteBuffer.class.getModule(),
                Set.of(),
                Map.of(),
                // Add 'opens'
                Map.of("java.nio", Set.of(agentModule)),
                Set.of(),
                Map.of()
            );

            var transforms = List.of(
                new TransformBuilder(Unsafe.class)
                    .addMethods("allocateMemory", AllocateMemory.class)
                    .addMethods("reallocateMemory", ReallocateMemory.class)
                    .addMethods("freeMemory", FreeMemory.class)
                    .addMethods(named("setMemory").and(isPublic()).and(takesArguments(4)), SetMemoryObject.class)
                    .addMethods(named("setMemory").and(isPublic()).and(takesArguments(3)), SetMemoryAddress.class)
                    .addMethods(named("copyMemory").and(isPublic()).and(takesArguments(5)), CopyMemoryObject.class)
                    .addMethods(named("copyMemory").and(isPublic()).and(takesArguments(3)), CopyMemoryAddress.class)
                    .addMethods(
                        nameStartsWith("get").and(isPublic())
                            .and(takesArgument(0, long.class))
                            // `getAddress` is handled separately below
                            .and(not(named("getAddress"))),
                        GetX.class
                    )
                    .addMethods("getAddress", GetAddress.class)
                    .addMethods(
                        nameStartsWith("get").and(isPublic())
                            .and(takesArgument(0, Object.class))
                            // `getAndSetObject` is handled separately below
                            .and(not(named("getAndSetObject"))),
                        GetXObject.class
                    )
                    .addMethods("getAndSetObject", GetAndSetObject.class)
                    .addMethods(
                        nameStartsWith("put").and(isPublic())
                            .and(takesArgument(0, long.class))
                            // `putAddress` is handled separately below
                            .and(not(named("putAddress"))),
                        PutX.class
                    )
                    .addMethods("putAddress", PutAddress.class)
                    .addMethods(
                        nameStartsWith("put").and(isPublic())
                            .and(takesArgument(0, Object.class)),
                        PutXObject.class
                    )
                    .addMethods(
                        nameStartsWith("compareAndSwap").and(isPublic())
                            // `compareAndSwapObject` is handled separately below
                            .and(not(named("compareAndSwapObject"))),
                        CompareAndSwapX.class
                    )
                    .addMethods("compareAndSwapObject", CompareAndSwapObject.class),
                new TransformBuilder(ByteBuffer.class)
                    .addMethods("allocateDirect", DirectByteBufferInterceptors.AllocateDirect.class),
                new TransformBuilder(DEALLOCATOR_CLASS_NAME)
                    .addMethods("run", DirectByteBufferInterceptors.DeallocatorRun.class)
            );

            // The agent and the advice classes are installed here (in non-bootstrap) because otherwise if this was
            // done in the JAR loaded from bootstrap Byte Buddy seems to be unable to locate the advice classes,
            // Possibly same issue as https://github.com/raphw/byte-buddy/issues/720
            AgentBuilder agentBuilder = new AgentBuilder.Default()
                // Need to use `RETRANSFORMATION` because the JDK classes which should be instrumented are most likely
                // already loaded, see https://github.com/raphw/byte-buddy/issues/1564#issuecomment-1906823082
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
                // (Not completely sure if `disableClassFormatChanges()` is needed, but the Byte Buddy comment above
                //  mentions it, so use it to be safe)
                .disableClassFormatChanges()
                // Overwrite default ignore matcher to be able to instrument the JDK classes loaded by the bootstrap
                // classloader, see https://github.com/raphw/byte-buddy/issues/236#issuecomment-268968111
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."));
                /*
                 * TODO: Not sure if this is needed as well (maybe for cases where `Unsafe` has not been loaded yet?):
                 *  .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                 *  .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                 */

            AgentBuilder.Listener listener = null;
            if (settings.instrumentationLogging) {
                agentBuilder = agentBuilder.with(AgentBuilder.InstallationListener.StreamWriting.toSystemOut());

                var typeNames = transforms.stream().map(TransformBuilder::getClassToTransform).map(Class::getName).toList();

                listener = new AgentBuilder.Listener.Filtering(
                    ElementMatchers.anyOf(typeNames),
                    AgentBuilder.Listener.StreamWriting.toSystemOut()
                );
            }

            var errorListener = new ErrorCollectingAgentListener();
            if (listener == null) {
                listener = errorListener;
            } else {
                listener = new AgentBuilder.Listener.Compound(listener, errorListener);
            }
            agentBuilder = agentBuilder.with(listener);

            for (TransformBuilder transform : transforms) {
                agentBuilder = transform.configure(agentBuilder);
            }

            agentBuilder.installOn(instrumentation);

            var errors = errorListener.getErrors();
            if (!errors.isEmpty()) {
                var exception = new RuntimeException("Failed installing agent");
                errors.forEach(exception::addSuppressed);
                throw exception;
            }

            isInstalled = true;
        } finally {
            installLock.unlock();
        }
    }

    private static Path createLockFilePath(Path path) {
        return path.resolveSibling(path.getFileName().toString() + ".lock");
    }

    /*
     * The agent has to be added to the bootstrap classpath so that the injected `Advice` code can access
     * its classes.
     *
     * The implementation here is based on https://github.com/CodeIntelligenceTesting/jazzer/blob/5bc43a6f65180cb003605349c9e2fdadc702d2c8/src/main/java/com/code_intelligence/jazzer/agent/AgentUtils.java#L30
     * The agent JAR is embedded as resource, this has the following advantages:
     * - In case the enclosing JAR is repackaged, the agent JAR stays intact
     * - Prevents accidentally loading agent classes with non-bootstrap classloader, causing errors at runtime
     *   (now can only get errors about missing classes if agent has not been installed yet, but public API
     *    here prevents this)
     * - Can easily obtain agent-impl JAR, whereas if agent-impl JAR was not separate, using something like `class.getProtectionDomain().getCodeSource().getLocation()`
     *   might have the file URL of a large repackaged uber JAR as result, which should most likely not be added
     *   to the bootstrap classpath
     */
    private static void addAgentToBootstrapClasspath(Instrumentation instrumentation) {
        String jarNamePrefix = "unsafe-sanitizer-agent-";
        String jarNameSuffix = ".jar";

        Path jarDir;
        JarFile jar;
        try {
            Path agentJarPath = Files.createTempFile(jarNamePrefix, jarNameSuffix);
            InputStream agentJarStream = UnsafeSanitizer.class.getResourceAsStream("agent-impl.jar");
            if (agentJarStream == null) {
                throw new IllegalStateException("agent-impl JAR is missing");
            }

            try (agentJarStream) {
                Files.copy(agentJarStream, agentJarPath, StandardCopyOption.REPLACE_EXISTING);
            }
            jarDir = agentJarPath.getParent();

            // On Windows this seems to have no effect, see https://bugs.openjdk.org/browse/JDK-8219681
            // Therefore need to clean up manually, see below
            agentJarPath.toFile().deleteOnExit();
            // Create a 'lock file' to know if agent JAR is still in use
            Path lockFile = createLockFilePath(agentJarPath);
            Files.createFile(lockFile);
            lockFile.toFile().deleteOnExit();

            jar = new JarFile(agentJarPath.toFile());
        } catch (Exception e) {
            throw new IllegalStateException("Failed preparing agent-impl JAR", e);
        }

        // Delete old temporary agent JAR files, because on Windows `deleteOnExit` does not work for this,
        // see https://bugs.openjdk.org/browse/JDK-8219681
        DirectoryStream.Filter<Path> agentJarFilter = file -> {
            if (!Files.isRegularFile(file)) {
                return false;
            }

            String name = file.getFileName().toString();
            return name.startsWith(jarNamePrefix) && name.endsWith(jarNameSuffix);
        };
        try (DirectoryStream<Path> agentJarFiles = Files.newDirectoryStream(jarDir, agentJarFilter)) {
            for (Path agentJarFile : agentJarFiles) {
                Path lockFile = createLockFilePath(agentJarFile);
                // Only delete if the agent JAR is not in use anymore, i.e. the lock file does not exist
                if (Files.notExists(lockFile)) {
                    try {
                        Files.delete(agentJarFile);
                    } catch (IOException e) {
                        // Ignore if deletion failed
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed cleaning up old agent JARs", e);
        }

        instrumentation.appendToBootstrapClassLoaderSearch(jar);
    }

    /** Verifies that the agent is installed, and the agent-impl classes can be accessed */
    static void checkInstalled() {
        if (!isInstalled) {
            throw new IllegalStateException("Agent has not been installed");
        }
    }

    /**
     * Allows modifying some of the settings of the sanitizer at runtime, after it had been installed (on JVM startup
     * or {@linkplain #installAgent(AgentSettings) at runtime}). Not all settings can be changed at runtime; prefer
     * directly specifying the desired {@link AgentSettings} when the sanitizer is installed.
     *
     * <p>An instance can be obtained with {@link #modifySettings()}. Calling any of its methods directly applies
     * the settings change. The methods return {@code this} to allow chaining.
     */
    // TODO: Should this update / replace the `agentSettings` (while holding `installLock`)?
    // TODO: Remove ModifySettings (or some of its methods) and only allow configuring settings on installation through AgentSettings?
    public static class ModifySettings {
        private ModifySettings() {
        }

        private static final ModifySettings INSTANCE = new ModifySettings();

        /**
         * Sets whether to check if native addresses are aligned when reading or writing primitive values.
         *
         * <p>This setting can already be specified when the agent is installed by using {@link AgentSettings#withAddressAlignmentChecking(boolean)}.
         */
        public ModifySettings setAddressAlignmentChecking(boolean addressAlignmentChecking) {
            UnsafeSanitizerImpl.setCheckAddressAlignment(addressAlignmentChecking);
            return this;
        }

        /**
         * Disables global sanitization of native memory access.
         *
         * <p>When disabled, only field and array memory access is sanitized. Disabling this can be useful when
         * the tested code is known to not use native memory, but the test or fuzzing framework is using
         * native memory and the sanitizer could interfere with it or slow it down.
         *
         * <p>This setting can already be specified when the agent is installed by using {@link AgentSettings#withGlobalNativeMemorySanitizer(boolean)}.
         */
        // No method for enabling this again because otherwise could lead to spurious errors for memory
        // which was allocated while sanitizer was disabled
        public ModifySettings disableNativeMemorySanitizer() {
            UnsafeSanitizerImpl.disableNativeMemorySanitizer();
            return this;
        }

        /**
         * Sets the error action, that is, how to react to bad memory access.
         *
         * <p>This setting can already be specified when the agent is installed by using {@link AgentSettings#withErrorAction(ErrorAction)}.
         */
        public ModifySettings setErrorAction(ErrorAction errorAction) {
            Objects.requireNonNull(errorAction);
            UnsafeSanitizerImpl.setErrorAction(errorAction.getAgentErrorAction());
            return this;
        }

        /**
         * Sets whether errors reported by the sanitizer should include sanitizer classes in the stack trace.
         *
         * <p>This setting can already be specified when the agent is installed by using {@link AgentSettings#withIncludeSanitizerErrorStackFrames(boolean)}.
         */
        public ModifySettings setIncludeSanitizerErrorStackFrames(boolean includeStackFrames) {
            UnsafeSanitizerImpl.setIsIncludeSanitizerStackFrames(includeStackFrames);
            return this;
        }

        /**
         * Sets whether debug logging for calls to {@code Unsafe} methods should be enabled.
         *
         * <p>This setting can already be specified when the agent is installed by using {@link AgentSettings#withCallDebugLogging(boolean)}.
         */
        public ModifySettings setCallDebugLogging(boolean callDebugLogging) {
            UnsafeSanitizerImpl.setIsDebugLogging(callDebugLogging);
            return this;
        }
    }

    /**
     * Allows modifying some of the sanitizer settings at runtime.
     * Not all settings can be changed at runtime, prefer directly specifying the desired {@link AgentSettings}
     * when the sanitizer is installed.
     *
     * @throws IllegalStateException
     *      if the sanitizer agent has not been installed yet
     */
    public static ModifySettings modifySettings() {
        checkInstalled();
        return ModifySettings.INSTANCE;
    }

    // Important: Guard all these methods with `checkInstalled()`, otherwise they will fail because agent classes
    // have not been added to classpath yet

    // TODO: Maybe only provide `getAndClearLastError()` but not `getLastError()` and `clearLastError()`?
    //   (but remove `@CheckReturnValue` then)

    /**
     * Gets the last bad memory access error, if any, returning {@code null} otherwise.
     *
     * <p>This method is useful to verify that no bad memory access error has not been discarded somewhere
     * in the code calling {@code Unsafe}, or when an {@linkplain AgentSettings#errorAction() error action}
     * is used which does not throw the error. The last error is set regardless of whether the original error
     * was propagated or not. And it will not be cleared automatically if subsequent usage of {@code Unsafe}
     * methods performs good memory access.
     *
     * <p>The error is retrieved from the global scope, or from the current {@linkplain #withScopedNativeMemoryTracking(boolean, MemoryAction) local scope}
     * (in case one is active). For the global scope it might be overwritten concurrently by a different thread.
     *
     * <p>Normally {@link #getAndClearLastError()} should be preferred over {@code getLastError()} to avoid
     * accidentally retrieving the same error later again on subsequent calls. Alternatively {@link #clearLastError()}
     * can be used to clear the error after a {@code getLastError()} call.
     */
    @CheckReturnValue
    @Nullable
    public static Error getLastError() {
        checkInstalled();
        return UnsafeSanitizerImpl.getLastErrorRef().get();
    }

    /**
     * Clears the last bad memory access error, if any.
     *
     * @see #getLastError()
     */
    public static void clearLastError() {
        //noinspection ResultOfMethodCallIgnored,ThrowableNotThrown
        getAndClearLastError();
    }

    /**
     * Gets and clears the last bad memory access error, if any, returning {@code null} otherwise.
     *
     * <p>This is a convenience method which combines {@link #getLastError()} and {@link #clearLastError()}.
     */
    @CheckReturnValue
    @Nullable
    public static Error getAndClearLastError() {
        checkInstalled();
        // Directly perform this using `AtomicReference#getAndSet` to avoid a race condition where clearing
        // erroneously clears a different error which has been set in the meantime
        return UnsafeSanitizerImpl.getLastErrorRef().getAndSet(null);
    }

    /**
     * Manually registers a section of allocated memory with this sanitizer.
     *
     * <p>This method is intended for use cases where the sanitizer has to be informed that a certain
     * section of memory has already been allocated without it having noticed it. For example when the agent
     * has been {@linkplain #installAgent(AgentSettings) installed at runtime}, or when the memory has been
     * allocated through means other than {@code Unsafe} or {@link ByteBuffer#allocateDirect(int)}.
     * Otherwise if that memory had not been registered, the sanitizer would likely report errors when
     * trying to access that memory later.
     *
     * @param isInitialized
     *      whether the memory should be considered fully initialized or uninitialized; this is relevant when
     *      {@link AgentSettings#uninitializedMemoryTracking()} is enabled
     *
     * @see #deregisterAllocatedMemory(long)
     */
    public static void registerAllocatedMemory(long address, long bytesCount, boolean isInitialized) {
        checkInstalled();
        if (address <= 0) {
            throw new IllegalArgumentException("Invalid address: " + address);
        }
        if (bytesCount <= 0) {
            throw new IllegalArgumentException("Invalid bytes count: " + bytesCount);
        }

        // TODO: Maybe handle it better when ErrorAction is not THROW (and this only returns false instead of throwing)?
        if (UnsafeSanitizerImpl.onAllocatedMemory(address, bytesCount, false)) {
            if (isInitialized) {
                // Perform a dummy write access to mark memory section as fully initialized
                UnsafeSanitizerImpl.onWriteAccess(null, address, bytesCount);
            }
        } else {
            throw new IllegalStateException("Failed to register allocated memory");
        }
    }

    /**
     * Deregisters allocated memory previously registered with {@link #registerAllocatedMemory(long, long, boolean)}.
     */
    public static void deregisterAllocatedMemory(long address) {
        checkInstalled();
        if (address <= 0) {
            throw new IllegalArgumentException("Invalid address: " + address);
        }

        // Note: This does not actually check that memory has been registered with `registerAllocatedMemory` before,
        // but that is probably fine for now; in case of double free this would then raise an error
        // TODO: Maybe handle it better when ErrorAction is not THROW (and `freeMemory` only returns false instead of throwing)?
        if (!UnsafeSanitizerImpl.freeMemory(address)) {
            throw new IllegalStateException("Failed to deregister allocated memory");
        }
    }

    /**
     * Action which performs native memory access, which should be sanitized by
     * {@link #withScopedNativeMemoryTracking(boolean, MemoryAction)}.
     *
     * @param <E>
     *      the type of the exception thrown by the action, if any
     */
    @FunctionalInterface
    public interface MemoryAction<E extends Throwable> {
        void run() throws E;
    }

    // TODO: Maybe also have method with allows running scoped so that `lastError` is scoped, but without
    //   enabling native memory tracking (or using global native memory tracking, if enabled?)

    /**
     * Same as {@link #withScopedNativeMemoryTracking(boolean, MemoryAction)}, except that whether uninitialized
     * memory is tracked is inherited from the {@linkplain AgentSettings#withUninitializedMemoryTracking(boolean) agent settings}.
     */
    public static <E extends Throwable> void withScopedNativeMemoryTracking(MemoryAction<E> action) throws IllegalStateException, E {
        checkInstalled();
        Objects.requireNonNull(action);

        UnsafeSanitizerImpl.withScopedNativeMemoryTracking(null, action::run);
    }

    /**
     * Runs the {@code action} in a scope where native memory tracking is enabled, regardless of whether it has
     * been enabled {@linkplain AgentSettings#globalNativeMemorySanitizer() globally} before.
     *
     * <p>Using scoped tracking can be useful to avoid interference with other code using {@code Unsafe}, such
     * as the testing or fuzzing framework running the tested code. Note however that the scope is thread-local,
     * so any access in other threads is not tracked.
     *
     * <p>The scope tracks the {@linkplain #getLastError() last error} separately from the global scope. If the
     * {@code action} does not throw any exception, but it is detected that a bad memory access occurred, an
     * {@link IllegalStateException} is thrown because the original bad memory access error was apparently
     * (accidentally) discarded.
     *
     * @param trackUninitialized
     *      whether to additionally track if read access on uninitialized memory is performed
     * @param action
     *      action to run with native memory tracking enabled
     * @throws IllegalStateException
     *      if a local scope is already active
     * @throws IllegalStateException
     *      if no exception was thrown by the {@code action}, but bad memory access was detected (that is, the
     *      bad memory access error was discarded)
     * @throws E
     *      exception thrown by {@code action}, if any
     *
     * @see #withScopedNativeMemoryTracking(MemoryAction)
     */
    public static <E extends Throwable> void withScopedNativeMemoryTracking(boolean trackUninitialized, MemoryAction<E> action) throws IllegalStateException, E {
        checkInstalled();
        Objects.requireNonNull(action);

        UnsafeSanitizerImpl.withScopedNativeMemoryTracking(trackUninitialized, action::run);
    }

    /**
     * Verifies that all native memory has been freed again.
     *
     * <p>This can be especially useful in combination with {@link #withScopedNativeMemoryTracking(boolean, MemoryAction)},
     * as last call in the memory action. Using it with the global memory sanitizer should be done with care since
     * allocations unrelated to the tested library might have been performed, e.g. by the testing or fuzzing framework.
     *
     * @throws IllegalStateException
     *      if the native memory sanitizer is not enabled (neither globally nor in a local scope)
     * @throws IllegalStateException
     *      if not all native memory has been freed
     */
    public static void checkAllNativeMemoryFreed() {
        checkInstalled();
        // TODO: Maybe support `forgetMemorySections = true` as well? Otherwise users will experience same issues
        //   as in the tests in this project: Once one test forgets to clean up memory all subsequent tests will
        //   fail because for them the memory from the first failing test still exists
        //   Remove `TestSupport#checkAllNativeMemoryFreedAndForget` then
        UnsafeSanitizerImpl.checkAllNativeMemoryFreed(false);
    }
}
