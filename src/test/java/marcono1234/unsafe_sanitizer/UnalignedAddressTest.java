package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.agent_impl.UnsafeSanitizerImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static marcono1234.unsafe_sanitizer.MemoryHelper.allocateMemory;
import static marcono1234.unsafe_sanitizer.MemoryHelper.freeMemory;
import static marcono1234.unsafe_sanitizer.TestSupport.assertBadMemoryAccess;
import static marcono1234.unsafe_sanitizer.TestSupport.assertNoBadMemoryAccess;
import static marcono1234.unsafe_sanitizer.UnsafeAccess.unsafe;
import static org.junit.jupiter.api.Assertions.*;

class UnalignedAddressTest {
    @BeforeAll
    static void installAgent() {
        UnsafeSanitizer.installAgent(UnsafeSanitizer.AgentSettings.defaultSettings());
        UnsafeSanitizer.setErrorAction(ErrorAction.THROW);
        // This mainly prevents spurious errors in case any other test failed to free memory
        TestSupport.checkAllNativeMemoryFreedAndForget();
    }

    @AfterEach
    void checkNoError() {
        var lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            fail("Unexpected error", lastError);
        }
        TestSupport.checkAllNativeMemoryFreedAndForget();
    }
    
    private static long alignAddress(long address, int alignment) {
        long diff = address % alignment;
        if (diff == 0) {
            return address;
        }
        return address + alignment - diff;
    }

    @Test
    void aligned_array() {
        // test byte[] access
        assertNoBadMemoryAccess(() -> {
            var array = new byte[Long.BYTES * 3]; // make array large enough to account for alignment, and offset in test
            var offset = alignAddress(Unsafe.ARRAY_BYTE_BASE_OFFSET, Long.BYTES);

            unsafe.putByte(array, offset, (byte) 1);
            assertEquals((byte) 1, unsafe.getByte(array, offset));

            unsafe.putShort(array, offset, (short) 2);
            assertEquals((short) 2, unsafe.getShort(array, offset));

            unsafe.putShort(array, offset + Short.BYTES, (short) 3);
            assertEquals((short) 3, unsafe.getShort(array, offset + Short.BYTES));

            unsafe.putInt(array, offset, 4);
            assertEquals(4, unsafe.getInt(array, offset));

            unsafe.putInt(array, offset + Integer.BYTES, 5);
            assertEquals(5, unsafe.getInt(array, offset + Integer.BYTES));

            unsafe.putLong(array, offset, 6);
            assertEquals(6, unsafe.getLong(array, offset));

            unsafe.putLong(array, offset + Long.BYTES, 7);
            assertEquals(7, unsafe.getLong(array, offset + Long.BYTES));
        });

        // test int[] access
        assertNoBadMemoryAccess(() -> {
            var array = new int[Long.BYTES * 3]; // make array large enough to account for alignment, and offset in test
            long offset = Unsafe.ARRAY_INT_BASE_OFFSET;
            assertEquals(0, offset % Integer.BYTES, "offset should already be aligned for int");
            offset = alignAddress(offset, Long.BYTES);

            unsafe.putByte(array, offset, (byte) 1);
            assertEquals((byte) 1, unsafe.getByte(array, offset));

            unsafe.putShort(array, offset, (short) 2);
            assertEquals((short) 2, unsafe.getShort(array, offset));

            unsafe.putInt(array, offset, 4);
            assertEquals(4, unsafe.getInt(array, offset));

            unsafe.putInt(array, offset + Integer.BYTES, 5);
            assertEquals(5, unsafe.getInt(array, offset + Integer.BYTES));

            unsafe.putLong(array, offset, 6);
            assertEquals(6, unsafe.getLong(array, offset));

            unsafe.putLong(array, offset + Long.BYTES, 7);
            assertEquals(7, unsafe.getLong(array, offset + Long.BYTES));
        });

        // test long[] access
        assertNoBadMemoryAccess(() -> {
            var array = new long[2];
            var offset = Unsafe.ARRAY_LONG_BASE_OFFSET;
            assertEquals(0, offset % Long.BYTES, "offset should already be aligned for long");

            unsafe.putByte(array, offset, (byte) 1);
            assertEquals((byte) 1, unsafe.getByte(array, offset));

            unsafe.putShort(array, offset, (short) 2);
            assertEquals((short) 2, unsafe.getShort(array, offset));

            unsafe.putInt(array, offset, 4);
            assertEquals(4, unsafe.getInt(array, offset));

            unsafe.putLong(array, offset, 6);
            assertEquals(6, unsafe.getLong(array, offset));

            unsafe.putLong(array, offset + Long.BYTES, 7);
            assertEquals(7, unsafe.getLong(array, offset + Long.BYTES));
        });
        
        // copyMemory and setMemory should work regardless of alignment
        assertNoBadMemoryAccess(() -> {
            var array = new byte[] {1, 2, 3};
            var baseOffset = Unsafe.ARRAY_BYTE_BASE_OFFSET;
            
            var dest = new byte[array.length];
            unsafe.copyMemory(array, baseOffset, dest, baseOffset, array.length);
            assertArrayEquals(new byte[] {1, 2, 3}, dest);

            unsafe.copyMemory(array, baseOffset, dest, baseOffset + 1, array.length - 1);
            assertArrayEquals(new byte[] {1, 1, 2}, dest);

            unsafe.putByte(array, baseOffset + 1, (byte) 3);
            unsafe.copyMemory(array, baseOffset + 1, dest, baseOffset, array.length - 1);
            assertArrayEquals(new byte[] {3, 3, 2}, dest);
            
            
            unsafe.setMemory(array, baseOffset, array.length, (byte) 4);
            assertArrayEquals(new byte[] {4, 4, 4}, array);

            unsafe.setMemory(array, baseOffset + 1, array.length - 1, (byte) 5);
            assertArrayEquals(new byte[] {4, 5, 5}, array);
        });
    }

    @Test
    void unaligned_array() {
        // test byte[] access
        {
            var array = new byte[Long.BYTES * 3]; // make array large enough to account for alignment, and offset in test
            var unalignedOffset = alignAddress(Unsafe.ARRAY_BYTE_BASE_OFFSET, Long.BYTES) + 1;

            // short
            var e = assertBadMemoryAccess(() -> unsafe.putShort(array, unalignedOffset, (short) 1));
            assertEquals("Address " + unalignedOffset + " is not aligned by 2", e.getMessage());

            e = assertBadMemoryAccess(() -> unsafe.getShort(array, unalignedOffset));
            assertEquals("Address " + unalignedOffset + " is not aligned by 2", e.getMessage());

            // int
            for (int i = 0; i < Integer.BYTES - 1; i++) {
                long offset = unalignedOffset + i;

                e = assertBadMemoryAccess(() -> unsafe.putInt(array, offset, 1));
                assertEquals("Address " + offset + " is not aligned by 4", e.getMessage());

                e = assertBadMemoryAccess(() -> unsafe.getInt(array, offset));
                assertEquals("Address " + offset + " is not aligned by 4", e.getMessage());
            }

            // long
            for (int i = 0; i < Long.BYTES - 1; i++) {
                long offset = unalignedOffset + i;

                e = assertBadMemoryAccess(() -> unsafe.putLong(array, offset, 1));
                assertEquals("Address " + offset + " is not aligned by 8", e.getMessage());

                e = assertBadMemoryAccess(() -> unsafe.getLong(array, offset));
                assertEquals("Address " + offset + " is not aligned by 8", e.getMessage());
            }
        }

        // test int[] access
        {
            var array = new int[Long.BYTES * 3]; // make array large enough to account for alignment, and offset in test
            var alignedOffset = alignAddress(Unsafe.ARRAY_INT_BASE_OFFSET, Long.BYTES);
            var unalignedOffset = alignedOffset + 1;

            var e = assertBadMemoryAccess(() -> unsafe.putLong(array, unalignedOffset, 1));
            assertEquals("Address " + unalignedOffset + " is not aligned by 8", e.getMessage());

            e = assertBadMemoryAccess(() -> unsafe.getLong(array, unalignedOffset));
            assertEquals("Address " + unalignedOffset + " is not aligned by 8", e.getMessage());
        }
    }

    // TODO: Guard this test with a check if the current platform supports unaligned access, to avoid a crash?
    @Test
    void unaligned_array_notChecked() {
        var array = new byte[Long.BYTES * 2];
        var unalignedOffset = alignAddress(Unsafe.ARRAY_BYTE_BASE_OFFSET, Long.BYTES) + 1;

        // Verify that by default unaligned access is checked
        var e = assertBadMemoryAccess(() -> unsafe.putLong(array, unalignedOffset, 1));
        assertEquals("Address " + unalignedOffset + " is not aligned by 8", e.getMessage());

        UnsafeSanitizerImpl.setCheckAddressAlignment(false);
        try {
            assertNoBadMemoryAccess(() -> unsafe.putLong(array, unalignedOffset, 1));
        } finally {
            UnsafeSanitizerImpl.setCheckAddressAlignment(true);
        }
    }

    @Test
    void aligned_native() {
        assertNoBadMemoryAccess(() -> {
            var address = allocateMemory(Long.BYTES * 2);
            
            unsafe.putByte(address, (byte) 1);
            assertEquals((byte) 1, unsafe.getByte(address));

            unsafe.putShort(address, (short) 2);
            assertEquals((short) 2, unsafe.getShort(address));

            unsafe.putShort(address + Short.BYTES, (short) 3);
            assertEquals((short) 3, unsafe.getShort(address + Short.BYTES));

            unsafe.putInt(address, 4);
            assertEquals(4, unsafe.getInt(address));

            unsafe.putInt(address + Integer.BYTES, 5);
            assertEquals(5, unsafe.getInt(address + Integer.BYTES));

            unsafe.putLong(address, 6);
            assertEquals(6, unsafe.getLong(address));

            unsafe.putLong(address + Long.BYTES, 7);
            assertEquals(7, unsafe.getLong(address + Long.BYTES));

            freeMemory(address);
        });

        assertNoBadMemoryAccess(() -> {
            var address = allocateMemory(Unsafe.ADDRESS_SIZE * 2L);
            var addressValue = address + 1;  // arbitrary address
            
            unsafe.putAddress(address, addressValue);
            assertEquals(addressValue, unsafe.getAddress(address));

            unsafe.putAddress(address + Unsafe.ADDRESS_SIZE, addressValue);
            assertEquals(addressValue, unsafe.getAddress(address + Unsafe.ADDRESS_SIZE));

            freeMemory(address);
        });
            
        
        // copyMemory and setMemory should work regardless of alignment
        assertNoBadMemoryAccess(() -> {
            var length = 3;
            var address = allocateMemory(length);

            unsafe.setMemory(address, length, (byte) 1);
            assertEquals((byte) 1, unsafe.getByte(address));
            assertEquals((byte) 1, unsafe.getByte(address + 1));
            assertEquals((byte) 1, unsafe.getByte(address + 2));
            
            unsafe.setMemory(address + 1, length - 1, (byte) 2);
            assertEquals((byte) 1, unsafe.getByte(address));
            assertEquals((byte) 2, unsafe.getByte(address + 1));
            assertEquals((byte) 2, unsafe.getByte(address + 2));
            
            
            unsafe.copyMemory(address, address + 1, length - 1);
            assertEquals((byte) 1, unsafe.getByte(address));
            assertEquals((byte) 1, unsafe.getByte(address + 1));
            assertEquals((byte) 2, unsafe.getByte(address + 2));
            
            
            unsafe.putByte(address + 1, (byte) 3);
            unsafe.copyMemory(address + 1, address, length - 1);
            assertEquals((byte) 3, unsafe.getByte(address));
            assertEquals((byte) 2, unsafe.getByte(address + 1));
            assertEquals((byte) 2, unsafe.getByte(address + 2));

            freeMemory(address);
        });
    }

    @Test
    void unaligned_native() {
        {
            var alignedAddress = allocateMemory(Long.BYTES * 2L);
            var unalignedAddress = alignedAddress + 1;

            // short
            var e = assertBadMemoryAccess(() -> unsafe.putShort(unalignedAddress, (short) 1));
            assertEquals("Address " + unalignedAddress + " is not aligned by 2", e.getMessage());

            e = assertBadMemoryAccess(() -> unsafe.getShort(unalignedAddress));
            assertEquals("Address " + unalignedAddress + " is not aligned by 2", e.getMessage());

            // int
            for (int i = 0; i < Integer.BYTES - 1; i++) {
                long address = unalignedAddress + i;

                e = assertBadMemoryAccess(() -> unsafe.putInt(address, 1));
                assertEquals("Address " + address + " is not aligned by 4", e.getMessage());

                e = assertBadMemoryAccess(() -> unsafe.getInt(address));
                assertEquals("Address " + address + " is not aligned by 4", e.getMessage());
            }

            // long
            for (int i = 0; i < Long.BYTES - 1; i++) {
                long address = unalignedAddress + i;

                e = assertBadMemoryAccess(() -> unsafe.putLong(address, 1));
                assertEquals("Address " + address + " is not aligned by 8", e.getMessage());

                e = assertBadMemoryAccess(() -> unsafe.getLong(address));
                assertEquals("Address " + address + " is not aligned by 8", e.getMessage());
            }

            freeMemory(alignedAddress);
        }

        {
            var alignedAddress = allocateMemory(Unsafe.ADDRESS_SIZE * 2L);
            var unalignedAddress = alignedAddress + 1;

            for (int i = 0; i < Unsafe.ADDRESS_SIZE - 1; i++) {
                long address = unalignedAddress + i;

                var addressValue = alignedAddress + 1;  // arbitrary address
                var e = assertBadMemoryAccess(() -> unsafe.putAddress(address, addressValue));
                assertEquals("Address " + address + " is not aligned by " + Unsafe.ADDRESS_SIZE, e.getMessage());

                e = assertBadMemoryAccess(() -> unsafe.getAddress(address));
                assertEquals("Address " + address + " is not aligned by " + Unsafe.ADDRESS_SIZE, e.getMessage());
            }

            freeMemory(alignedAddress);
        }
    }

    // TODO: Guard this test with a check if the current platform supports unaligned access, to avoid a crash?
    @Test
    void unaligned_native_notChecked() {
        var alignedAddress = allocateMemory(Long.BYTES * 2L);
        var unalignedAddress = alignedAddress + 1;

        // Verify that by default unaligned access is checked
        var e = assertBadMemoryAccess(() -> unsafe.putLong(unalignedAddress, 1));
        assertEquals("Address " + unalignedAddress + " is not aligned by 8", e.getMessage());

        UnsafeSanitizerImpl.setCheckAddressAlignment(false);
        try {
            assertNoBadMemoryAccess(() -> unsafe.putLong(unalignedAddress, 1));
        } finally {
            UnsafeSanitizerImpl.setCheckAddressAlignment(true);
        }

        freeMemory(alignedAddress);
    }
}
