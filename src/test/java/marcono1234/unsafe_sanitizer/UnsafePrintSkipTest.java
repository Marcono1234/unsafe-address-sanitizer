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

import static marcono1234.unsafe_sanitizer.MemoryHelper.allocateMemory;
import static marcono1234.unsafe_sanitizer.MemoryHelper.freeMemory;
import static marcono1234.unsafe_sanitizer.UnsafeAccess.unsafe;
import static marcono1234.unsafe_sanitizer.UnsafeInterceptors.INVALID_ADDRESS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests using {@link sun.misc.Unsafe} with {@link ErrorAction#PRINT_SKIP}.
 *
 * <p>This only covers basic error scenarios; see {@link UnsafeThrowErrorTest} for more extensive tests.
 * The main point of this test is to verify that skipping works as expected.
 */
class UnsafePrintSkipTest {
    @BeforeAll
    static void installAgent() {
        UnsafeSanitizer.installAgent(AgentSettings.defaultSettings());
        UnsafeSanitizer.setErrorAction(ErrorAction.PRINT_SKIP);
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
            // The tests here don't run with `ErrorAction.THROW`, so any thrown exception is unexpected
            lastError = UnsafeSanitizer.getAndClearLastError();
            if (lastError == null || lastError == t) {
                throw new AssertionError("Unexpected exception", t);
            } else {
                var e = new AssertionError("Unexpected exception, but expected error occurred as well", t);
                e.addSuppressed(lastError);
                throw e;
            }
        } finally {
            System.setErr(oldSystemErr);
        }

        tempSystemErr.flush();
        String systemErrOutput = capturedErr.toString(StandardCharsets.UTF_8);
        lastError = UnsafeSanitizer.getAndClearLastError();
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
        assertBadMemoryAccess(() -> {
            long a = unsafe.allocateMemory(-1);
            assertEquals(INVALID_ADDRESS, a);
        });
    }

    @Test
    void free_bad() {
        assertBadMemoryAccess(() -> unsafe.freeMemory(-1));
        assertBadMemoryAccess(() -> {
            // Assumes that no allocation has been performed at this address
            unsafe.freeMemory(1);
        });
    }

    @Test
    void reallocate_bad() {
        assertBadMemoryAccess(() -> {
            long a = unsafe.reallocateMemory(-1, 10);
            assertEquals(INVALID_ADDRESS, a);
        });
        assertBadMemoryAccess(() -> {
            // Assumes that no allocation has been performed at this address
            long a = unsafe.reallocateMemory(1, 10);
            assertEquals(INVALID_ADDRESS, a);
        });

        {
            long a = allocateMemory(10);
            assertBadMemoryAccess(() -> {
                long b = unsafe.reallocateMemory(a, -10);
                assertEquals(INVALID_ADDRESS, b);
            });
            // Clean up
            freeMemory(a);
        }
    }

    @Test
    void get_bad() {
        assertBadMemoryAccess(() -> {
            boolean b = unsafe.getBoolean(null, -1);
            //noinspection SimplifiableAssertion
            assertEquals(false, b);
        });
        assertBadMemoryAccess(() -> {
            byte b = unsafe.getByte(-1);
            assertEquals(0, b);
        });
        assertBadMemoryAccess(() -> {
            long a = unsafe.getAddress(-1);
            assertEquals(INVALID_ADDRESS, a);
        });
        assertBadMemoryAccess(() -> {
            Object o = unsafe.getObject(null, -1);
            assertNull(o);
        });

        assertBadMemoryAccess(() -> {
            char c = unsafe.getCharVolatile(null, -1);
            assertEquals('\0', c);
        });

        assertBadMemoryAccess(() -> {
            int i = unsafe.getAndAddInt(null, -1, 1);
            assertEquals(0, i);
        });
        assertBadMemoryAccess(() -> {
            long l = unsafe.getAndSetLong(null, -1, 1);
            assertEquals(0, l);
        });
    }

    @Test
    void compareAndSwap_bad() {
        assertBadMemoryAccess(() -> {
            boolean result = unsafe.compareAndSwapInt(null, -1, 0, 1);
            assertFalse(result);
        });
        assertBadMemoryAccess(() -> {
            boolean result = unsafe.compareAndSwapLong(null, -1, 0, 1);
            assertFalse(result);
        });
        assertBadMemoryAccess(() -> {
            boolean result = unsafe.compareAndSwapObject(null, -1, null, "a");
            assertFalse(result);
        });
    }

    // TODO Cover other methods as well
}
