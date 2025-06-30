package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.TestSupport.ThrowingRunnable;
import marcono1234.unsafe_sanitizer.UnsafeSanitizer.AgentSettings;
import marcono1234.unsafe_sanitizer.UnsafeSanitizer.ModifySettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.util.Arrays;

import static marcono1234.unsafe_sanitizer.MemoryHelper.allocateMemory;
import static marcono1234.unsafe_sanitizer.MemoryHelper.freeMemory;
import static marcono1234.unsafe_sanitizer.UnsafeAccess.unsafe;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentSettings#withIncludeSanitizerErrorStackFrames(boolean)} respectively
 * {@link ModifySettings#setIncludeSanitizerErrorStackFrames(boolean)}.
 */
public class StackTraceTest {
    @BeforeAll
    static void installAgent() {
        UnsafeSanitizer.installAgent(UnsafeSanitizer.AgentSettings.defaultSettings());
    }

    @AfterEach
    void checkNoError() {
        var lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            throw new AssertionError("Unexpected error", lastError);
        }
        TestSupport.checkAllNativeMemoryFreedAndForget();
    }

    @AfterAll
    static void restoreIncludeStackFrames() {
        UnsafeSanitizer.modifySettings().setIncludeSanitizerErrorStackFrames(true);
    }

    private static void checkStackTraces(ThrowingRunnable invalidAccessRunnable) {
        Error errorWithStackFrames = null;
        Error errorWithoutStackFrames = null;

        // Run this in a loop with 2 iterations instead of having two separate Unsafe calls to ensure that the
        // error stack traces are identical (except for omitted sanitizer stack frames)
        for (int i = 0; i < 2; i++) {
            boolean includeStackFrames = i == 0;
            UnsafeSanitizer.modifySettings().setIncludeSanitizerErrorStackFrames(includeStackFrames);

            // Note: Using `TestSupport` here also makes sure that its stack frames are not omitted, despite also
            // being part of the sanitizer (but not the actual sanitization implementation)
            var error = TestSupport.assertBadMemoryAccess(invalidAccessRunnable);
            if (includeStackFrames) {
                errorWithStackFrames = error;
            } else {
                errorWithoutStackFrames = error;
            }
        }

        assertEquals(errorWithStackFrames.getMessage(), errorWithoutStackFrames.getMessage());

        // The cause (if any) should have been omitted
        assertNull(errorWithoutStackFrames.getCause());

        var withStackFrames = errorWithStackFrames.getStackTrace();
        var withoutStackFrames = errorWithoutStackFrames.getStackTrace();

        // All sanitizer stack frames should have been omitted
        var unsafeStackFrame = withoutStackFrames[0];
        assertEquals("sun.misc.Unsafe", unsafeStackFrame.getClassName());
        assertEquals("getInt", unsafeStackFrame.getMethodName());

        // Should still include this test class (i.e. the caller of the Unsafe method)
        boolean includesTestClass = Arrays.stream(withoutStackFrames).anyMatch(e -> e.getClassName().equals(StackTraceTest.class.getName()));
        assertTrue(includesTestClass);

        int commonStackTraceStart = 0;
        for (; commonStackTraceStart < withStackFrames.length; commonStackTraceStart++) {
            var stackFrame = withStackFrames[commonStackTraceStart];
            if (stackFrame.equals(unsafeStackFrame)) {
                break;
            }
        }
        assertNotEquals(commonStackTraceStart, withStackFrames.length, "Unsafe stack frame should have been found");

        assertArrayEquals(
            Arrays.copyOfRange(withStackFrames, commonStackTraceStart, withStackFrames.length),
            withoutStackFrames,
            "Stack traces should be identical, except for omitted sanitizer stack frames at start"
        );
    }

    @Test
    void array() {
        checkStackTraces(() -> unsafe.getInt(new byte[10], Unsafe.ARRAY_BYTE_BASE_OFFSET + 100));
    }

    @Test
    void field() throws Exception {
        class Dummy {
            byte b;
        }
        long fieldOffset = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("b"));
        checkStackTraces(() -> unsafe.getInt(new Dummy(), fieldOffset));
    }

    @Test
    void nativeMemory_outOfBounds() {
        long address = allocateMemory(1);
        try {
            checkStackTraces(() -> unsafe.getInt(address));
        } finally {
            freeMemory(address);
        }
    }

    @Test
    void nativeMemory_uninitialized() {
        long address = allocateMemory(Integer.BYTES);
        try {
            checkStackTraces(() -> unsafe.getInt(address));
        } finally {
            freeMemory(address);
        }
    }
}
