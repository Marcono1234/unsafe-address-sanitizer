package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.UnsafeSanitizer.AgentSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static marcono1234.unsafe_sanitizer.MemoryHelper.freeMemory;
import static marcono1234.unsafe_sanitizer.TestSupport.*;
import static marcono1234.unsafe_sanitizer.UnsafeAccess.unsafe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectByteBufferTest {
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

    private static long getAddress(ByteBuffer buffer) {
        assertTrue(buffer.isDirect());
        Field addressField;
        try {
            addressField = Buffer.class.getDeclaredField("address");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

        return assertNoBadMemoryAccessGet(() -> unsafe.getLong(buffer, unsafe.objectFieldOffset(addressField)));
    }

    private static void assertBadMemoryAccess(ThrowingRunnable runnable, String expectedMessage) {
        var e = TestSupport.assertBadMemoryAccess(runnable);
        assertEquals(expectedMessage, e.getMessage());
    }

    private final int bytesCount = 10;
    private ByteBuffer buffer;
    private long address;

    @BeforeEach
    void setUpBuffer() {
        buffer = assertNoBadMemoryAccessGet(() -> ByteBuffer.allocateDirect(bytesCount));
        // Match byte order of Unsafe
        buffer.order(ByteOrder.nativeOrder());
        address = getAddress(buffer);
    }

    @AfterEach
    void clearBuffer() {
        // Verify that no double free occurs
        // This also makes sure there is still a strong reference to the buffer; otherwise might have to
        // use `Reference.reachabilityFence` to prevent garbage collection of the buffer within the tests
        assertNoBadMemoryAccess(() -> unsafe.invokeCleaner(buffer));

        var lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            throw new AssertionError("Unexpected error", lastError);
        }
        TestSupport.checkAllNativeMemoryFreedAndForget();
    }

    @Test
    void readAccess() {
        assertNoBadMemoryAccess(() -> {
            assertEquals(0, unsafe.getByte(address));
            assertEquals(0, unsafe.getInt(address));
            assertEquals(0, unsafe.getLong(address));
            assertEquals(0, unsafe.getLong(address + bytesCount - Long.BYTES));

            buffer.put(0, (byte) 1);
            assertEquals(1, unsafe.getByte(address));

            buffer.putInt(0, 0x12345678);
            assertEquals(0x12345678, unsafe.getInt(address));

            buffer.putLong(0, 0x00_01_02_03_04_05_06_07L);
            assertEquals(0x00_01_02_03_04_05_06_07L, unsafe.getLong(address));
            buffer.putLong(2, 0x02_03_04_05_06_07_08_09L);
            assertEquals(0x02_03_04_05_06_07_08_09L, unsafe.getLong(address + 2));
        });
    }

    @Test
    void writeAccess() {
        assertNoBadMemoryAccess(() -> {
            unsafe.putByte(address, (byte) 1);
            assertEquals(1, buffer.get(0));

            unsafe.putInt(address, 0x12345678);
            assertEquals(0x12345678, buffer.getInt(0));

            unsafe.putLong(address, 0x00_01_02_03_04_05_06_07L);
            int charOffset = Long.BYTES;
            unsafe.putChar(address + charOffset, (char) 0x08_09);
            assertEquals(0x00_01_02_03_04_05_06_07L, buffer.getLong(0));
            assertEquals((char) 0x08_09, buffer.getChar(charOffset));
        });
    }

    @Test
    void outOfBoundsAccess() {
        long badAddress = address - 1;
        String expectedMessage = "Access outside of section at " + badAddress;
        assertBadMemoryAccess(
            () -> unsafe.getLong(badAddress),
            expectedMessage
        );
        assertBadMemoryAccess(
            () -> unsafe.putLong(badAddress, 0),
            expectedMessage
        );

        long badAddressEnd = address + bytesCount - (Long.BYTES - 1);
        expectedMessage = "Access outside of section at " + badAddressEnd + ", size " + Long.BYTES
            + " (previous section: " + address + ", size " + bytesCount + ")";
        assertBadMemoryAccess(
            () -> unsafe.getLong(badAddressEnd),
            expectedMessage
        );
        assertBadMemoryAccess(
            () -> unsafe.putLong(badAddressEnd, 0),
            expectedMessage
        );
    }

    /**
     * Verify that buffer is considered fully initialized, and can read its content without having
     * to write data first.
     */
    @Test
    void readInitial() {
        for (int i = 0; i < bytesCount; i++) {
            final int iFinal = i;
            byte result = assertNoBadMemoryAccessGet(() -> unsafe.getByte(address + iFinal));
            assertEquals(0, result);
        }
    }

    /**
     * Buffer content is assumed to be fully initialized, so must not copy uninitialized data there.
     */
    @Test
    void copyUninitialized() {
        long uninitializedAddress = assertNoBadMemoryAccessGet(() -> unsafe.allocateMemory(bytesCount));
        assertBadMemoryAccess(
            () -> unsafe.copyMemory(uninitializedAddress, address, bytesCount),
            "Trying to copy uninitialized data from " + uninitializedAddress + ", size 10"
        );
        // Clean up
        freeMemory(uninitializedAddress);
    }

    @Test
    void doubleFree() {
        buffer.put((byte) 123);

        assertBadMemoryAccess(
            () -> unsafe.freeMemory(address),
            "Trying to manually free memory of direct ByteBuffer at address " + address
        );
        assertBadMemoryAccess(
            () -> unsafe.reallocateMemory(address, 10),
            "Trying to reallocate memory of direct ByteBuffer at address " + address
        );

        assertNoBadMemoryAccess(() -> {
            assertEquals(123, unsafe.getByte(address));
            unsafe.invokeCleaner(buffer);
        });

        assertNoBadMemoryAccess(() -> {
            // Cleaner is implemented by JDK to only free memory once
            unsafe.invokeCleaner(buffer);
        });
    }
}
