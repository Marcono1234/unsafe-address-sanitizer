package marcono1234.unsafe_sanitizer.agent_impl;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

import static marcono1234.unsafe_sanitizer.agent_impl.BadMemoryAccessError.reportError;
import static marcono1234.unsafe_sanitizer.agent_impl.UnsafeAccess.unsafe;

/**
 * Checks access on instance and static fields.
 */
class FieldAccessSanitizer {
    // Implementation must be thread-safe

    private record FieldData(Field field, MemorySize fieldSize) {
    }

    // Two separate maps, one for static fields and one for instance fields, because the way their
    // offset is obtained from `Unsafe` differs, and there is no guarantee that there won't be collisions
    private final Map<Class<?>, Map<Long, FieldData>> staticFieldsCache;
    private final Map<Class<?>, Map<Long, FieldData>> instanceFieldsCache;

    public FieldAccessSanitizer() {
        // Important: Must use fully synchronized maps here, cannot use read-write lock because for WeakHashMap
        // even methods which normally don't mutate map (e.g. `size()`) might remove stale entries
        staticFieldsCache = Collections.synchronizedMap(new WeakHashMap<>());
        instanceFieldsCache = Collections.synchronizedMap(new WeakHashMap<>());
    }

    @VisibleForTesting // currently this method only exists to simplify tests
    boolean checkAccess(Object obj, long offset, MemorySize size) {
        return checkAccess(obj, offset, size, null);
    }

    private static String getFieldDisplayString(Field f) {
        return f.getType().getTypeName() + " " + f.getDeclaringClass().getTypeName() + "#" + f.getName();
    }

    /**
     * @param obj
     *      object on which the access is performed; for static fields the {@link sun.misc.Unsafe#staticFieldBase(Field)}
     * @param offset
     *      offset of the accessed field
     * @param size
     *      size of the access
     * @param writtenObject
     *      the object which is written to the field, in case this is a write access writing an {@code Object};
     *      otherwise {@code null}
     * @return
     *      {@code true} if the access was successful; {@code false} otherwise (in case the sanitizer is not
     *      configured to throw exceptions)
     */
    public boolean checkAccess(Object obj, long offset, MemorySize size, @Nullable Object writtenObject) {
        Objects.requireNonNull(obj);
        Objects.requireNonNull(size);

        if (offset < 0) {
            return reportError("Invalid offset: " + offset);
        }

        FieldData fieldData;
        String classDisplayName;
        if (obj instanceof Class<?> c) {
            /*
             * Object was probably obtained from `sun.misc.Unsafe#staticFieldBase`
             * Note that this is an implementation detail of the HotSpot JVM, see
             * https://github.com/openjdk/jdk/blob/55c1446b68db6c4734420124b5f26278389fdf2b/src/hotspot/share/prims/unsafe.cpp#L533-L553
             * For other JVMs `staticFieldBase` could return something different
             *
             * A more reliable solution might be to intercept calls to `staticFieldBase` and `staticFieldOffset`
             * and record their return values in the `staticFieldsCache` here. But the disadvantage is that all
             * Unsafe calls with Object (even if null?) would have to check then if it might be a static field access.
             */

            classDisplayName = c.getTypeName();
            fieldData = getStaticFieldData(c, offset);
        } else {
            Class<?> c = obj.getClass();
            classDisplayName = c.getTypeName();
            fieldData = getInstanceFieldData(c, offset);
        }

        if (fieldData == null) {
            return reportError("Class " + classDisplayName + " has no field at offset " + offset);
        }

        if (writtenObject != null) {
            Field field = fieldData.field;
            Class<?> writtenObjectClass = writtenObject.getClass();
            // `Unsafe.putObject` says "Unless the reference x being stored is either null or matches the field
            // type, the results are undefined"
            if (!field.getType().isAssignableFrom(writtenObjectClass)) {
                return reportError("Trying to write " + writtenObjectClass + " to field '" + getFieldDisplayString(field) + "'");
            }
        }

        // TODO: Should permit reading smaller? E.g. read `int` as `byte`? (but have to differentiate here then
        //   between read and write)
        //   But `Unsafe#getInt` says "the results are undefined if that variable is not in fact of the type returned by this method"
        //   (though this might be a bit cautious)
        MemorySize fieldSize = fieldData.fieldSize;
        if (fieldSize != size) {
            return reportError("Field '" + getFieldDisplayString(fieldData.field) + "' at offset " + offset
                // Include the `classDisplayName` for the case where the field is declared by a superclass
                + " of class "+ classDisplayName + " has size " + fieldSize + ", not " + size);
        }
        return true;
    }

    private FieldData getStaticFieldData(Class<?> c, long offset) {
        var offsetMap = staticFieldsCache.computeIfAbsent(c, key -> createStaticFieldsOffsetMap(c));
        return offsetMap.get(offset);
    }

    private static Map<Long, FieldData> createStaticFieldsOffsetMap(Class<?> c) {
        Map<Long, FieldData> map = new HashMap<>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                FieldData fieldData = new FieldData(f, MemorySize.fromClass(f.getType()));
                var oldValue = map.put(unsafe.staticFieldOffset(f), fieldData);
                if (oldValue != null) {
                    throw new AssertionError("Duplicate field offset for " + f);
                }
            }
        }

        return map;
    }

    private FieldData getInstanceFieldData(Class<?> c, long offset) {
        var offsetMap = instanceFieldsCache.computeIfAbsent(c, key -> createInstanceFieldsOffsetMap(c));
        return offsetMap.get(offset);
    }

    private static Map<Long, FieldData> createInstanceFieldsOffsetMap(Class<?> c) {
        Map<Long, FieldData> map = new HashMap<>();

        // For simplicity later during lookup, include the fields of all superclasses, instead of
        // having separate entries for them
        for (; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    FieldData fieldData = new FieldData(f, MemorySize.fromClass(f.getType()));
                    var oldValue = map.put(unsafe.objectFieldOffset(f), fieldData);
                    if (oldValue != null) {
                        throw new AssertionError("Duplicate field offset for " + f);
                    }
                }
            }
        }

        return map;
    }
}
