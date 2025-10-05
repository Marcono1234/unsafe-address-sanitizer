/*
 Use dummy package instead of `marcono1234.unsafe_sanitizer` to avoid Jazzer trying to record coverage for sanitizer
 implementation (because it is based on test package name if property `jazzer.instrument` is not explicitly specified),
 which could lead to infinite recursion because Jazzer uses Unsafe for coverage tracking, which is intercepted by
 the sanitizer, which is intercepted by Jazzer coverage tracking ...
*/
package com.example;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.api.FuzzerSecurityIssueCritical;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.platform.testkit.engine.EngineTestKit;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EventConditions.*;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.*;

/**
 * Runs Jazzer tests in 'regression mode' where the previously generated inputs from the
 * {@code JazzerRegressionTestImplInputs} directory are used.
 */
class JazzerRegressionTest {
    /*
     * Test is executed manually below using JUnit Platform Test Kit to be able to assert expected failures,
     * see also https://junit.org/junit5/docs/current/user-guide/#testkit
     *
     * If you want to execute this manually (for example for easier troubleshooting), temporarily remove the
     * exclusion filter in `build.gradle.kts`
     */
    @SuppressWarnings("NewClassNamingConvention")  // intentionally does not have standard test name
    @TestMethodOrder(OrderAnnotation.class)  // to ensure test events have consistent order
    static class JazzerRegressionTestImpl {
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

        private static void testArray(FuzzedDataProvider dataProvider, boolean includeBug) {
            byte[] array = new byte[dataProvider.consumeInt(70, 100)];
            // Intentional bug: Index might be >= array length
            int index = dataProvider.consumeInt(0, array.length - 1 + (includeBug ? 3 : 0)); // - 1 because `max` parameter is inclusive

            int offset = Unsafe.ARRAY_BYTE_BASE_OFFSET + index * Unsafe.ARRAY_BYTE_INDEX_SCALE;
            byte expectedValue = 3;
            unsafe.putByte(array, offset, expectedValue);
            assertEquals(expectedValue, unsafe.getByte(array, offset));
        }

        @Order(1)
        @FuzzTest
        void array_success(FuzzedDataProvider dataProvider) {
            testArray(dataProvider, false);
        }

        // Note: Out-of-bounds array access is detected by Jazzer's built-in Unsafe sanitizer
        @Order(2)
        @FuzzTest
        void array_error(FuzzedDataProvider dataProvider) {
            testArray(dataProvider, true);
        }

        private static void testNativeMemory(FuzzedDataProvider dataProvider, boolean includeBug) {
            int size = dataProvider.consumeInt(70, 100);
            long address = unsafe.allocateMemory(size);
            try {
                byte expectedValue = 3;
                // Intentional bug: Initialize everything except byte at last address; leading to potential read of uninitialized memory
                unsafe.setMemory(address, size - (includeBug ? 1 : 0), expectedValue);
                int index = dataProvider.consumeInt(0, size - 1); // - 1 because `max` parameter is inclusive
                assertEquals(expectedValue, unsafe.getByte(address + index));
            } finally {
                unsafe.freeMemory(address);
            }
        }

        @Order(3)
        @FuzzTest
        void nativeMemory_success(FuzzedDataProvider dataProvider) {
            testNativeMemory(dataProvider, false);
        }

        @Order(4)
        @FuzzTest
        void nativeMemory_error(FuzzedDataProvider dataProvider) {
            testNativeMemory(dataProvider, true);
        }
    }

    private static <T> Condition<T> isInstanceOf(String className) {
        return new Condition<>(t -> t.getClass().getSimpleName().equals(className), "is of type %s", className);
    }

    private static Condition<Throwable> isFuzzFindingException() {
        // This Jazzer class does not seem to be part of public API?
        return isInstanceOf("FuzzTestFindingException");
    }

    private static Condition<Throwable> isSanitizerError() {
        // Check for name because error class is not part of public API of sanitizer
        return isInstanceOf("BadMemoryAccessError");
    }

    @Test
    void runTests() {
        var results = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(JazzerRegressionTestImpl.class))
            .execute();

        results.testEvents().finished()
            .assertEventsMatchExactly(
                event(test("array_success"), finishedSuccessfully()), // for '<empty input>' fuzz data
                event(test("array_success"), finishedSuccessfully()),

                event(test("array_error"), finishedSuccessfully()), // for '<empty input>' fuzz data
                event(test("array_error"), finishedWithFailure(
                    // This exception is thrown by Jazzer's built-in Unsafe sanitizer (the exception message is kind of an implementation detail)
                    isFuzzFindingException(), cause(instanceOf(FuzzerSecurityIssueCritical.class), message(m -> m.contains(" exceeds end offset "))))),

                event(test("nativeMemory_success"), finishedSuccessfully()), // for '<empty input>' fuzz data
                event(test("nativeMemory_success"), finishedSuccessfully()),

                event(test("nativeMemory_error"), finishedSuccessfully()), // for '<empty input>' fuzz data
                event(test("nativeMemory_error"), finishedWithFailure(
                    isFuzzFindingException(), cause(isSanitizerError(), message(m -> m.startsWith("Trying to read uninitialized data at ")))))
            );
    }
}
