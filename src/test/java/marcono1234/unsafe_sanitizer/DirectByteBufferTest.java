package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.UnsafeSanitizer.AgentSettings;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static marcono1234.unsafe_sanitizer.MemoryHelper.allocateMemory;
import static marcono1234.unsafe_sanitizer.MemoryHelper.freeMemory;
import static marcono1234.unsafe_sanitizer.TestSupport.*;
import static marcono1234.unsafe_sanitizer.UnsafeAccess.unsafe;
import static org.junit.jupiter.api.Assertions.*;

class DirectByteBufferTest {
    @BeforeAll
    static void installAgent() {
        UnsafeSanitizer.installAgent(AgentSettings.defaultSettings());
        UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.THROW);
        // This mainly prevents spurious errors in case any other test failed to free memory
        TestSupport.checkAllNativeMemoryFreedAndForget();

        // TODO: Instead of disabling alignment check here, adjust tests to only perform aligned access?
        UnsafeSanitizer.modifySettings().setAddressAlignmentChecking(false);
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
    static void restoreAlignmentChecking() {
        // TODO: See `setAddressAlignmentChecking` call above
        UnsafeSanitizer.modifySettings().setAddressAlignmentChecking(true);
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

    private final int bufferSize = 10;
    private ByteBuffer buffer;
    private long bufferAddress;

    @BeforeEach
    void setUpBuffer() {
        buffer = assertNoBadMemoryAccessGet(() -> ByteBuffer.allocateDirect(bufferSize));
        // Match byte order of Unsafe
        buffer.order(ByteOrder.nativeOrder());
        bufferAddress = getAddress(buffer);
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
            assertEquals(0, unsafe.getByte(bufferAddress));
            assertEquals(0, unsafe.getInt(bufferAddress));
            assertEquals(0, unsafe.getLong(bufferAddress));
            assertEquals(0, unsafe.getLong(bufferAddress + bufferSize - Long.BYTES));

            buffer.put(0, (byte) 1);
            assertEquals(1, unsafe.getByte(bufferAddress));

            buffer.putInt(0, 0x12345678);
            assertEquals(0x12345678, unsafe.getInt(bufferAddress));

            buffer.putLong(0, 0x00_01_02_03_04_05_06_07L);
            assertEquals(0x00_01_02_03_04_05_06_07L, unsafe.getLong(bufferAddress));
            buffer.putLong(2, 0x02_03_04_05_06_07_08_09L);
            assertEquals(0x02_03_04_05_06_07_08_09L, unsafe.getLong(bufferAddress + 2));
        });
    }

    @Test
    void writeAccess() {
        assertNoBadMemoryAccess(() -> {
            unsafe.putByte(bufferAddress, (byte) 1);
            assertEquals(1, buffer.get(0));

            unsafe.putInt(bufferAddress, 0x12345678);
            assertEquals(0x12345678, buffer.getInt(0));

            unsafe.putLong(bufferAddress, 0x00_01_02_03_04_05_06_07L);
            int charOffset = Long.BYTES;
            unsafe.putChar(bufferAddress + charOffset, (char) 0x08_09);
            assertEquals(0x00_01_02_03_04_05_06_07L, buffer.getLong(0));
            assertEquals((char) 0x08_09, buffer.getChar(charOffset));
        });
    }

    @Test
    void outOfBoundsAccess() {
        long badAddress = bufferAddress - 1;
        String expectedMessage = "Access outside of section, at address " + badAddress;
        assertBadMemoryAccess(
            () -> unsafe.getLong(badAddress),
            expectedMessage
        );
        assertBadMemoryAccess(
            () -> unsafe.putLong(badAddress, 0),
            expectedMessage
        );

        long badAddressEnd = bufferAddress + bufferSize - (Long.BYTES - 1);
        expectedMessage = "Access outside of section at address " + badAddressEnd + ", size " + Long.BYTES
            + " (previous section: address " + bufferAddress + ", size " + bufferSize + ")";
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
        for (int i = 0; i < bufferSize; i++) {
            long address = bufferAddress + i;
            byte result = assertNoBadMemoryAccessGet(() -> unsafe.getByte(address));
            assertEquals(0, result);
        }
    }

    @Test
    void copyBufferToBuffer() {
        ByteBuffer buffer2 = ByteBuffer.allocateDirect(bufferSize);
        long bufferAddress2 = getAddress(buffer2);

        buffer.put(0, (byte) 1);
        buffer.put(1, (byte) 2);
        assertNoBadMemoryAccess(() -> unsafe.copyMemory(bufferAddress, bufferAddress2, bufferSize));
        assertEquals(1, buffer2.get(0));
        assertEquals(2, buffer2.get(1));

        assertNoBadMemoryAccess(() -> unsafe.invokeCleaner(buffer2));
    }

    @Test
    void copyArrayToBuffer() {
        byte[] array = {1, 2, 3};
        assertNoBadMemoryAccess(() -> unsafe.copyMemory(array, Unsafe.ARRAY_BYTE_BASE_OFFSET, null, bufferAddress, array.length));
        assertEquals(1, buffer.get(0));
        assertEquals(2, buffer.get(1));
        assertEquals(3, buffer.get(2));
    }

    @Test
    void copyBufferToArray() {
        byte[] array = new byte[] {1, 2, 3};
        assertNoBadMemoryAccess(() -> unsafe.copyMemory(null, bufferAddress, array, Unsafe.ARRAY_BYTE_BASE_OFFSET, array.length));
        // Ensure that buffer was considered fully initialized (with 0), and overwrote array content
        assertArrayEquals(new byte[] {0, 0, 0}, array);

        buffer.put(new byte[] {4, 5, 6});
        assertNoBadMemoryAccess(() -> unsafe.copyMemory(null, bufferAddress, array, Unsafe.ARRAY_BYTE_BASE_OFFSET, array.length));
        assertArrayEquals(new byte[] {4, 5, 6}, array);
    }

    @Test
    void copyInitialized() {
        long size = 2;
        long address = allocateMemory(size);
        assertNoBadMemoryAccess(() -> {
            unsafe.putByte(address, (byte) 1);
            unsafe.putByte(address + 1, (byte) 2);
        });

        assertNoBadMemoryAccess(() -> unsafe.copyMemory(address, bufferAddress, size));
        assertEquals(1, buffer.get(0));
        assertEquals(2, buffer.get(1));

        freeMemory(address);
    }

    /**
     * Buffer content is assumed to be fully initialized, so must not copy uninitialized data there.
     */
    @Test
    void copyUninitialized() {
        long uninitializedAddress = assertNoBadMemoryAccessGet(() -> unsafe.allocateMemory(bufferSize));
        assertBadMemoryAccess(
            () -> unsafe.copyMemory(uninitializedAddress, bufferAddress, bufferSize),
            "Trying to copy uninitialized data from address " + uninitializedAddress + ", size 10"
        );
        // Clean up
        freeMemory(uninitializedAddress);
    }

    @Test
    void doubleFree() {
        buffer.put((byte) 123);

        assertBadMemoryAccess(
            () -> unsafe.freeMemory(bufferAddress),
            "Trying to manually free memory of direct ByteBuffer at address " + bufferAddress
        );
        assertBadMemoryAccess(
            () -> unsafe.reallocateMemory(bufferAddress, 10),
            "Trying to reallocate memory of direct ByteBuffer at address " + bufferAddress
        );

        assertNoBadMemoryAccess(() -> {
            assertEquals(123, unsafe.getByte(bufferAddress));
            unsafe.invokeCleaner(buffer);
        });

        assertNoBadMemoryAccess(() -> {
            // Cleaner is implemented by JDK to only free memory once
            unsafe.invokeCleaner(buffer);
        });
    }
}
