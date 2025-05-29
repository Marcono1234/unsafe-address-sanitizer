package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.UnsafeSanitizer.AgentSettings;
import marcono1234.unsafe_sanitizer.agent_impl.UnsafeSanitizerImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;

import static marcono1234.unsafe_sanitizer.MemoryHelper.allocateMemory;
import static marcono1234.unsafe_sanitizer.MemoryHelper.freeMemory;
import static marcono1234.unsafe_sanitizer.TestSupport.*;
import static marcono1234.unsafe_sanitizer.UnsafeAccess.unsafe;
import static org.junit.jupiter.api.Assertions.*;
import static sun.misc.Unsafe.*;

// TODO: Check error message of `assertBadMemoryAccess`?

/**
 * Tests using {@link sun.misc.Unsafe} with {@link ErrorAction#THROW}.
 */
class UnsafeThrowErrorTest {
    @BeforeAll
    static void installAgent() {
        UnsafeSanitizer.installAgent(AgentSettings.defaultSettings());
        UnsafeSanitizer.setErrorAction(ErrorAction.THROW);
        // This mainly prevents spurious errors in case any other test failed to free memory
        TestSupport.checkAllNativeMemoryFreedAndForget();

        // TODO: Instead of disabling alignment check here, adjust tests to only perform aligned access?
        UnsafeSanitizerImpl.setCheckAddressAlignment(false);
    }

    @AfterEach
    void checkNoError() {
        var lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            fail("Unexpected error", lastError);
        }
        TestSupport.checkAllNativeMemoryFreedAndForget();
    }

    @AfterAll
    static void restoreAlignmentChecking() {
        // TODO: See `setCheckAddressAlignment` call above
        UnsafeSanitizerImpl.setCheckAddressAlignment(true);
    }

    /** Reallocates memory, not expecting any error */
    private static long reallocateMemory(long address, long bytesCount) {
        return assertNoBadMemoryAccessGet(() -> unsafe.reallocateMemory(address, bytesCount));
    }

    @Test
    void allocate() {
        assertNoBadMemoryAccess(() -> {
            // Size of 0 has no effect
            long a = unsafe.allocateMemory(0);
            assertEquals(0, a);
        });

        assertNoBadMemoryAccess(() -> {
            long a = unsafe.allocateMemory(10);
            unsafe.freeMemory(a);
        });

        assertNoBadMemoryAccess(() -> {
            long a = unsafe.allocateMemory(10);
            long b = unsafe.allocateMemory(20);
            long c = unsafe.allocateMemory(30);
            unsafe.freeMemory(b);
            unsafe.freeMemory(c);
            unsafe.freeMemory(a);
        });
    }

    @Test
    void allocate_bad() {
        assertBadMemoryAccess(() -> unsafe.allocateMemory(-1));
    }

    @Test
    void free() {
        assertNoBadMemoryAccess(() -> {
            // Free at 0 has no effect
            unsafe.freeMemory(0);
        });
    }

    @Test
    void free_bad() {
        var e = assertBadMemoryAccess(() -> unsafe.freeMemory(-1));
        assertEquals("Invalid address: -1", e.getMessage());

        e = assertBadMemoryAccess(() -> {
            // Assumes that no allocation has been performed at this address
            unsafe.freeMemory(1);
        });
        assertEquals("Cannot free at address 1", e.getMessage());

        {
            long a = allocateMemory(10);
            freeMemory(a);
            e = assertBadMemoryAccess(() -> {
                // Double free
                unsafe.freeMemory(a);
            });
            assertEquals("Cannot free at address " + a, e.getMessage());
        }
    }

    @Test
    void reallocate() {
        assertNoBadMemoryAccess(() -> {
            // Enlarge
            long a = unsafe.reallocateMemory(allocateMemory(10), 20);
            // Can access enlarged memory section now
            unsafe.putLong(a + 12, 1);
            freeMemory(a);
        });

        assertNoBadMemoryAccess(() -> {
            // Shrink
            long a = unsafe.reallocateMemory(allocateMemory(10), 4);
            unsafe.putInt(a, 1);
            // Cannot access past new size (even though it would have been within original size)
            assertBadMemoryAccess(() -> unsafe.putLong(a, 1));
            freeMemory(a);
        });

        assertNoBadMemoryAccess(() -> {
            // Same size
            long a = unsafe.reallocateMemory(allocateMemory(10), 10);
            // Can still access memory
            unsafe.putLong(a + 2, 1);
            freeMemory(a);
        });

        assertNoBadMemoryAccess(() -> {
            // Address 0 acts like `allocateMemory` instead
            long a = unsafe.reallocateMemory(0, 20);
            freeMemory(a);
        });

        assertNoBadMemoryAccess(() -> {
            long a = unsafe.allocateMemory(10);
            // Size of 0 only frees memory
            long b = unsafe.reallocateMemory(a, 0);
            assertEquals(0, b);
        });
    }

    @Test
    void reallocate_bad() {
        assertBadMemoryAccess(() -> unsafe.reallocateMemory(-1, 10));
        assertBadMemoryAccess(() -> {
            // Assumes that no allocation has been performed at this address
            unsafe.reallocateMemory(1, 10);
        });

        {
            long a = allocateMemory(10);
            assertBadMemoryAccess(() -> unsafe.reallocateMemory(a, -10));
            // Clean up
            freeMemory(a);
        }

        {
            long a = allocateMemory(10);
            long oldA = a;
            a = reallocateMemory(a, 20);
            // Only run if memory was reallocated at different address
            if (a != oldA) {
                assertBadMemoryAccess(() -> {
                    // Free moved memory
                    unsafe.freeMemory(oldA);
                });
            }
            // Clean up
            freeMemory(a);
        }

        {
            long a = allocateMemory(10);
            // Size of 0 only frees memory
            long b = reallocateMemory(a, 0);
            assertEquals(0, b);
            assertBadMemoryAccess(() -> {
                // Should fail freeing memory because `reallocateMemory` already freed it
                unsafe.freeMemory(a);
            });
        }
    }

    @Test
    void fields() throws Exception {
        class Dummy {
            int i;
            static int s;

            @Override
            public String toString() {
                return "Dummy";
            }
        }

        long offsetI = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("i"));
        Field fieldS = Dummy.class.getDeclaredField("s");
        Object baseS = unsafe.staticFieldBase(fieldS);
        long offsetS = unsafe.staticFieldOffset(fieldS);

        Dummy dummy = new Dummy();
        assertNoBadMemoryAccess(() -> {
            unsafe.putFloat(dummy, offsetI, 1f);
            unsafe.putInt(dummy, offsetI, 2);

            unsafe.putInt(baseS, offsetS, 3);
        });
        assertEquals(2, dummy.i);
        assertEquals(3, Dummy.s);

        assertBadMemoryAccess(() -> {
            // Trying to store `long` (8 byte) in `int` (4 byte) field
            unsafe.putLong(dummy, offsetI, 1);
        });
        assertBadMemoryAccess(() -> {
            // Trying to store Integer in `int` field
            unsafe.putObject(dummy, offsetI, 1);
        });
        assertBadMemoryAccess(() -> unsafe.putInt(dummy, offsetI + 1, 1));
        assertBadMemoryAccess(() -> unsafe.putInt(dummy, offsetI - 1, 1));
        assertBadMemoryAccess(() -> unsafe.putInt(dummy, -1, 1));
        assertBadMemoryAccess(() -> unsafe.putInt(baseS, -1, 1));

        assertNoBadMemoryAccess(() -> {
            dummy.i = 1;
            boolean success = unsafe.compareAndSwapInt(dummy, offsetI, 1, 2);
            assertTrue(success);
            assertEquals(2, dummy.i);
        });
        var e = assertBadMemoryAccess(() -> unsafe.compareAndSwapLong(dummy, offsetI, 1, 2));
        assertEquals(
            "Field 'int " + Dummy.class.getTypeName() +"#i' at offset 12 of class " + Dummy.class.getTypeName() + " has size BYTE_4, not BYTE_8",
            e.getMessage()
        );

        class DummySub extends Dummy {
        }
        DummySub dummySub = new DummySub();
        assertNoBadMemoryAccess(() -> unsafe.putInt(dummySub, offsetI, 1));
        assertEquals(1, dummySub.i);
    }

    @Test
    void objectField() throws Exception {
        class Dummy {
            Number n;

            @Override
            public String toString() {
                return "Dummy";
            }
        }
        Field field = Dummy.class.getDeclaredField("n");
        long offset = unsafe.objectFieldOffset(field);
        Dummy dummy = new Dummy();

        assertNoBadMemoryAccess(() -> {
            unsafe.putObject(dummy, offset, BigInteger.valueOf(1));
            assertEquals(BigInteger.valueOf(1), unsafe.getObject(dummy, offset));
        });
        assertEquals(BigInteger.valueOf(1), dummy.n);

        assertNoBadMemoryAccess(() -> {
            Object oldValue = unsafe.getAndSetObject(dummy, offset, BigInteger.valueOf(2));
            assertEquals(BigInteger.valueOf(1), oldValue);
        });
        assertEquals(BigInteger.valueOf(2), dummy.n);

        assertNoBadMemoryAccess(() -> {
            boolean success = unsafe.compareAndSwapObject(dummy, offset, BigInteger.valueOf(2), BigInteger.valueOf(3));
            assertTrue(success);
            assertEquals(BigInteger.valueOf(3), dummy.n);

            // Value is now 3, and not the expected 2
            success = unsafe.compareAndSwapObject(dummy, offset, BigInteger.valueOf(2), BigInteger.valueOf(4));
            assertFalse(success);
            // Still has old value
            assertEquals(BigInteger.valueOf(3), dummy.n);
        });

        assertNoBadMemoryAccess(() -> {
            unsafe.putObject(dummy, offset, null);
            assertNull(unsafe.getObject(dummy, offset));
        });
        assertNull(dummy.n);

        var e = assertBadMemoryAccess(() -> unsafe.putObject(dummy, offset, "test"));
        String expectedMessage = "Trying to write class java.lang.String to field 'java.lang.Number " + Dummy.class.getTypeName() + "#n'";
        assertEquals(expectedMessage, e.getMessage());

        e = assertBadMemoryAccess(() -> unsafe.getAndSetObject(dummy, offset, "test"));
        assertEquals(expectedMessage, e.getMessage());

        e = assertBadMemoryAccess(() -> unsafe.compareAndSwapObject(dummy, offset, Boolean.TRUE, "test"));
        assertEquals(expectedMessage, e.getMessage());
    }

    @Test
    void putAddress() {
        long a = allocateMemory(ADDRESS_SIZE);

        assertNoBadMemoryAccess(() -> {
            unsafe.putAddress(a, 0);
            assertEquals(0, unsafe.getAddress(a));
            unsafe.putAddress(a, a);
            assertEquals(a, unsafe.getAddress(a));
        });

        var e = assertBadMemoryAccess(() -> unsafe.putAddress(-1, 0));
        assertEquals("Invalid address: -1", e.getMessage());

        e = assertBadMemoryAccess(() -> unsafe.putAddress(0, 0));
        assertEquals("Invalid address: 0", e.getMessage());

        e = assertBadMemoryAccess(() -> {
            // Assumes that no allocation has been performed at this address
            unsafe.putAddress(1, 0);
        });
        assertEquals("Access outside of section at 1", e.getMessage());

        e = assertBadMemoryAccess(() -> unsafe.putAddress(a, -1));
        assertEquals("Invalid address value: -1", e.getMessage());

        // Disable alignment check to make sure section bounds check is reached
        UnsafeSanitizerImpl.runWithoutAddressAlignmentCheck(() -> {
            var e2 = assertBadMemoryAccess(() -> {
                // Exceeds section
                unsafe.putAddress(a + 1, 0);
            });
            assertEquals(
                "Access outside of section at " + (a + 1) + ", size " + ADDRESS_SIZE + " (previous section: " + a + ", size " + ADDRESS_SIZE + ")",
                e2.getMessage()
            );
        });

        // Clean up
        freeMemory(a);
    }

    @Test
    void array() {
        byte[] b = {1, 2, 3};
        assertNoBadMemoryAccess(() -> {
            assertEquals(1, unsafe.getByte(b, ARRAY_BYTE_BASE_OFFSET));

            long offset = ARRAY_BYTE_BASE_OFFSET + ARRAY_BYTE_INDEX_SCALE * 2L;
            assertEquals(3, unsafe.getByte(b, offset));
            unsafe.putByte(b, offset, (byte) 4);
            assertEquals(4, unsafe.getByte(b, offset));
        });

        assertBadMemoryAccess(() -> unsafe.getByte(b, 0));
        assertBadMemoryAccess(() -> {
            long offset = ARRAY_BYTE_BASE_OFFSET + (long) ARRAY_BYTE_INDEX_SCALE * b.length;
            unsafe.getByte(b, offset);
        });
        assertBadMemoryAccess(() -> unsafe.getByte(new byte[0], ARRAY_BYTE_BASE_OFFSET));
        assertBadMemoryAccess(() -> {
            // Array contains only 3 bytes, `long` is 8 bytes
            unsafe.getLong(b, ARRAY_BYTE_BASE_OFFSET);
        });
        assertBadMemoryAccess(() -> unsafe.getObject(b, ARRAY_BYTE_BASE_OFFSET));
        assertBadMemoryAccess(() -> {
            // Storing boxed Byte in array should not be allowed
            unsafe.putObject(b, ARRAY_BYTE_BASE_OFFSET, (byte) 1);
        });

        // Disable address alignment check to make sure index alignment check is reached
        UnsafeSanitizerImpl.runWithoutAddressAlignmentCheck(() -> {
            long[] array = {1, 2, 3};
            assert ARRAY_LONG_INDEX_SCALE > 1;
            long unalignedOffset = ARRAY_LONG_BASE_OFFSET + 1;

            var e = assertBadMemoryAccess(() -> unsafe.putLong(array, unalignedOffset, 1));
            assertEquals("Bad aligned array access at offset " + unalignedOffset + " for long[]", e.getMessage());

            e = assertBadMemoryAccess(() -> unsafe.getLong(array, unalignedOffset));
            assertEquals("Bad aligned array access at offset " + unalignedOffset + " for long[]", e.getMessage());
        });
    }

    @Test
    void objectArray() {
        Number[] o = {BigInteger.valueOf(1), BigDecimal.valueOf(2)};
        assertNoBadMemoryAccess(() -> {
            assertEquals(BigInteger.valueOf(1), unsafe.getObject(o, ARRAY_OBJECT_BASE_OFFSET));

            long offset = ARRAY_OBJECT_BASE_OFFSET + ARRAY_OBJECT_INDEX_SCALE;
            assertEquals(BigDecimal.valueOf(2), unsafe.getObject(o, offset));

            unsafe.putObject(o, offset, BigDecimal.valueOf(3));
            assertEquals(BigDecimal.valueOf(3), unsafe.getObject(o, offset));
            assertArrayEquals(new Number[] {BigInteger.valueOf(1), BigDecimal.valueOf(3)}, o);

            unsafe.putObject(o, offset, null);
            assertNull(unsafe.getObject(o, offset));
            assertArrayEquals(new Number[] {BigInteger.valueOf(1), null}, o);
        });

        var e = assertBadMemoryAccess(() -> unsafe.putInt(o, ARRAY_OBJECT_BASE_OFFSET, 1));
        assertEquals("Bad request for BYTE_4 from java.lang.Number[]", e.getMessage());

        e = assertBadMemoryAccess(() -> unsafe.putObject(o, ARRAY_OBJECT_BASE_OFFSET, "test"));
        assertEquals("Trying to write java.lang.String to java.lang.Number array", e.getMessage());

        long arrayEndOffset = ARRAY_OBJECT_BASE_OFFSET + (long) o.length * ARRAY_OBJECT_INDEX_SCALE;
        e = assertBadMemoryAccess(() -> unsafe.putObject(o, arrayEndOffset, BigDecimal.valueOf(1)));
        assertEquals(
            "Bad array access at offset " + arrayEndOffset + ", size " + ARRAY_OBJECT_INDEX_SCALE + "; max offset is " + arrayEndOffset,
            e.getMessage()
        );

        // Test unaligned element access; assumes that index scale is larger than 1 byte
        assert ARRAY_OBJECT_INDEX_SCALE > 1;
        // Disable address alignment check to make sure index alignment check is reached
        UnsafeSanitizerImpl.runWithoutAddressAlignmentCheck(() -> {
            long unalignedOffset = ARRAY_OBJECT_BASE_OFFSET + 1;
            var e2 = assertBadMemoryAccess(() -> unsafe.putObject(o, unalignedOffset, BigDecimal.valueOf(1)));
            assertEquals("Bad aligned array access at offset " + unalignedOffset + " for java.lang.Number[]", e2.getMessage());

            e2 = assertBadMemoryAccess(() -> unsafe.getObject(o, ARRAY_OBJECT_BASE_OFFSET + 1));
            assertEquals("Bad aligned array access at offset " + unalignedOffset + " for java.lang.Number[]", e2.getMessage());
        });
    }

    @Test
    void compareAndSwapInt() {
        long a = allocateMemory(Integer.BYTES);
        assertNoBadMemoryAccess(() -> {
            unsafe.putInt(a, 0);
            boolean success = unsafe.compareAndSwapInt(null, a, 0, 1);
            assertTrue(success);
            assertEquals(1, unsafe.getInt(a));

            success = unsafe.compareAndSwapInt(null, a, 0, 1);
            assertFalse(success);
        });

        var e = assertBadMemoryAccess(() -> unsafe.compareAndSwapInt(null, a + 1, 0, 1));
        assertEquals(
            "Access outside of section at " + (a + 1) + ", size 4 (previous section: " + a + ", size 4)",
            e.getMessage()
        );

        // Clean up
        freeMemory(a);
    }

    @Test
    void compareAndSwapLong() {
        long a = allocateMemory(Long.BYTES);
        assertNoBadMemoryAccess(() -> {
            unsafe.putLong(a, 0);
            boolean success = unsafe.compareAndSwapLong(null, a, 0, 1);
            assertTrue(success);
            assertEquals(1, unsafe.getLong(a));

            success = unsafe.compareAndSwapLong(null, a, 0, 1);
            assertFalse(success);
        });

        var e = assertBadMemoryAccess(() -> unsafe.compareAndSwapLong(null, a + 1, 0, 1));
        assertEquals(
            "Access outside of section at " + (a + 1) + ", size 8 (previous section: " + a + ", size 8)",
            e.getMessage()
        );

        // Clean up
        freeMemory(a);
    }

    @Test
    void getAndAddInt() {
        long a = allocateMemory(Integer.BYTES);
        assertNoBadMemoryAccess(() -> {
            unsafe.putInt(a, 1);
            assertEquals(1, unsafe.getAndAddInt(null, a, 5));
            assertEquals(6, unsafe.getInt(a));

            assertEquals(6, unsafe.getAndAddInt(null, a, -2));
            assertEquals(4, unsafe.getInt(a));

            assertEquals(4, unsafe.getAndAddInt(null, a, 0));
            assertEquals(4, unsafe.getInt(a));
        });

        var e = assertBadMemoryAccess(() -> unsafe.getAndAddInt(null, a + 1, 1));
        assertEquals(
            "Access outside of section at " + (a + 1) + ", size 4 (previous section: " + a + ", size 4)",
            e.getMessage()
        );

        // Clean up
        freeMemory(a);
    }

    @Test
    void getAndAddLong() {
        long a = allocateMemory(Long.BYTES);
        assertNoBadMemoryAccess(() -> {
            unsafe.putLong(a, 1);
            assertEquals(1, unsafe.getAndAddLong(null, a, 5));
            assertEquals(6, unsafe.getLong(a));

            assertEquals(6, unsafe.getAndAddLong(null, a, -2));
            assertEquals(4, unsafe.getLong(a));

            assertEquals(4, unsafe.getAndAddLong(null, a, 0));
            assertEquals(4, unsafe.getLong(a));
        });

        var e = assertBadMemoryAccess(() -> unsafe.getAndAddLong(null, a + 1, 1));
        assertEquals(
            "Access outside of section at " + (a + 1) + ", size 8 (previous section: " + a + ", size 8)",
            e.getMessage()
        );

        // Clean up
        freeMemory(a);
    }

    @Test
    void getAndSetInt() {
        long a = allocateMemory(Integer.BYTES);
        assertNoBadMemoryAccess(() -> {
            unsafe.putInt(a, 1);
            assertEquals(1, unsafe.getAndSetInt(null, a, 5));
            assertEquals(5, unsafe.getInt(a));

            assertEquals(5, unsafe.getAndSetInt(null, a, -2));
            assertEquals(-2, unsafe.getInt(a));

            assertEquals(-2, unsafe.getAndSetInt(null, a, 0));
            assertEquals(0, unsafe.getInt(a));
        });

        var e = assertBadMemoryAccess(() -> unsafe.getAndSetInt(null, a + 1, 1));
        assertEquals(
            "Access outside of section at " + (a + 1) + ", size 4 (previous section: " + a + ", size 4)",
            e.getMessage()
        );

        // Clean up
        freeMemory(a);
    }

    @Test
    void getAndSetLong() {
        long a = allocateMemory(Long.BYTES);
        assertNoBadMemoryAccess(() -> {
            unsafe.putLong(a, 1);
            assertEquals(1, unsafe.getAndSetLong(null, a, 5));
            assertEquals(5, unsafe.getLong(a));

            assertEquals(5, unsafe.getAndSetLong(null, a, -2));
            assertEquals(-2, unsafe.getLong(a));

            assertEquals(-2, unsafe.getAndSetLong(null, a, 0));
            assertEquals(0, unsafe.getLong(a));
        });

        var e = assertBadMemoryAccess(() -> unsafe.getAndSetLong(null, a + 1, 1));
        assertEquals(
            "Access outside of section at " + (a + 1) + ", size 8 (previous section: " + a + ", size 8)",
            e.getMessage()
        );

        // Clean up
        freeMemory(a);
    }

    @SuppressWarnings("UnnecessaryLocalVariable")
    @Test
    void copyMemoryArray() {
        assertNoBadMemoryAccess(() -> {
            byte[] b = {1, 2, 3, 4};
            long from = ARRAY_BYTE_BASE_OFFSET;
            long to = ARRAY_BYTE_BASE_OFFSET + ARRAY_BYTE_INDEX_SCALE * 2L;
            unsafe.copyMemory(b, from, b, to, 2);
            assertArrayEquals(new byte[] {1, 2, 1, 2}, b);
        });
        assertNoBadMemoryAccess(() -> {
            long[] a = {1, 2, 3, 4};
            long from = ARRAY_LONG_BASE_OFFSET;
            long to = ARRAY_LONG_BASE_OFFSET + ARRAY_LONG_INDEX_SCALE * 2L;
            unsafe.copyMemory(a, from, a, to, 2 * Long.BYTES);
            assertArrayEquals(new long[] {1, 2, 1, 2}, a);
        });

        assertNoBadMemoryAccess(() -> {
            byte[] a = {1};
            byte[] b = {0};
            // Copy of size 0 should have no effect
            unsafe.copyMemory(a, ARRAY_BYTE_BASE_OFFSET, b, ARRAY_BYTE_BASE_OFFSET, 0);
            assertArrayEquals(new byte[] {1}, a);
            assertArrayEquals(new byte[] {0}, b);

            // Copy of size 0 should also be allowed right behind last array element, i.e. at 'exclusive end address' (but should have no effect)
            long endOffset = ARRAY_BYTE_BASE_OFFSET + a.length * ARRAY_BYTE_INDEX_SCALE;
            unsafe.copyMemory(a, endOffset, b, ARRAY_BYTE_BASE_OFFSET, 0);
            unsafe.copyMemory(b, ARRAY_BYTE_BASE_OFFSET, a, endOffset, 0);
            assertArrayEquals(new byte[] {1}, a);
            assertArrayEquals(new byte[] {0}, b);

            // Size 0 should allow using address 0 (should have no effect)
            unsafe.copyMemory(a, ARRAY_BYTE_BASE_OFFSET, null, 0, 0);
            unsafe.copyMemory(null, 0, b, ARRAY_BYTE_BASE_OFFSET, 0);
            assertArrayEquals(new byte[] {1}, a);
            assertArrayEquals(new byte[] {0}, b);
        });

        assertBadMemoryAccess(() -> {
            byte[] b = {1, 2, 3, 4};
            long from = ARRAY_BYTE_BASE_OFFSET;
            // `to + size` exceeds array
            long to = ARRAY_BYTE_BASE_OFFSET + ARRAY_BYTE_INDEX_SCALE * 3L;
            unsafe.copyMemory(b, from, b, to, 2);
        });

        {
            byte[] a = {1};
            // Should fail for invalid address, even if size is 0
            long maxOffset = ARRAY_BYTE_BASE_OFFSET + a.length * ARRAY_BYTE_INDEX_SCALE;
            var e = assertBadMemoryAccess(() -> unsafe.copyMemory(a, ARRAY_BYTE_BASE_OFFSET, a, maxOffset + 1, 0));
            assertEquals("Bad array access at offset " + (maxOffset + 1) + ", size 0; max offset is " + maxOffset, e.getMessage());
            e = assertBadMemoryAccess(() -> unsafe.copyMemory(a, ARRAY_BYTE_BASE_OFFSET, null, -1, 0));
            assertEquals("Invalid address: -1", e.getMessage());
            e = assertBadMemoryAccess(() -> unsafe.copyMemory(null, -1, a, ARRAY_BYTE_BASE_OFFSET, 0));
            assertEquals("Invalid address: -1", e.getMessage());
        }

        {
            byte[] b = {1};
            // Should fail for array offset 0 (assuming that base offset != 0), even if size is 0
            assert ARRAY_BYTE_BASE_OFFSET > 0;
            var e = assertBadMemoryAccess(() -> unsafe.copyMemory(b, 0, b, ARRAY_BYTE_BASE_OFFSET, 0));
            assertEquals("Bad array access at offset 0; min offset is " + ARRAY_BYTE_BASE_OFFSET, e.getMessage());
            e = assertBadMemoryAccess(() -> unsafe.copyMemory(b, ARRAY_BYTE_BASE_OFFSET, b, 0, 0));
            assertEquals("Bad array access at offset 0; min offset is " + ARRAY_BYTE_BASE_OFFSET, e.getMessage());
        }

        var e = assertBadMemoryAccess(() -> {
            Object[] a = {"a", "b"};
            // `copyMemory` only permits primitive arrays
            unsafe.copyMemory(a, ARRAY_OBJECT_BASE_OFFSET, a, ARRAY_OBJECT_BASE_OFFSET, 1);
        });
        assertEquals("Unsupported class " + Object[].class.getTypeName(), e.getMessage());
    }

    @Test
    void copyMemoryField() throws Exception {
        class Dummy {
            short a;
            char b;

            @Override
            public String toString() {
                return "Dummy";
            }
        }
        Field fieldA = Dummy.class.getDeclaredField("a");
        long offsetA = unsafe.objectFieldOffset(fieldA);
        Field fieldB = Dummy.class.getDeclaredField("b");
        long offsetB = unsafe.objectFieldOffset(fieldB);
        Dummy dummy = new Dummy();

        dummy.a = 1;
        var e = assertBadMemoryAccess(() -> unsafe.copyMemory(dummy, offsetA, dummy, offsetB, 2));
        assertEquals("Unsupported class " + Dummy.class.getTypeName(), e.getMessage());

        // Should fail even if size is 0
        e = assertBadMemoryAccess(() -> unsafe.copyMemory(dummy, offsetA, dummy, offsetB, 0));
        assertEquals("Unsupported class " + Dummy.class.getTypeName(), e.getMessage());

        // Should fail for offset 0 (assuming that no field is at that offset), even if size is 0
        assert offsetA != 0 && offsetB != 0;
        byte[] b = {1};
        e = assertBadMemoryAccess(() -> unsafe.copyMemory(dummy, 0, b, ARRAY_BYTE_BASE_OFFSET, 0));
        assertEquals("Unsupported class " + Dummy.class.getTypeName(), e.getMessage());
        e = assertBadMemoryAccess(() -> unsafe.copyMemory(b, ARRAY_BYTE_BASE_OFFSET, dummy, 0, 0));
        assertEquals("Unsupported class " + Dummy.class.getTypeName(), e.getMessage());
    }

    @Test
    void copyMemory() {
        assertNoBadMemoryAccess(() -> {
            long size = Long.BYTES;
            long a = allocateMemory(size);
            long longValue = 0x1234567890ABCDEFL;
            unsafe.putLong(a, longValue);

            long b = allocateMemory(size);
            unsafe.putLong(b, 0);

            // Copy of size 0 should have no effect
            unsafe.copyMemory(null, a, null, b, 0);
            assertEquals(longValue, unsafe.getLong(a));
            assertEquals(0, unsafe.getLong(b));

            // Copy of size 0 should also be allowed right behind sections, i.e. at 'exclusive end address' (but should have no effect)
            unsafe.copyMemory(null, a + size, null, b + size, 0);
            assertEquals(longValue, unsafe.getLong(a));
            assertEquals(0, unsafe.getLong(b));

            // Size 0 should allow using address 0 (should have no effect)
            unsafe.copyMemory(null, 0, null, 0, 0);
            unsafe.copyMemory(null, a, null, 0, 0);
            unsafe.copyMemory(null, 0, null, b, 0);
            assertEquals(longValue, unsafe.getLong(a));
            assertEquals(0, unsafe.getLong(b));

            unsafe.copyMemory(null, a, null, b, size);
            assertEquals(longValue, unsafe.getLong(a));
            assertEquals(longValue, unsafe.getLong(b));

            freeMemory(b);
            b = allocateMemory(size);
            unsafe.copyMemory(a, b, size);
            assertEquals(longValue, unsafe.getLong(b));

            freeMemory(b);
            b = allocateMemory(size);
            unsafe.setMemory(b, size, (byte) 0);
            unsafe.putInt(a + 2, 1);
            // Copy memory in the middle
            unsafe.copyMemory(a + 2, b + 2, 4);
            assertEquals(1, unsafe.getInt(b + 2));
            // Other memory should have remained unmodified
            assertEquals(0, unsafe.getByte(b));
            assertEquals(0, unsafe.getByte(b + 6));

            // Clean up
            freeMemory(a);
            freeMemory(b);
        });


        long size = 8;
        long a = allocateMemory(size);
        long b = allocateMemory(size);

        assertBadMemoryAccess(() -> unsafe.copyMemory(null, a, null, b, size + 1));
        assertBadMemoryAccess(() -> unsafe.copyMemory(a, b, size + 1));

        assertBadMemoryAccess(() -> unsafe.copyMemory(null, a + 1, null, b, size));
        assertBadMemoryAccess(() -> unsafe.copyMemory(a + 1, b, size));

        assertBadMemoryAccess(() -> unsafe.copyMemory(null, a, null, b + 1, size));
        assertBadMemoryAccess(() -> unsafe.copyMemory(a, b + 1, size));

        // Should fail for invalid address, even if size is 0
        var e = assertBadMemoryAccess(() -> unsafe.copyMemory(-1, 0, 0));
        assertEquals("Invalid address: -1", e.getMessage());
        e = assertBadMemoryAccess(() -> unsafe.copyMemory(0, -1, 0));
        assertEquals("Invalid address: -1", e.getMessage());

        assertBadMemoryAccess(() -> {
            class Dummy {
                int i;
            }
            long offset = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("i"));
            Dummy dummy = new Dummy();
            // `copyMemory` only allows primitive arrays as objects
            unsafe.copyMemory(dummy, offset, dummy, offset, 4);
        });

        // Clean up
        freeMemory(a);
        freeMemory(b);
    }

    @Test
    void setMemoryArray() {
        assertNoBadMemoryAccess(() -> {
            byte[] b = {1, 2, 3, 4};
            unsafe.setMemory(b, ARRAY_BYTE_BASE_OFFSET, 4, (byte) 2);
            assertArrayEquals(new byte[] {2, 2, 2, 2}, b);
        });
        assertNoBadMemoryAccess(() -> {
            byte[] b = {1, 2, 3, 4};
            unsafe.setMemory(b, ARRAY_BYTE_BASE_OFFSET, 2, (byte) 5);
            assertArrayEquals(new byte[] {5, 5, 3, 4}, b);
        });
        assertNoBadMemoryAccess(() -> {
            byte[] b = {1, 2, 3, 4};
            unsafe.setMemory(b, ARRAY_BYTE_BASE_OFFSET + ARRAY_BYTE_INDEX_SCALE, 2, (byte) 5);
            assertArrayEquals(new byte[] {1, 5, 5, 4}, b);
        });
        assertNoBadMemoryAccess(() -> {
            long[] a = {1, 2, 3};
            unsafe.setMemory(a, ARRAY_LONG_BASE_OFFSET + ARRAY_LONG_INDEX_SCALE, Long.BYTES, (byte) 15);
            assertArrayEquals(new long[] {1, 0x0F0F0F0F0F0F0F0FL, 3}, a);
        });

        var e = assertBadMemoryAccess(() -> {
            byte[] b = {1, 2, 3, 4};
            unsafe.setMemory(b, ARRAY_BYTE_BASE_OFFSET, b.length + 1, (byte) 5);
        });
        assertEquals(
            "Bad array access at offset " + ARRAY_BYTE_BASE_OFFSET + ", size 5; max offset is " + (ARRAY_BYTE_BASE_OFFSET + 4 * ARRAY_BYTE_INDEX_SCALE),
            e.getMessage()
        );

        e = assertBadMemoryAccess(() -> {
            Object[] a = {"a", "b"};
            // `setMemory` only permits primitive arrays
            unsafe.setMemory(a, ARRAY_OBJECT_BASE_OFFSET, ARRAY_OBJECT_INDEX_SCALE, (byte) 2);
        });
        assertEquals("Unsupported class " + Object[].class.getTypeName(), e.getMessage());

        e = assertBadMemoryAccess(() -> {
            byte[] b = {1, 2, 3, 4};
            // Should fail for array offset 0 (assuming that base offset != 0), even if size is 0
            assert ARRAY_BYTE_BASE_OFFSET > 0;
            unsafe.setMemory(b, 0, 0, (byte) 5);
        });
        assertEquals(
            "Bad array access at offset 0; min offset is " + ARRAY_BYTE_BASE_OFFSET,
            e.getMessage()
        );
    }

    @Test
    void setMemoryField() throws Exception {
        class Dummy {
            int a;

            @Override
            public String toString() {
                return "Dummy";
            }
        }
        Field field = Dummy.class.getDeclaredField("a");
        long offset = unsafe.objectFieldOffset(field);
        Dummy dummy = new Dummy();

        dummy.a = 1;
        var e = assertBadMemoryAccess(() -> unsafe.setMemory(dummy, offset, 4, (byte) 2));
        assertEquals("Unsupported class " + Dummy.class.getTypeName(), e.getMessage());

        // Should fail even if size is 0
        e = assertBadMemoryAccess(() -> unsafe.setMemory(dummy, offset, 0, (byte) 2));
        assertEquals("Unsupported class " + Dummy.class.getTypeName(), e.getMessage());

        // Should fail for offset 0 (assuming that no field is at that offset), even if size is 0
        assert offset != 0;
        e = assertBadMemoryAccess(() -> unsafe.setMemory(dummy, 0, 0, (byte) 2));
        assertEquals("Unsupported class " + Dummy.class.getTypeName(), e.getMessage());
    }

    @Test
    void setMemory() {
        assertNoBadMemoryAccess(() -> {
            long size = 8;
            long a = allocateMemory(size);
            unsafe.putLong(a, 0);

            // Size 0 should have no effect
            unsafe.setMemory(a, 0, (byte) 1);
            assertEquals(0, unsafe.getLong(a));

            // Size 0 should also be allowed right behind section, i.e. at 'exclusive end address' (but should have no effect)
            unsafe.setMemory(a + size, 0, (byte) 1);
            assertEquals(0, unsafe.getLong(a));

            // Size 0 should allow using address 0 (should have no effect)
            unsafe.setMemory(0, 0, (byte) 1);

            unsafe.setMemory(a + 2, 4, (byte) 0x12);
            assertEquals(0x12121212, unsafe.getInt(a + 2));
            // Other memory should have remained unmodified
            assertEquals(0, unsafe.getByte(a));
            assertEquals(0, unsafe.getByte(a + 6));

            // Clean up
            freeMemory(a);
        });

        var e = assertBadMemoryAccess(() -> unsafe.setMemory(-1, 2, (byte) 0));
        assertEquals("Invalid address: -1", e.getMessage());

        // Should fail for invalid address, even if size is 0
        e = assertBadMemoryAccess(() -> unsafe.setMemory(-1, 0, (byte) 0));
        assertEquals("Invalid address: -1", e.getMessage());

        long size = 8;
        long a = allocateMemory(size);

        e = assertBadMemoryAccess(() -> unsafe.setMemory(a, -1, (byte) 0));
        assertEquals("Invalid bytes count: -1", e.getMessage());

        e = assertBadMemoryAccess(() -> unsafe.setMemory(a + 1, size, (byte) 0));
        assertEquals(
            "Access outside of section at " + (a + 1) + ", size 8 (previous section: " + a + ", size 8)",
            e.getMessage()
        );

        // Should fail for invalid address, even if size is 0
        e = assertBadMemoryAccess(() -> unsafe.setMemory(a + size + 1, 0, (byte) 4));
        assertEquals(
            "Access outside of section at " + (a + size + 1) + ", size 0 (previous section: " + a + ", size 8)",
            e.getMessage()
        );

        // Clean up
        freeMemory(a);
    }

    // TODO: Add tests which call all Unsafe methods (without necessarily verifying their effect)
    //   just to make sure that there is no bug in intercepting
}
