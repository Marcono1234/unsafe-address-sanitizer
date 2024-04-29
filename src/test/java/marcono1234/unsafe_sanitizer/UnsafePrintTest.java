package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.TestSupport.ThrowingRunnable;
import marcono1234.unsafe_sanitizer.UnsafeSanitizer.AgentSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static marcono1234.unsafe_sanitizer.UnsafeAccess.unsafe;

/**
 * Tests using {@link sun.misc.Unsafe} with {@link ErrorAction#PRINT}.
 *
 * <p>This only covers very few scenarios to verify that printing the error works in general.
 * Since {@link ErrorAction#PRINT} is neither throwing an error not skipping execution, other
 * error cases could otherwise crash the JVM.
 *
 * <p>See {@link UnsafeThrowErrorTest} for more extensive tests.
 */
class UnsafePrintTest {
    @BeforeAll
    static void installAgent() {
        UnsafeSanitizer.installAgent(AgentSettings.defaultSettings());
        UnsafeSanitizer.setErrorAction(ErrorAction.PRINT);
        // This mainly prevents spurious errors in case any other test failed to free memory
        TestSupport.checkAllNativeMemoryFreedAndForget();
    }

    @AfterAll
    static void resetErrorAction() {
        UnsafeSanitizer.setErrorAction(ErrorAction.THROW);
    }

    @AfterEach
    void checkNoError() {
        var lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            throw new AssertionError("Unexpected error", lastError);
        }
        TestSupport.checkAllNativeMemoryFreedAndForget();
    }

    private static void assertBadMemoryAccess(ThrowingRunnable runnable) {
        Objects.requireNonNull(runnable);

        var lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            throw new AssertionError("Unexpected previous error", lastError);
        }

        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        PrintStream tempSystemErr = new PrintStream(capturedErr, true, StandardCharsets.UTF_8);
        PrintStream oldSystemErr = System.err;

        try {
            System.setErr(tempSystemErr);
            runnable.run();
        } catch (Throwable t) {
            lastError = UnsafeSanitizer.getAndClearLastError();
            //noinspection ConditionCoveredByFurtherCondition
            if (lastError == null || lastError == t || !(t instanceof RuntimeException)) {
                throw new AssertionError("Unexpected exception", t);
            }
            // Else assume that `Unsafe` API threw RuntimeException
        } finally {
            System.setErr(oldSystemErr);
        }

        tempSystemErr.flush();
        String systemErrOutput = capturedErr.toString(StandardCharsets.UTF_8);
        // Only fetch last error again if not done in `catch` above
        if (lastError == null) {
            lastError = UnsafeSanitizer.getAndClearLastError();
        }

        if (lastError == null) {
            throw new AssertionError("No error was set as 'last error'");
        }

        if (!systemErrOutput.contains(lastError.toString())) {
            var e = new AssertionError("No error output, but expected error occurred; System.err:\n" + systemErrOutput.replaceAll("\\R", "\n  "));
            e.addSuppressed(lastError);
            throw e;
        }
    }

    @Test
    void allocate_bad() {
        assertBadMemoryAccess(() -> unsafe.allocateMemory(-1));
    }
}
