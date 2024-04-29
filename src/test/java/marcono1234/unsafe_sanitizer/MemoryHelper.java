package marcono1234.unsafe_sanitizer;

import static marcono1234.unsafe_sanitizer.TestSupport.assertNoBadMemoryAccess;
import static marcono1234.unsafe_sanitizer.TestSupport.assertNoBadMemoryAccessGet;
import static marcono1234.unsafe_sanitizer.UnsafeAccess.unsafe;

/**
 * Helper class for memory allocation and deallocation in unit tests.
 */
public class MemoryHelper {
    private MemoryHelper() {}

    /** Allocates memory, not expecting any error */
    public static long allocateMemory(long bytesCount) {
        // Temporarily set ErrorAction#THROW to detect unexpected bad memory access
        return TestSupport.withThrowErrorAction(() -> {
            return assertNoBadMemoryAccessGet(() -> unsafe.allocateMemory(bytesCount));
        });
    }

    /** Frees memory, not expecting any error */
    public static void freeMemory(long address) {
        // Temporarily set ErrorAction#THROW to detect unexpected bad memory access
        TestSupport.withThrowErrorAction(() -> {
            assertNoBadMemoryAccess(() -> unsafe.freeMemory(address));
            return null;
        });
    }
}
