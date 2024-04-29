package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.agent_impl.DirectByteBufferHelper;
import marcono1234.unsafe_sanitizer.agent_impl.UnsafeSanitizerImpl;
import net.bytebuddy.asm.Advice.*;

import java.nio.ByteBuffer;

// Note: To avoid IntelliJ warnings, can mark in IntelliJ methods annotated with `@OnMethodEnter` and `@OnMethodExit`
//   as entrypoint

/**
 * Interceptors for 'direct' {@link ByteBuffer}, allocated with {@link ByteBuffer#allocateDirect(int)}.
 */
// This is an interface to allow writing `class` instead of `public static class` for all nested classes;
// probably acceptable since this is not public API
interface DirectByteBufferInterceptors {
    // Note: This currently assumes that all buffer instances which use `java.nio.DirectByteBuffer$Deallocator` are
    // created by `ByteBuffer.allocateDirect`. Otherwise unrelated `Deallocator.run()` calls would erroneously cause
    // a double free sanitizer error. This assumption seems to currently hold true, but might be a bit brittle. Maybe
    // instead of `ByteBuffer.allocateDirect` could intercept `DirectByteBuffer` constructor. But that would also be
    // brittle then because it relies on its implementation details.

    class AllocateDirect {
        // Note: Don't need to check arguments here since `ByteBuffer.allocateDirect` is public API and validates
        // its arguments
        @OnMethodExit
        public static void exit(@Argument(0) int bytesCount, @Return(readOnly = false) ByteBuffer buffer) {
            long address = DirectByteBufferHelper.getAddress(buffer);
            if (!UnsafeSanitizerImpl.onAllocatedDirectBuffer(address, bytesCount)) {
                //noinspection UnusedAssignment
                buffer = null;
            }
        }
    }

    // TODO: Instead of intercepting this private class, could also intercept JDK-internal `jdk.internal.misc.Unsafe#freeMemory`
    //  but would have to ignore then when no allocation exists at that address, because `freeMemory` will also be
    //  used by other JDK code which allocated native memory (and which won't be detected by sanitizer because it
    //  only intercepts the 'public' `sun.misc.Unsafe` class)
    /**
     * {@code java.nio.DirectByteBuffer$Deallocator#run()}
     */
    class DeallocatorRun {
        // Uses `@OnMethodEnter#skipOn` to skip execution in case of `ErrorAction#PRINT_SKIP`; otherwise it might
        // crash the JVM when deallocating already freed memory (double free)
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        public static boolean enter(@This Runnable this_) {
            long address = DirectByteBufferHelper.getDeallocatorAddress(this_);
            if (address == 0) {
                // Already freed
                return true;
            }

            return UnsafeSanitizerImpl.freeDirectBufferMemory(address);
        }
    }
}
