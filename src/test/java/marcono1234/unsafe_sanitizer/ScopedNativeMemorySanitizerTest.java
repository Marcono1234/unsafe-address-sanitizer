package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.agent_impl.BadMemoryAccessError;
import marcono1234.unsafe_sanitizer.agent_impl.UnsafeSanitizerImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static marcono1234.unsafe_sanitizer.MemoryHelper.allocateMemory;
import static marcono1234.unsafe_sanitizer.MemoryHelper.freeMemory;
import static marcono1234.unsafe_sanitizer.TestSupport.assertBadMemoryAccess;
import static marcono1234.unsafe_sanitizer.TestSupport.assertNoBadMemoryAccess;
import static marcono1234.unsafe_sanitizer.UnsafeAccess.unsafe;
import static marcono1234.unsafe_sanitizer.UnsafeSanitizer.withScopedNativeMemoryTracking;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"Convert2MethodRef", "CodeBlock2Expr"})
class ScopedNativeMemorySanitizerTest {
    @BeforeAll
    static void installAgent() {
        UnsafeSanitizer.installAgent(UnsafeSanitizer.AgentSettings.defaultSettings());
        UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.THROW);
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

    @Test
    void success() {
        assertNoBadMemoryAccess(() -> {
            withScopedNativeMemoryTracking(() -> {
                long address = unsafe.allocateMemory(10);
                unsafe.putInt(address, 1);

                var e = assertThrows(IllegalStateException.class, () -> UnsafeSanitizer.checkAllNativeMemoryFreed());
                assertEquals("Still contains the following sections: [Section[address=" + address + ", bytesCount=10]]", e.getMessage());

                unsafe.freeMemory(address);
                UnsafeSanitizer.checkAllNativeMemoryFreed();
            });
        });
    }

    /**
     * For arrays should not matter where array was created.
     */
    @Test
    void success_Array() {
        AtomicReference<byte[]> array = new AtomicReference<>();
        withScopedNativeMemoryTracking(() -> array.set(new byte[10]));

        withScopedNativeMemoryTracking(() -> {
            assertNoBadMemoryAccess(() -> unsafe.putByte(array.get(), Unsafe.ARRAY_BYTE_BASE_OFFSET, (byte) 1));
        });
    }

    @Test
    void error_BadAccess() {
        withScopedNativeMemoryTracking(() -> {
            long address = allocateMemory(10);
            assertBadMemoryAccess(() -> unsafe.putInt(address - 4, 1));
            // Clean up
            freeMemory(address);
        });

        withScopedNativeMemoryTracking(() -> {
            long address = allocateMemory(10);
            freeMemory(address);

            // Double free
            assertBadMemoryAccess(() -> unsafe.freeMemory(address));
        });
    }

    @Test
    void error_BadAccessDiscardedError() {
        var e = assertThrows(IllegalStateException.class, () -> withScopedNativeMemoryTracking(() -> {
            // Does not propagate the BadMemoryAccessError
            assertThrows(BadMemoryAccessError.class, () -> unsafe.allocateMemory(-1));
        }));
        assertEquals("Unhandled bad memory access error", e.getMessage());
    }

    @Test
    void error_NestedScope() {
        var e = assertThrows(IllegalStateException.class, () -> {
            withScopedNativeMemoryTracking(() -> withScopedNativeMemoryTracking(() -> fail("should not be called")));
        });
        assertEquals("Scope is already active; cannot nest scopes", e.getMessage());
    }

    @Test
    void error_AccessScopedInGlobal() {
        AtomicLong address = new AtomicLong();
        withScopedNativeMemoryTracking(() -> {
            address.set(allocateMemory(10));
        });

        assertBadMemoryAccess(() -> unsafe.putInt(address.get(), 1));

        // Clean up
        freeMemory(address.get());
    }

    // TODO: Should support this case?
    @Test
    void error_AccessGlobalInScope() {
        long address = allocateMemory(10);

        withScopedNativeMemoryTracking(() -> {
            assertBadMemoryAccess(() -> unsafe.putInt(address, 1));
        });

        // Clean up
        freeMemory(address);
    }

    @Test
    void error_AccessScopedInOtherScope() {
        AtomicLong address = new AtomicLong();
        withScopedNativeMemoryTracking(() -> {
            address.set(allocateMemory(10));
        });

        withScopedNativeMemoryTracking(() -> {
            assertBadMemoryAccess(() -> unsafe.putInt(address.get(), 1));
        });

        // Clean up
        freeMemory(address.get());
    }

    /**
     * Verifies that memory allocated in local scope can be freed in global scope, handling
     * the case where a separate cleaner thread frees memory after garbage collection.
     */
    @Test
    void freeScopedInGlobal() {
        AtomicLong address = new AtomicLong();
        withScopedNativeMemoryTracking(() -> {
            address.set(allocateMemory(10));
        });

        assertNoBadMemoryAccess(() -> unsafe.freeMemory(address.get()));

        // Double free
        assertBadMemoryAccess(() -> unsafe.freeMemory(address.get()));
    }

    /**
     * Verifies that memory allocated in global scope cannot be freed in local scope,
     * since memory cannot be accessed in local scope either and it is unlikely that
     * cleaner action for global memory runs in local scope.
     */
    @Test
    void freeGlobalInScope() {
        long address = allocateMemory(10);

        withScopedNativeMemoryTracking(() -> {
            assertBadMemoryAccess(() -> unsafe.freeMemory(address));
        });

        assertNoBadMemoryAccess(() -> unsafe.freeMemory(address));
    }

    /**
     * Verifies that memory allocated in one scope cannot be freed in another scope,
     * since memory cannot be accessed in that other scope either and it is unlikely that
     * cleaner action for memory from one scope runs in other scope.
     */
    @Test
    void freeScopedInOtherScope() {
        AtomicLong address = new AtomicLong();
        withScopedNativeMemoryTracking(() -> {
            address.set(allocateMemory(10));
        });

        withScopedNativeMemoryTracking(() -> {
            assertBadMemoryAccess(() -> unsafe.freeMemory(address.get()));
        });

        assertNoBadMemoryAccess(() -> unsafe.freeMemory(address.get()));
    }

    @Test
    void uninitializedTracking() {
        UnsafeSanitizerImpl.withUninitializedMemoryTracking(true, () -> {
            {
                long address = allocateMemory(10);
                // Reads uninitialized memory
                assertBadMemoryAccess(() -> unsafe.getByte(address));

                // Clean up
                freeMemory(address);
            }

            // Inherited from global
            withScopedNativeMemoryTracking(() -> {
                long address = allocateMemory(10);

                // Reads uninitialized memory
                assertBadMemoryAccess(() -> unsafe.getByte(address));

                // Clean up
                freeMemory(address);
            });

            // Overwrite global; enable uninitialized tracking
            withScopedNativeMemoryTracking(true, () -> {
                long address = allocateMemory(10);

                // Reads uninitialized memory
                assertBadMemoryAccess(() -> unsafe.getByte(address));

                // Clean up
                freeMemory(address);
            });

            // Overwrite global; disable uninitialized tracking
            withScopedNativeMemoryTracking(false, () -> {
                long address = allocateMemory(10);

                // Reads uninitialized memory, but tracking is disabled
                assertNoBadMemoryAccess(() -> unsafe.getByte(address));

                // Clean up
                freeMemory(address);
            });

            // Global sanitizer should have been unaffected by disabled uninitialized memory tracking in scope above
            {
                long address = allocateMemory(10);
                // Reads uninitialized memory
                assertBadMemoryAccess(() -> unsafe.getByte(address));

                // Clean up
                freeMemory(address);
            }
        });
    }

    @Test
    void uninitializedTracking_GlobalDisabled() {
        UnsafeSanitizerImpl.withUninitializedMemoryTracking(false, () -> {
            {
                long address = allocateMemory(10);
                // Reads uninitialized memory, but tracking is disabled
                assertNoBadMemoryAccess(() -> unsafe.getByte(address));

                // Clean up
                freeMemory(address);
            }

            // Overwrite global; disable uninitialized tracking
            withScopedNativeMemoryTracking(false, () -> {
                long address = allocateMemory(10);

                // Reads uninitialized memory, but tracking is disabled
                assertNoBadMemoryAccess(() -> unsafe.getByte(address));

                // Clean up
                freeMemory(address);
            });

            // Overwrite global; enable uninitialized tracking
            withScopedNativeMemoryTracking(true, () -> {
                long address = allocateMemory(10);

                // Reads uninitialized memory
                assertBadMemoryAccess(() -> unsafe.getByte(address));

                // Clean up
                freeMemory(address);
            });

            // Global sanitizer should have been unaffected by enabled uninitialized memory tracking in scope above
            {
                long address = allocateMemory(10);
                // Reads uninitialized memory, but tracking is disabled
                assertNoBadMemoryAccess(() -> unsafe.getByte(address));

                // Clean up
                freeMemory(address);
            }
        });
    }

    @Test
    void lastErrorScoped_GlobalError() throws Exception {
        byte[] array = new byte[0];
        assertThrows(BadMemoryAccessError.class, () -> unsafe.getByte(array, -1));
        var lastErrorGlobal = UnsafeSanitizer.getLastError();
        assertNotNull(lastErrorGlobal);
        assertEquals("Invalid offset: -1", lastErrorGlobal.getMessage());

        // Contains `Boolean.TRUE` on success
        AtomicReference<Object> threadResult = new AtomicReference<>(null);
        CountDownLatch threadCausedError = new CountDownLatch(1);
        CountDownLatch mainCheckedError = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try {
                assertSame(lastErrorGlobal, UnsafeSanitizer.getLastError());

                withScopedNativeMemoryTracking(() -> {
                    assertNull(UnsafeSanitizer.getLastError());

                    assertThrows(BadMemoryAccessError.class, () -> unsafe.getByte(array, -1));
                    var lastErrorScoped = UnsafeSanitizer.getLastError();
                    assertNotNull(lastErrorScoped);
                    assertNotSame(lastErrorGlobal, lastErrorScoped);
                    assertEquals("Invalid offset: -1", lastErrorScoped.getMessage());

                    threadCausedError.countDown();
                    mainCheckedError.await();

                    // Allow to exit `withScopedNativeMemoryTracking` despite non-propagated last error
                    UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.NONE);
                });
                UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.THROW);

                // Sees original global error again
                assertSame(lastErrorGlobal, UnsafeSanitizer.getLastError());
                threadResult.set(Boolean.TRUE);
            } catch (Throwable t) {
                threadResult.set(t);
                // Prevent deadlock for main thread in case of exception
                threadCausedError.countDown();
            }
        });
        thread.setDaemon(true);
        thread.start();

        threadCausedError.await();
        // Should still see original global error
        assertSame(lastErrorGlobal, UnsafeSanitizer.getLastError());
        mainCheckedError.countDown();

        thread.join();
        UnsafeSanitizer.clearLastError();

        if (threadResult.get() instanceof Throwable t) {
            fail("Error in thread", t);
        }
        assertEquals(Boolean.TRUE, threadResult.get());
    }

    @Test
    void lastErrorScoped_NoGlobalError() throws Exception {
        byte[] array = new byte[0];
        assertNull(UnsafeSanitizer.getLastError());

        // Contains `Boolean.TRUE` on success
        AtomicReference<Object> threadResult = new AtomicReference<>(null);
        CountDownLatch threadCausedError = new CountDownLatch(1);
        CountDownLatch mainCheckedError = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try {
                assertNull(UnsafeSanitizer.getLastError());

                withScopedNativeMemoryTracking(() -> {
                    assertThrows(BadMemoryAccessError.class, () -> unsafe.getByte(array, -1));
                    var lastError = UnsafeSanitizer.getLastError();
                    assertNotNull(lastError);
                    assertEquals("Invalid offset: -1", lastError.getMessage());

                    threadCausedError.countDown();
                    mainCheckedError.await();
                    // Should still see the same scoped error
                    assertSame(lastError, UnsafeSanitizer.getLastError());

                    // Allow to exit `withScopedNativeMemoryTracking` despite non-propagated last error
                    UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.NONE);
                });
                UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.THROW);

                assertNull(UnsafeSanitizer.getLastError());
                threadResult.set(Boolean.TRUE);
            } catch (Throwable t) {
                threadResult.set(t);
                // Prevent deadlock for main thread in case of exception
                threadCausedError.countDown();
            }
        });
        thread.setDaemon(true);
        thread.start();

        threadCausedError.await();
        // Error from scope should not be visible
        assertNull(UnsafeSanitizer.getLastError());
        // Clearing error should not affect scope
        UnsafeSanitizer.clearLastError();
        mainCheckedError.countDown();

        thread.join();
        if (threadResult.get() instanceof Throwable t) {
            fail("Error in thread", t);
        }
        assertEquals(Boolean.TRUE, threadResult.get());
    }

    @Test
    void registerAllocatedMemory() {
        long address = allocateMemory(10);

        withScopedNativeMemoryTracking(true, () -> {
            assertBadMemoryAccess(() -> unsafe.getByte(address));

            UnsafeSanitizer.registerAllocatedMemory(address, 10);
            // This actually reads uninitialized memory, but `registerAllocatedMemory` assumes that region is
            // fully initialized
            assertNoBadMemoryAccess(() -> unsafe.getByte(address));

            // Reads outside of region
            assertBadMemoryAccess(() -> unsafe.getLong(address + 8));

            UnsafeSanitizer.deregisterAllocatedMemory(address);
            assertBadMemoryAccess(() -> unsafe.getByte(address));

            // Double free
            assertBadMemoryAccess(() -> UnsafeSanitizer.deregisterAllocatedMemory(address));
        });

        // Clean up
        freeMemory(address);
    }

    @Test
    void checkAllNativeMemoryFreed() {
        long address = allocateMemory(10);

        withScopedNativeMemoryTracking(false, () -> {
            // Should not see allocation outside of scope
            UnsafeSanitizer.checkAllNativeMemoryFreed();

            long scopedAddress = allocateMemory(10);
            var e = assertThrows(IllegalStateException.class, () -> UnsafeSanitizer.checkAllNativeMemoryFreed());
            assertEquals(
                "Still contains the following sections: [Section[address=" + scopedAddress + ", bytesCount=10]]",
                e.getMessage()
            );

            freeMemory(scopedAddress);
            UnsafeSanitizer.checkAllNativeMemoryFreed();
        });

        // Clean up
        freeMemory(address);
    }
}
