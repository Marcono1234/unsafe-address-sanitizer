package marcono1234.unsafe_sanitizer.agent_impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.*;
import static sun.misc.Unsafe.*;

class ArrayAccessSanitizerTest {
    @BeforeEach
    void setUp() {
        UnsafeSanitizerImpl.setErrorAction(AgentErrorAction.THROW);
    }

    @AfterEach
    void clearLastError() {
        var lastError = UnsafeSanitizerImpl.getLastErrorRefImpl().getAndSet(null);
        if (lastError != null) {
            fail("Unexpected error", lastError);
        }
    }

    private static void assertThrows(Executable executable, String expectedMessage) {
        var e = Assertions.assertThrows(BadMemoryAccessError.class, executable);
        UnsafeSanitizerImpl.getLastErrorRefImpl().set(null);
        assertEquals(expectedMessage, e.getMessage());
    }

    @Test
    void onAccessMemorySize() {
        Object a = new byte[8];
        long offset = ARRAY_BYTE_BASE_OFFSET;
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BOOLEAN));
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_1));
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_2));
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_4));
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_8));
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.ADDRESS));

        a = new long[1];
        offset = ARRAY_LONG_BASE_OFFSET;
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BOOLEAN));
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_1));
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_2));
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_4));
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_8));
        assertTrue(ArrayAccessSanitizer.onAccess(a, offset, MemorySize.ADDRESS));

        a = new Object[1];
        assertTrue(ArrayAccessSanitizer.onAccess(a, ARRAY_OBJECT_BASE_OFFSET, MemorySize.OBJECT));
    }

    @Test
    void onAccessMemorySize_Error() {
        {
            var a = new byte[0];
            long offset = ARRAY_BYTE_BASE_OFFSET;
            String expectedPrefix = "Bad array access at offset " + offset + ", size ";
            String expectedSuffix = "; exceeds end offset " + ARRAY_BYTE_BASE_OFFSET;
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BOOLEAN),
                expectedPrefix + MemorySize.BOOLEAN.getBytesCount() + expectedSuffix
            );
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_1),
                expectedPrefix + MemorySize.BYTE_1.getBytesCount() + expectedSuffix
            );
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_2),
                expectedPrefix + MemorySize.BYTE_2.getBytesCount() + expectedSuffix
            );
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_4),
                expectedPrefix + MemorySize.BYTE_4.getBytesCount() + expectedSuffix
            );
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_8),
                expectedPrefix + MemorySize.BYTE_8.getBytesCount() + expectedSuffix
            );
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, offset, MemorySize.ADDRESS),
                expectedPrefix + MemorySize.ADDRESS.getBytesCount() + expectedSuffix
            );
        }

        {
            var a = new byte[1];
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, -1, MemorySize.BYTE_1),
                "Invalid offset: -1"
            );
        }

        {
            var a = new byte[1];
            // Not using base offset, assuming it is > 0
            assert ARRAY_BYTE_BASE_OFFSET > 0;
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, 0, MemorySize.BYTE_1),
                "Bad array access at offset 0; min offset is " + ARRAY_BYTE_BASE_OFFSET
            );
        }

        {
            var shift = 1;  // shift offset to ensure validation is performed when offset > baseOffset
            var a = new byte[shift + Long.BYTES - 1];  // large enough to account for alignment
            long offset = ARRAY_BYTE_BASE_OFFSET + shift;

            // Ensure that offset is aligned to avoid triggering sanitizer error due to unaligned access
            long alignmentDiff = offset % Long.BYTES;
            long alignedOffset = alignmentDiff == 0 ? offset : offset + Long.BYTES - alignmentDiff;

            var memorySize = MemorySize.fromClass(long.class);

            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, alignedOffset, memorySize),
                "Bad array access at offset " + alignedOffset + ", size " + memorySize.getBytesCount() + "; exceeds end offset " + (ARRAY_BYTE_BASE_OFFSET + a.length * ARRAY_BYTE_INDEX_SCALE)
            );
        }

        {
            var a = new byte[60];
            // Trying to access Object from primitive array
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, ARRAY_BYTE_BASE_OFFSET, MemorySize.OBJECT),
                "Using Object in the context of bytes"
            );

            // Only permitted if error throwing is disabled
            UnsafeSanitizerImpl.setErrorAction(AgentErrorAction.NONE);
            try {
                ArrayAccessSanitizer.onAccess(a, ARRAY_BYTE_BASE_OFFSET, MemorySize.OBJECT);
                BadMemoryAccessError error = UnsafeSanitizerImpl.getLastErrorRefImpl().getAndSet(null);
                assertEquals("Using Object in the context of bytes", error.getMessage());
            } finally {
                UnsafeSanitizerImpl.setErrorAction(AgentErrorAction.THROW);
            }
        }

        {
            var a = new Object[1];
            // Trying to access primitive from Object array
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, ARRAY_OBJECT_BASE_OFFSET, MemorySize.BYTE_1),
                "Bad request for BYTE_1 from java.lang.Object[]"
            );
        }

        {
            var a = new long[2];
            // Not aligned by `long[]` scale
            long offset = ARRAY_LONG_BASE_OFFSET + ARRAY_BYTE_INDEX_SCALE;
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, offset, MemorySize.BYTE_1),
                "Bad aligned array access at offset " + offset + " for long[]"
            );
        }
    }

    @Test
    void onAccessBytesCount() {
        Object a = new byte[4];
        assertTrue(ArrayAccessSanitizer.onAccess(a, ARRAY_BYTE_BASE_OFFSET, 0));
        assertTrue(ArrayAccessSanitizer.onAccess(a, ARRAY_BYTE_BASE_OFFSET, 1));
        assertTrue(ArrayAccessSanitizer.onAccess(a, ARRAY_BYTE_BASE_OFFSET, 2));
        assertTrue(ArrayAccessSanitizer.onAccess(a, ARRAY_BYTE_BASE_OFFSET + ARRAY_BYTE_INDEX_SCALE * 2L, 2));

        a = new long[2];
        assertTrue(ArrayAccessSanitizer.onAccess(a, ARRAY_LONG_BASE_OFFSET, 16));
        assertTrue(ArrayAccessSanitizer.onAccess(a, ARRAY_LONG_BASE_OFFSET + ARRAY_LONG_INDEX_SCALE, 8));
        // TODO: Is this allowed? Reading 2 bytes from array where each element has 8 bytes
        assertTrue(ArrayAccessSanitizer.onAccess(a, ARRAY_LONG_BASE_OFFSET + ARRAY_LONG_INDEX_SCALE, 2));
    }

    @Test
    void onAccessBytesCount_Error() {
        {
            var a = new byte[0];
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, ARRAY_BYTE_BASE_OFFSET, 1),
                "Bad array access at offset " + ARRAY_BYTE_BASE_OFFSET + ", size 1; exceeds end offset " + ARRAY_BYTE_BASE_OFFSET
            );
        }
        {
            var a = new byte[0];
            long offset = ARRAY_BYTE_BASE_OFFSET + ARRAY_BYTE_INDEX_SCALE;
            // Should also validate when trying to access 0 bytes
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, offset, 0),
                "Bad array access at offset " + offset + ", size 0; exceeds end offset " + ARRAY_BYTE_BASE_OFFSET
            );
        }
        {
            var a = new byte[1];
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, ARRAY_BYTE_BASE_OFFSET, 2),
                "Bad array access at offset " + ARRAY_BYTE_BASE_OFFSET + ", size 2; exceeds end offset " + (ARRAY_BYTE_BASE_OFFSET + a.length * ARRAY_BYTE_INDEX_SCALE)
            );
        }
        {
            var a = new byte[2];
            long offset = ARRAY_BYTE_BASE_OFFSET + ARRAY_BYTE_INDEX_SCALE;
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, offset, 2),
                "Bad array access at offset " + offset + ", size 2; exceeds end offset " + (ARRAY_BYTE_BASE_OFFSET + a.length * ARRAY_BYTE_INDEX_SCALE)
            );
        }

        {
            var a = new byte[1];
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, -1, 1),
                "Invalid offset: -1"
            );
        }
        {
            var a = new byte[1];
            // Not using base offset, assuming it is > 0
            assert ARRAY_BYTE_BASE_OFFSET > 0;
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, 0, 1),
                "Bad array access at offset 0; min offset is " + ARRAY_BYTE_BASE_OFFSET
            );
        }
        {
            var a = new byte[1];
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, ARRAY_BYTE_BASE_OFFSET, -1),
                "Invalid size: -1"
            );
        }

        {
            var a = new long[2];
            // Not aligned by `long[]` scale
            long offset = ARRAY_LONG_BASE_OFFSET + ARRAY_BYTE_INDEX_SCALE;
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, offset, 1),
                "Bad aligned array access at offset " + offset + " for long[]"
            );
        }

        {
            var a = new Object[1];
            // Trying to access bytes from `Object[]`
            assertThrows(
                () -> ArrayAccessSanitizer.onAccess(a, ARRAY_OBJECT_BASE_OFFSET, 1),
                "Reading bytes from non-primitive array java.lang.Object[]"
            );
        }
    }
}