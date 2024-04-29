package marcono1234.unsafe_sanitizer.agent_impl;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * Helper for {@code ByteBuffer}s allocated with {@link ByteBuffer#allocateDirect(int)}.
 */
public class DirectByteBufferHelper {
    private DirectByteBufferHelper() {}

    /**
     * Name of the internal JDK class performing deallocation of direct byte buffers.
     */
    public static final String DEALLOCATOR_CLASS_NAME = "java.nio.DirectByteBuffer$Deallocator";

    /*
     * Note: This does not use `Unsafe` to access the field values because this would then cause
     * spurious log entries for the sanitizer
     * Instead it makes the fields accessible and relies on the agent opening `java.nio` to this module
     */
    private static final MethodHandle bufferAddressGetter;
    static {
        try {
            Field bufferAddressField = Buffer.class.getDeclaredField("address");
            bufferAddressField.setAccessible(true);
            bufferAddressGetter = MethodHandles.lookup().unreflectGetter(bufferAddressField);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed getting 'address' field", e);
        }
    }

    private static final MethodHandle deallocatorAddressGetter;
    static {
        Class<?> deallocatorClass;
        try {
            deallocatorClass = Class.forName(DEALLOCATOR_CLASS_NAME);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed getting deallocator class", e);
        }

        // In JDK 22 (backported to JDK 21) the class was converted to a record class, see
        // https://github.com/openjdk/jdk/commit/cf74b8c2a32f33019a13ce80b6667da502cc6722
        // However, component name is still the same so can still access the backing field
        // with that name
        try {
            Field deallocatorAddressField = deallocatorClass.getDeclaredField("address");
            deallocatorAddressField.setAccessible(true);
            deallocatorAddressGetter = MethodHandles.lookup().unreflectGetter(deallocatorAddressField);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed getting 'address' field", e);
        }
    }

    public static long getAddress(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer must be direct");
        }
        try {
            return (long) bufferAddressGetter.invokeExact((Buffer) buffer);
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed getting buffer address", t);
        }
    }

    public static long getDeallocatorAddress(Runnable r) {
        try {
            // Cannot use `invokeExact` here because the receiver type is actually the private
            // `DirectByteBuffer$Deallocator` class
            return (long) deallocatorAddressGetter.invoke(r);
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed getting deallocator address", t);
        }
    }
}
