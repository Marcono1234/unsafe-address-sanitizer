package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.agent_impl.MemorySize;
import marcono1234.unsafe_sanitizer.agent_impl.UnsafeSanitizerImpl;
import net.bytebuddy.asm.Advice.*;
import sun.misc.Unsafe;

import java.lang.invoke.MethodType;

import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;

// Note: To avoid IntelliJ warnings, can mark in IntelliJ methods annotated with `@OnMethodEnter` and `@OnMethodExit`
//   as entrypoint

/**
 * Interceptors for {@link Unsafe}.
 */
// This is an interface to allow writing `class` instead of `public static class` for all nested classes;
// probably acceptable since this is not public API
@SuppressWarnings("ParameterCanBeLocal") // suppress warnings for reassigning `@Return` parameter
interface UnsafeInterceptors {
    long INVALID_ADDRESS = 0;

    /*
     * Notes:
     *   - These methods use `@OnMethodEnter#skipOn` to skip execution in case of `ErrorAction#PRINT_SKIP`;
     *     otherwise it might crash the JVM when the Unsafe method is called with invalid arguments
     *   - There are two `Unsafe` classes, the 'public' `sun.misc.Unsafe` and the JDK-internal `jdk.internal.misc.Unsafe`.
     *     The public Unsafe delegates to the JDK-internal one, and also direct ByteBuffers use the JDK-internal
     *     one, so intercepting it instead of the public one might seem more useful. However, this is not possible
     *     for all methods because some are native and cannot be intercepted. Also, the JDK-internal Unsafe might
     *     have already been used before the agent is installed, so spurious memory access errors would be reported
     *     by the sanitizer because it is not aware of the previously allocated memory.
     *     Therefore intercept only calls to the public Unsafe.
     */

    /**
     * {@link Unsafe#allocateMemory(long)}
     */
    class AllocateMemory {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        public static boolean enter(@Argument(0) long bytesCount) {
            return UnsafeSanitizerImpl.verifyValidAllocationBytesCount(bytesCount);
        }

        @OnMethodExit
        public static void exit(@Argument(0) long bytesCount, @Enter boolean wasExecuted, @Return(readOnly = false) long address) {
            if (!(wasExecuted && UnsafeSanitizerImpl.onAllocatedMemory(address, bytesCount, true))) {
                // Set custom return value as result
                //noinspection UnusedAssignment
                address = INVALID_ADDRESS;
            }
        }
    }

    /**
     * {@link Unsafe#reallocateMemory(long, long)}
     */
    class ReallocateMemory {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        public static boolean enter(@Argument(0) long oldAddress, @Argument(1) long bytesCount) {
            // `oldAddress == 0` acts like allocate instead
            return (oldAddress == 0 || UnsafeSanitizerImpl.verifyCanReallocate(oldAddress))
                && UnsafeSanitizerImpl.verifyValidAllocationBytesCount(bytesCount);
        }

        @OnMethodExit
        public static void exit(@Argument(0) long oldAddress, @Argument(1) long bytesCount, @Enter(readOnly = false) boolean wasExecuted, @Return(readOnly = false) long newAddress) {
            if (wasExecuted) {
                // `oldAddress == 0` acts like allocate instead
                if (oldAddress == 0) {
                    wasExecuted = UnsafeSanitizerImpl.onAllocatedMemory(newAddress, bytesCount, true);
                } else {
                    wasExecuted = UnsafeSanitizerImpl.onReallocatedMemory(oldAddress, newAddress, bytesCount);
                }
            }

            if (!wasExecuted) {
                // Set custom return value as result
                //noinspection UnusedAssignment
                newAddress = INVALID_ADDRESS;
            }
        }
    }

    /**
     * {@link Unsafe#freeMemory(long)}
     */
    class FreeMemory {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        public static boolean enter(@Argument(0) long address) {
            return UnsafeSanitizerImpl.freeMemory(address);
        }
    }

    /**
     * {@link Unsafe#setMemory(Object, long, long, byte)}
     */
    class SetMemoryObject {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        public static boolean enter(@Argument(0) Object obj, @Argument(1) long offset, @Argument(2) long bytesCount) {
            return UnsafeSanitizerImpl.onWriteAccess(obj, offset, bytesCount);
        }
    }

    /**
     * {@link Unsafe#setMemory(long, long, byte)}
     */
    class SetMemoryAddress {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        public static boolean enter(@Argument(0) long address, @Argument(1) long bytesCount) {
            return UnsafeSanitizerImpl.onWriteAccess(null, address, bytesCount);
        }
    }

    /**
     * {@link Unsafe#copyMemory(Object, long, Object, long, long)}
     */
    class CopyMemoryObject {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        public static boolean enter(@Argument(0) Object srcBase, @Argument(1) long srcOffset, @Argument(2) Object destBase, @Argument(3) long destOffset, @Argument(4) long bytesCount) {
            return UnsafeSanitizerImpl.onCopy(srcBase, srcOffset, destBase, destOffset, bytesCount);
        }
    }

    /**
     * {@link Unsafe#copyMemory(long, long, long)}
     */
    class CopyMemoryAddress {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        public static boolean enter(@Argument(0) long srcAddress, @Argument(1) long destAddress, @Argument(2) long bytesCount) {
            return UnsafeSanitizerImpl.onCopy(null, srcAddress, null, destAddress, bytesCount);
        }
    }

    /**
     * All {@code getX} memory methods, except for {@link Unsafe#getAndSetObject(Object, long, Object)}
     * (which is handled by {@link GetAndSetObject}).
     */
    class GetX {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        // Note: Don't use `@Origin Method` because that would create a new `Method` for every call
        public static boolean enter(@Origin("#m") String methodName, @Origin MethodType method, @AllArguments(typing = DYNAMIC) Object[] arguments) {
            Object obj;
            long address;
            // Check index 1 because index 0 is `Unsafe` (receiver type)
            if (method.parameterType(1) == Object.class) {
                obj = arguments[0];
                address = (long) arguments[1];
            } else {
                obj = null;
                address = (long) arguments[0];
            }
            
            MemorySize size;
            if (methodName.equals("getAddress")) {
                size = MemorySize.ADDRESS;
            } else {
                size = MemorySize.fromClass(method.returnType());
            }

            // Technically for the `getAnd...` methods this is also `onWriteAccess`, but that would be mainly relevant
            // for marking memory as initialized, and `onReadAccess` already verifies that memory is initialized
            return UnsafeSanitizerImpl.onReadAccess(obj, address, size);
        }
        
        @OnMethodExit
        public static void exit(@Origin("#m") String methodName, @Enter boolean wasExecuted, @Return(typing = DYNAMIC, readOnly = false) Object result, @StubValue Object resultDefault) {
            if (!wasExecuted) {
                if (methodName.equals("getAddress")) {
                    //noinspection UnusedAssignment
                    result = INVALID_ADDRESS;
                } else {
                    // TODO: Is this explicitly necessary, or does it implicitly get the default value?
                    //noinspection UnusedAssignment
                    result = resultDefault;
                }
            }
        }
    }

    /**
     * {@link Unsafe#getAndSetObject(Object, long, Object)}.
     */
    class GetAndSetObject {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        public static boolean enter(@Argument(0) Object obj, @Argument(1) long offset, @Argument(2) Object newValue) {
            MemorySize size = MemorySize.OBJECT;
            // `onWriteAccess` check is mainly needed to make sure `writtenObject` is valid; everything else is
            // already checked by `onReadAccess` (correct field offset & size)
            return UnsafeSanitizerImpl.onReadAccess(obj, offset, size) && UnsafeSanitizerImpl.onWriteAccess(obj, offset, size, newValue);
        }

        @OnMethodExit
        public static void exit(@Enter boolean wasExecuted, @Return(readOnly = false) Object result) {
            if (!wasExecuted) {
                //noinspection UnusedAssignment
                result = null;
            }
        }
    }

    /**
     * All {@code putX} methods.
     */
    class PutX {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        // Note: Don't use `@Origin Method` because that would create a new `Method` for every call
        public static boolean enter(@Origin("#m") String methodName, @Origin MethodType method, @AllArguments(typing = DYNAMIC) Object[] arguments) {
            Object obj;
            int addressIndex;
            // Check index 1 because index 0 is `Unsafe` (receiver type)
            if (method.parameterType(1) == Object.class) {
                obj = arguments[0];
                addressIndex = 1;
            } else {
                obj = null;
                addressIndex = 0;
            }

            boolean isPutAddressMethod = methodName.equals("putAddress");

            long address = (long) arguments[addressIndex];
            int valueIndex = addressIndex + 1;
            MemorySize size;
            Object writtenObject = null;
            if (isPutAddressMethod) {
                size = MemorySize.ADDRESS;

                // `Unsafe.putAddress` says behavior is undefined if address value does not point to valid allocation
                long addressValue = (long) arguments[valueIndex];
                if (!UnsafeSanitizerImpl.verifyValidMemoryAddress(addressValue)) {
                    return false;
                }
            } else {
                // Additional `+ 1` because index 0 is `Unsafe` (receiver type)
                Class<?> valueClass = method.parameterType(valueIndex + 1);
                size = MemorySize.fromClass(valueClass);
                if (valueClass == Object.class) {
                    writtenObject = arguments[valueIndex];
                }
            }

            return UnsafeSanitizerImpl.onWriteAccess(obj, address, size, writtenObject);
        }
    }

    /**
     * All {@code compareAndSwapX} methods, except for {@link Unsafe#compareAndSwapObject(Object, long, Object, Object)}
     * (which is handled by {@link CompareAndSwapObject}).
     */
    class CompareAndSwapX {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        // Note: Don't use `@Origin Method` because that would create a new `Method` for every call
        public static boolean enter(@Origin("#m") String methodName, @Argument(0) Object obj, @Argument(1) long offset) {
            MemorySize size = switch (methodName) {
                case "compareAndSwapInt" -> MemorySize.BYTE_4;
                case "compareAndSwapLong" -> MemorySize.BYTE_8;
                default -> throw new AssertionError("Unexpected method: " + methodName);
            };
            // Technically this is also `onWriteAccess`, but that would be mainly relevant for marking memory as
            // initialized, and `onReadAccess` already verifies that memory is initialized
            return UnsafeSanitizerImpl.onReadAccess(obj, offset, size);
        }

        @OnMethodExit
        public static void exit(@Enter boolean wasExecuted, @Return(readOnly = false) boolean result) {
            if (!wasExecuted) {
                //noinspection UnusedAssignment
                result = false;
            }
        }
    }

    /**
     * {@link Unsafe#compareAndSwapObject(Object, long, Object, Object)}
     */
    class CompareAndSwapObject {
        @OnMethodEnter(skipOn = OnDefaultValue.class) // -> `return false` = skip
        public static boolean enter(@Argument(0) Object obj, @Argument(1) long offset, @Argument(3) Object newValue) {
            MemorySize size = MemorySize.OBJECT;
            // `onWriteAccess` check is mainly needed to make sure `writtenObject` is valid; everything else is
            // already checked by `onReadAccess` (correct field offset & size)
            return UnsafeSanitizerImpl.onReadAccess(obj, offset, size) && UnsafeSanitizerImpl.onWriteAccess(obj, offset, size, newValue);
        }

        @OnMethodExit
        public static void exit(@Enter boolean wasExecuted, @Return(readOnly = false) boolean result) {
            if (!wasExecuted) {
                //noinspection UnusedAssignment
                result = false;
            }
        }
    }


    /*
        TODO: Maybe cover the following incorrect usage (validation only seems to happen in native code?)

        class Test {
            int i;
            static int s;
        }

        unsafe.arrayBaseOffset(int.class);
        unsafe.arrayIndexScale(int.class);
        unsafe.staticFieldBase(Test.class.getDeclaredField("i"));
        unsafe.staticFieldOffset(Test.class.getDeclaredField("i"));
        unsafe.objectFieldOffset(Test.class.getDeclaredField("s"));
     */
}
