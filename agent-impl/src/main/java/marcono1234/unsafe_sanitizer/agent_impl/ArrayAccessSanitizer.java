package marcono1234.unsafe_sanitizer.agent_impl;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import sun.misc.Unsafe;

import java.lang.reflect.Array;

import static marcono1234.unsafe_sanitizer.agent_impl.BadMemoryAccessError.reportError;
import static marcono1234.unsafe_sanitizer.agent_impl.UnsafeAccess.unsafe;

/**
 * Checks access on array objects.
 */
class ArrayAccessSanitizer {
    private ArrayAccessSanitizer() {}

    @VisibleForTesting // currently this method only exists to simplify tests
    static boolean onAccess(Object obj, long offset, MemorySize memorySize) {
        return onAccess(obj, offset, memorySize, null);
    }

    /**
     * @param array
     *      array object on which the access is performed
     * @param offset
     *      offset within the array object where the access is performed
     * @param memorySize
     *      size of the access
     * @param writtenObject
     *      the object which is written to the array, in case this is a write access writing an {@code Object};
     *      otherwise {@code null}
     * @return
     *      {@code true} if the access was successful; {@code false} otherwise (in case the sanitizer is not
     *      configured to throw exceptions)
     */
    public static boolean onAccess(Object array, long offset, MemorySize memorySize, @Nullable Object writtenObject) {
        Class<?> arrayClass = array.getClass();
        Class<?> componentClass = arrayClass.getComponentType();

        long bytesCount;
        if (componentClass.isPrimitive()) {
            // Assume that for primitive array primitive memory size is used
            bytesCount = memorySize.getBytesCount();
        } else if (memorySize == MemorySize.OBJECT) {
            // Object access on an object array

            if (writtenObject != null) {
                Class<?> writtenObjectClass = writtenObject.getClass();
                // `Unsafe.putObject` says "Unless the reference x being stored is either null or matches the field
                // type, the results are undefined"; probably applies to array elements as well
                if (!componentClass.isAssignableFrom(writtenObjectClass)) {
                    return reportError("Trying to write " + writtenObjectClass.getTypeName() + " to " + componentClass.getTypeName() + " array");
                }
            }

            // Use the index scale of the object array as access size
            bytesCount = unsafe.arrayIndexScale(arrayClass);
            if (bytesCount <= 0) {
                return reportError("Array class " + arrayClass.getTypeName() + " has unsupported index scale " + bytesCount);
            }
        } else {
            // Trying to read primitive data from object array
            // This is technically possible, but seems error-prone, especially if there are no guarantees how
            // large object references actually are
            return reportError("Bad request for " + memorySize + " from " + arrayClass.getTypeName());
        }

        /*
         * Note: `Unsafe#get` says "the results are undefined if that variable is not in fact of the type returned by this method"
         * That would mean reading a `long` from a `byte[]` is never safe. However, it seems this can actually be safe
         * when the access is aligned. See for example JDK's `java.nio.HeapByteBuffer#putLong`.
         *
         * Therefore, this only checks alignment here.
         */
        return AddressAlignmentChecker.checkAlignment(array, offset, memorySize)
            && onAccessImpl(array, offset, bytesCount);
    }

    public static boolean onAccess(Object obj, long offset, long bytesCount) {
        Class<?> arrayClass = obj.getClass();

        if (!arrayClass.getComponentType().isPrimitive()) {
            // This is technically possible, but seems error-prone, especially if there are no guarantees how
            // large object references actually are
            return reportError("Reading bytes from non-primitive array " + arrayClass.getTypeName());
        }
        return onAccessImpl(obj, offset, bytesCount);
    }

    private static boolean onAccessImpl(Object obj, long offset, long bytesCount) {
        if (offset < 0) {
            return reportError("Invalid offset: " + offset);
        }
        if (bytesCount < 0) {
            return reportError("Invalid size: " + bytesCount);
        }

        Class<?> arrayClass = obj.getClass();
        int arrayLength = Array.getLength(obj);
        long baseOffset;
        long indexScale;
        // Faster path for byte[], which is most commonly used (?)
        if (arrayClass == byte[].class) {
            baseOffset = Unsafe.ARRAY_BYTE_BASE_OFFSET;
            indexScale = Unsafe.ARRAY_BYTE_INDEX_SCALE;
        } else {
            baseOffset = unsafe.arrayBaseOffset(arrayClass);
            indexScale = unsafe.arrayIndexScale(arrayClass);
        }

        if (indexScale <= 0) {
            return reportError("Array class " + arrayClass.getTypeName() + " has unsupported index scale " + indexScale);
        }

        if (offset < baseOffset) {
            return reportError("Bad array access at offset " + offset + "; min offset is " + baseOffset);
        }
        long endOffset = baseOffset + (arrayLength * indexScale);
        // Overflow-safe variant, uses `Long.compareUnsigned` here since `endOffset` might have overflown already (?)
        if (Long.compareUnsigned(offset + bytesCount, endOffset) > 0) {
            return reportError("Bad array access at offset " + offset + ", size " + bytesCount
                + "; exceeds end offset " + Long.toUnsignedString(endOffset));
        }

        // `Unsafe#getInt` documentation sounds like access must be aligned by scale
        if ((offset - baseOffset) % indexScale != 0) {
            return reportError("Bad aligned array access at offset " + offset + " for " + arrayClass.getTypeName());
        }
        // TODO: Should this also check if `offset + bytesCount` is aligned (e.g. reading a byte from a long[])?

        return true;
    }
}
