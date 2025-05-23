package marcono1234.unsafe_sanitizer.agent_impl;

import sun.misc.Unsafe;

/**
 * Size of a memory access which is using a primitive or {@code Object} value.
 */
// This assumes that for `Unsafe` methods only size of data type matters, and it is for example possible to
// read a `float` (= 4 bytes) as `int` (= 4 bytes).
public enum MemorySize {
    BOOLEAN,
    BYTE_1,
    BYTE_2,
    BYTE_4,
    BYTE_8,
    ADDRESS,
    OBJECT,
    ;

    /**
     * Gets the number of bytes this memory size represents.
     *
     * @throws BadMemoryAccessError
     *      If this 'memory size' has no defined number of bytes
     */
    int getBytesCount() {
        return switch (this) {
            // TODO: Is this correct for boolean?
            case BOOLEAN, BYTE_1 -> 1;
            case BYTE_2 -> 2;
            case BYTE_4 -> 4;
            case BYTE_8 -> 8;
            case ADDRESS -> Unsafe.ADDRESS_SIZE;
            case OBJECT -> {
                // It looks like it is for example possible to store an Object reference in a `byte[]`; though not
                // sure if that is safe and if there are guarantees how large an Object reference actually is
                // Doing this most likely breaks garbage collection for the object
                BadMemoryAccessError.reportError("Using Object in the context of bytes");
                // As fallback use `ARRAY_OBJECT_INDEX_SCALE`; that might be wrong though
                yield Unsafe.ARRAY_OBJECT_INDEX_SCALE;
            }
        };
    }

    /**
     * Gets the memory size a value of type {@code c} takes up.
     */
    public static MemorySize fromClass(Class<?> c) {
        if (c == boolean.class) {
            return MemorySize.BOOLEAN;
        }
        if (c == byte.class) {
            return MemorySize.BYTE_1;
        }
        if (c == char.class || c == short.class) {
            return MemorySize.BYTE_2;
        }
        if (c == int.class || c == float.class) {
            return MemorySize.BYTE_4;
        }
        if (c == long.class || c == double.class) {
            return MemorySize.BYTE_8;
        }
        return MemorySize.OBJECT;
    }
}
