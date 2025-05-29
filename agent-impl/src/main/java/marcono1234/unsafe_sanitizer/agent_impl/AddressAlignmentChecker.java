package marcono1234.unsafe_sanitizer.agent_impl;

import static marcono1234.unsafe_sanitizer.agent_impl.BadMemoryAccessError.reportError;

class AddressAlignmentChecker {
    private AddressAlignmentChecker() {}

    public static volatile boolean checkAlignment = true;

    /**
     * @return
     *      {@code true} if the memory access should be performed; {@code false} if the memory access
     *      should be skipped and no exception was thrown due to the configured {@link AgentErrorAction}
     */
    public static boolean checkAlignment(Object obj, long address, MemorySize memorySize) {
        // Should only be called for native memory access or access to array
        assert obj == null || obj.getClass().isArray();

        if (!checkAlignment) {
            return true;
        }
        // Skip for Object access, caller should have verified that Object access is only possible for non-primitive
        // array and vice versa
        if (memorySize == MemorySize.OBJECT) {
            return true;
        }

        /*
         * For arrays the following matches the behavior of `jdk.internal.misc.Unsafe#putLongUnaligned` and similar
         * which just check the offset, and assume the native base address of the array is aligned.
         * See also https://mail.openjdk.org/pipermail/panama-dev/2025-May/021038.html
         *
         * Checking alignment for arrays is necessary because there are no guarantees except for the alignment to the
         * size of the elements. See also https://bugs.openjdk.org/browse/JDK-8320247 and https://bugs.openjdk.org/browse/JDK-8314882
         */

        int bytesCount = memorySize.getBytesCount();
        if (address % bytesCount != 0) {
            return reportError("Address " + address + " is not aligned by " + bytesCount);
        }
        return true;
    }
}
