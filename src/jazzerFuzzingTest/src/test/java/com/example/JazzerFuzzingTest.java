/*
 Use dummy package instead of `marcono1234.unsafe_sanitizer` to avoid Jazzer trying to record coverage for sanitizer
 implementation (because it is based on test package name if property `jazzer.instrument` is not explicitly specified),
 which could lead to infinite recursion because Jazzer uses Unsafe for coverage tracking, which is intercepted by
 the sanitizer, which is intercepted by Jazzer coverage tracking ...
*/
package com.example;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.BeforeAll;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runs Jazzer tests in 'fuzzing mode', performing actual fuzzing.
 *
 * <p>The fuzz tests below intentionally do not perform invalid memory access through {@code Unsafe} because that
 * would make the tests unreliable since one fuzzing run might trigger the invalid access while another one
 * might not. Instead, the purpose of the tests is to verify that the sanitizer does not interfere with Jazzer's
 * own {@code Unsafe} usage.
 */
class JazzerFuzzingTest {
    private static final Unsafe unsafe;
    static {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed getting Unsafe", e);
        }
    }

    /**
     * Verify that the sanitizer is actually installed. Because the fuzz test does not perform invalid memory
     * access, so it would not notice if the sanitizer had not actually been installed.
     */
    @BeforeAll
    static void checkSanitizerIsInstalled() {
        long address = unsafe.allocateMemory(10);
        try {
            var e = assertThrows(Error.class, () -> unsafe.getByte(address));
            assertEquals("Trying to read uninitialized data at address " + address + ", size 1", e.getMessage());
        } finally {
            unsafe.freeMemory(address);
        }
    }

    @BeforeAll
    static void checkJazzerIsFuzzing() {
        assertEquals("1", System.getenv("JAZZER_FUZZ"));
    }

    private static void testArray(FuzzedDataProvider dataProvider) {
        byte[] array = new byte[dataProvider.consumeInt(70, 100)];
        int index = dataProvider.consumeInt(0, array.length - 1); // - 1 because `max` parameter is inclusive

        int offset = Unsafe.ARRAY_BYTE_BASE_OFFSET + index * Unsafe.ARRAY_BYTE_INDEX_SCALE;
        byte expectedValue = (byte) (index % 10 == 0 ? 3 : 4); // adds branching, which is hopefully recognized by Jazzer coverage tracking
        unsafe.putByte(array, offset, expectedValue);
        assertEquals(expectedValue, unsafe.getByte(array, offset));
    }

    private static void testNativeMemory(FuzzedDataProvider dataProvider) {
        int size = dataProvider.consumeInt(70, 100);
        long address = unsafe.allocateMemory(size);
        try {
            byte expectedValue = (byte) (size % 10 == 0 ? 3 : 4); // adds branching, which is hopefully recognized by Jazzer coverage tracking
            unsafe.setMemory(address, size, expectedValue);
            int index = dataProvider.consumeInt(0, size - 1); // - 1 because `max` parameter is inclusive
            assertEquals(expectedValue, unsafe.getByte(address + index));
        } finally {
            unsafe.freeMemory(address);
        }
    }

    // Can only have a single @FuzzTest which performs fuzzing, see https://github.com/CodeIntelligenceTesting/jazzer/issues/599
    @FuzzTest(maxExecutions = 1000)  // use low execution count to avoid long build times of project
    void fuzzTest(FuzzedDataProvider dataProvider) {
        testArray(dataProvider);
        testNativeMemory(dataProvider);
    }
}
