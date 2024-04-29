package marcono1234.unsafe_sanitizer;

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
}
