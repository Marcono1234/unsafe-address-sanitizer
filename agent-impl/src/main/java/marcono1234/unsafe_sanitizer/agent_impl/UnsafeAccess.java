package marcono1234.unsafe_sanitizer.agent_impl;

import sun.misc.Unsafe;

/**
 * Exposes an instance of {@link Unsafe} through {@link #unsafe}.
 */
class UnsafeAccess {
    private UnsafeAccess() {
    }

    public static final Unsafe unsafe;
    static {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed getting Unsafe", e);
        }
    }

    public static int arrayIndexScale(Class<?> arrayClass) {
        int scale = unsafe.arrayIndexScale(arrayClass);
        // `arrayIndexScale` says the scale can be 0, but in that case Unsafe access and sanitization
        // won't work properly
        if (scale <= 0) {
            throw new AssertionError("Unsupported index scale " + scale + " for " + arrayClass);
        }
        return scale;
    }

    // Uses own `arrayIndexScale` to verify that scale is supported (should always be the case for byte[] though)
    public static int ARRAY_BYTE_INDEX_SCALE = arrayIndexScale(byte[].class);
}
