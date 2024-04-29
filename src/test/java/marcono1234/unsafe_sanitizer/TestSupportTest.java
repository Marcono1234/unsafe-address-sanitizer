package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.UnsafeSanitizer.AgentSettings;
import marcono1234.unsafe_sanitizer.agent_impl.BadMemoryAccessError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static marcono1234.unsafe_sanitizer.TestSupport.*;
import static marcono1234.unsafe_sanitizer.UnsafeAccess.unsafe;
import static org.junit.jupiter.api.Assertions.*;
import static sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET;

// Suppress redundant IntelliJ warnings
@SuppressWarnings({"Convert2MethodRef", "CodeBlock2Expr"})
class TestSupportTest {
    @BeforeAll
    static void installAgent() {
        UnsafeSanitizer.installAgent(AgentSettings.defaultSettings());
        UnsafeSanitizer.setErrorAction(ErrorAction.THROW);
        // This mainly prevents spurious errors in case any other test failed to free memory
        TestSupport.checkAllNativeMemoryFreedAndForget();
    }

    @AfterEach
    void checkNoError() {
        var lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            throw new AssertionError("Unexpected error", lastError);
        }
        TestSupport.checkAllNativeMemoryFreedAndForget();
    }

    @AfterEach
    void resetErrorAction() {
        UnsafeSanitizer.setErrorAction(ErrorAction.THROW);
    }

    private static void performBadAccess() {
        unsafe.getByte(new byte[0], ARRAY_BYTE_BASE_OFFSET);
    }

    private static void performGoodAccess() {
        unsafe.getByte(new byte[1], ARRAY_BYTE_BASE_OFFSET);
    }

    @Test
    void badAccess() {
        var e = assertBadMemoryAccess(() -> performBadAccess());
        assertEquals(BadMemoryAccessError.class, e.getClass());
        assertEquals(
            "Bad array access at offset " + ARRAY_BYTE_BASE_OFFSET + ", size 1; max offset is " + ARRAY_BYTE_BASE_OFFSET,
            e.getMessage()
        );
        // Last error should have been cleared
        assertNull(UnsafeSanitizer.getLastError());
    }

    @Test
    void badAccess_NoError() {
        var e = assertThrows(AssertionError.class, () -> {
            assertBadMemoryAccess(() -> performGoodAccess());
        });
        assertEquals("No exception was thrown", e.getMessage());
        assertNull(UnsafeSanitizer.getLastError());
    }

    @Test
    void badAccess_OtherError() {
        var otherError = new RuntimeException("custom");
        var e = assertThrows(AssertionError.class, () -> {
            assertBadMemoryAccess(() -> {
                throw otherError;
            });
        });
        assertEquals("Unexpected exception", e.getMessage());
        assertSame(otherError, e.getCause());
        assertNull(UnsafeSanitizer.getLastError());
    }

    @Test
    void badAccess_OtherDiscardedError() {
        var otherError = new RuntimeException("custom");
        var e = assertThrows(AssertionError.class, () -> {
            assertBadMemoryAccess(() -> {
                try {
                    performBadAccess();
                } catch (Throwable t) {
                    throw otherError;
                }
            });
        });
        assertEquals("Unexpected exception, but expected bad memory access error occurred as well", e.getMessage());
        assertSame(otherError, e.getCause());
        assertEquals(BadMemoryAccessError.class, e.getSuppressed()[0].getClass());
        // Last error should have been cleared
        assertNull(UnsafeSanitizer.getLastError());
    }

    @Test
    void badAccess_WrongErrorAction() {
        UnsafeSanitizer.setErrorAction(ErrorAction.PRINT);
        var e = assertThrows(IllegalStateException.class, () -> {
            assertBadMemoryAccess(() -> {});
        });
        assertEquals("Error action must be THROW", e.getMessage());
        assertNull(UnsafeSanitizer.getLastError());
    }


    @Test
    void noBadAccess() {
        assertNoBadMemoryAccess(() -> performGoodAccess());
        assertNull(UnsafeSanitizer.getLastError());
    }

    @Test
    void noBadAccessGet() {
        String result = "test";
        String actualResult = assertNoBadMemoryAccessGet(() -> {
            unsafe.getByte(new byte[1], ARRAY_BYTE_BASE_OFFSET);
            return result;
        });
        assertEquals(result, actualResult);
        assertNull(UnsafeSanitizer.getLastError());
    }

    @Test
    void noBadAccess_Error() {
        var e = assertThrows(AssertionError.class, () -> {
            assertNoBadMemoryAccess(() -> performBadAccess());
        });
        assertEquals("Unexpected exception", e.getMessage());
        // Last error should have been cleared
        assertNull(UnsafeSanitizer.getLastError());
    }

    @Test
    void noBadAccess_OtherError() {
        var otherError = new RuntimeException("custom");
        var e = assertThrows(AssertionError.class, () -> {
            assertNoBadMemoryAccess(() -> {
                throw otherError;
            });
        });
        assertEquals("Unexpected exception", e.getMessage());
        assertSame(otherError, e.getCause());
        assertNull(UnsafeSanitizer.getLastError());
    }

    @Test
    void noBadAccess_OtherDiscardedError() {
        var otherError = new RuntimeException("custom");
        var e = assertThrows(AssertionError.class, () -> {
            assertNoBadMemoryAccess(() -> {
                try {
                    performBadAccess();
                } catch (Throwable t) {
                    throw otherError;
                }
            });
        });
        assertEquals("Unexpected exception, and unexpected bad memory access error occurred as well", e.getMessage());
        assertSame(otherError, e.getCause());
        assertEquals(BadMemoryAccessError.class, e.getSuppressed()[0].getClass());
        // Last error should have been cleared
        assertNull(UnsafeSanitizer.getLastError());
    }

    @Test
    void noBadAccess_WrongErrorAction() {
        UnsafeSanitizer.setErrorAction(ErrorAction.PRINT);
        var e = assertThrows(IllegalStateException.class, () -> {
            assertNoBadMemoryAccess(() -> {});
        });
        assertEquals("Error action must be THROW", e.getMessage());
        assertNull(UnsafeSanitizer.getLastError());
    }
}
