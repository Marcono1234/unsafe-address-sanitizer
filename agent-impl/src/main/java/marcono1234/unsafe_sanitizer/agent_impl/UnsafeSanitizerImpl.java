package marcono1234.unsafe_sanitizer.agent_impl;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static marcono1234.unsafe_sanitizer.agent_impl.BadMemoryAccessError.reportError;

/**
 * Actual implementation of the sanitizer, providing the methods used by the public API.
 */
public class UnsafeSanitizerImpl {
    private UnsafeSanitizerImpl() {}

    private static class ScopedData {
        // Technically this does not have to be `AtomicReference` since ScopedData is thread-local, but
        // having it as `AtomicReference` allows it to be used with `getLastErrorRef()`
        final AtomicReference<BadMemoryAccessError> lastError = new AtomicReference<>(null);
        /** Memory tracker in this scope; might be {@code null} if no native memory tracking should be performed */
        @Nullable
        final MemoryTracker memoryTracker;

        public ScopedData(@Nullable MemoryTracker memoryTracker) {
            this.memoryTracker = memoryTracker;
        }
    }

    /** Contains {@code null} if scoped tracking has not been used yet */
    private static final AtomicReference<ThreadLocal<ScopedData>> scopedData = new AtomicReference<>(null);

    private static final AtomicReference<BadMemoryAccessError> lastError = new AtomicReference<>(null);
    private static volatile AgentErrorAction errorAction = AgentErrorAction.THROW;
    private static volatile boolean isRequireInitializedOnCopy = false;

    private static volatile boolean isTrackingUninitializedMemory = true;
    /** Global native memory tracker, {@code null} if global tracking is disabled */
    @Nullable
    private static volatile MemoryTracker memoryTracker = new MemoryTracker();
    private static final Set<MemoryTracker> activeScopedMemoryTrackers = ConcurrentHashMap.newKeySet();
    /** Scoped memory trackers which are not used anymore, but which still have allocations */
    private static final Set<MemoryTracker> staleScopedMemoryTrackers = ConcurrentHashMap.newKeySet();

    private static final FieldAccessSanitizer fieldAccessSanitizer = new FieldAccessSanitizer();

    @Nullable
    private static ScopedData getScopedData() {
        var scopedData = UnsafeSanitizerImpl.scopedData.get();
        if (scopedData != null) {
            var data = scopedData.get();
            if (data != null) {
                return data;
            } else {
                // Clear `null` ThreadLocal entry again, see also https://bugs.openjdk.org/browse/JDK-6630585
                scopedData.remove();
            }
        }
        return null;
    }

    @Nullable
    private static MemoryTracker getMemoryTracker() {
        var scopedData = getScopedData();
        if (scopedData != null) {
            return scopedData.memoryTracker;
        }
        return memoryTracker;
    }

    /**
     * Whether the instrumented method should be executed in case of an error, or not and
     * its execution should be skipped (e.g. to prevent a JVM crash).
     *
     * <p>See {@link AgentErrorAction#executeOnError}
     */
    static boolean executeOnError() {
        return errorAction.executeOnError;
    }

    public static void setErrorAction(AgentErrorAction errorAction) {
        UnsafeSanitizerImpl.errorAction = Objects.requireNonNull(errorAction);
    }

    public static AgentErrorAction getErrorAction() {
        return errorAction;
    }

    public static void setIsDebugLogging(boolean isDebugLogging) {
        MethodCallDebugLogger.isEnabled = isDebugLogging;
    }

    static AtomicReference<BadMemoryAccessError> getLastErrorRefImpl() {
        var scopedData = getScopedData();
        if (scopedData != null) {
            return scopedData.lastError;
        }

        return lastError;
    }

    /**
     * Returns a reference to the last error. This reference should only be used to retrieving or clearing
     * the last error. The {@link AtomicReference} should not be stored somewhere.
     */
    // Note: Don't expose `BadMemoryAccessError` as return type since it cannot be used in public API anyway
    // since users won't have access to it
    public static AtomicReference<? extends Error> getLastErrorRef() {
        return getLastErrorRefImpl();
    }

    // Note: Could consider adding an 'auto' mode which enables the check depending on the value
    // of `jdk.internal.misc.Unsafe#unalignedAccess()`; however that assumes the sanitizer is run
    // on the same platform which is also running the application in production, which most likely
    // is not guaranteed to be the case
    public static void setCheckAddressAlignment(boolean checkAlignment) {
        AlignmentChecker.checkAlignment = checkAlignment;
    }

    // TODO: Add corresponding method in public `UnsafeSanitizer`
    /**
     * Sets whether for copying native memory to native memory the source must be fully initialized.
     * If {@code true}, the source must be fully initialized, if {@code false} this is not required
     * and uninitialized sections are copied to the destination.
     *
     * <p>If enabled it allows detecting potential subsequent uninitialized memory access in advance,
     * however risking that there are false positives if there would not actually be any uninitialized
     * read access later on.
     */
    public static void setIsRequireInitializedOnCopy(boolean requireInitialized) {
        isRequireInitializedOnCopy = requireInitialized;
    }

    // No method for enabling this again because otherwise could lead to spurious errors for memory
    // which was allocated while sanitizer was disabled
    public static void disableNativeMemorySanitizer() {
        memoryTracker = null;
    }

    private static void enableUninitializedMemoryTracking() {
        isTrackingUninitializedMemory = true;
        var memoryTracker = getMemoryTracker();
        if (memoryTracker != null) {
            memoryTracker.enableUninitializedMemoryTracking();
        }
    }

    public static void disableUninitializedMemoryTracking() {
        isTrackingUninitializedMemory = false;
        var memoryTracker = getMemoryTracker();
        if (memoryTracker != null) {
            memoryTracker.disableUninitializedMemoryTracking();
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable<E extends Throwable> {
        void run() throws E;
    }

    // Only for testing; otherwise when enabling uninitialized tracking it could lead to spurious errors for memory
    // which was initialized before this was enabled
    @VisibleForTesting
    public static <E extends Throwable> void withUninitializedMemoryTracking(boolean enabled, ThrowingRunnable<E> runnable) throws E {
        boolean wasEnabled = isTrackingUninitializedMemory;
        if (enabled) {
            enableUninitializedMemoryTracking();
        } else {
            disableUninitializedMemoryTracking();
        }

        try {
            runnable.run();
        } finally {
            if (wasEnabled) {
                enableUninitializedMemoryTracking();
            } else {
                disableUninitializedMemoryTracking();
            }
        }
    }

    /**
     * @param trackUninitialized
     *      Whether uninitialized memory should be tracked. {@code null} if the global setting
     *      should be inherited.
     */
    public static <E extends Throwable> void withScopedNativeMemoryTracking(@Nullable Boolean trackUninitialized, ThrowingRunnable<E> runnable) throws IllegalStateException, E {
        Objects.requireNonNull(runnable);
        // Note: Don't use `ThreadLocal.withInitial` here to allow calling `ThreadLocal.get` for peeking
        // in `getScopedData()` without directly creating instance
        var threadLocalData = UnsafeSanitizerImpl.scopedData.updateAndGet(old -> old != null ? old : new ThreadLocal<>());

        var data = threadLocalData.get();
        if (data != null) {
            throw new IllegalStateException("Scope is already active; cannot nest scopes");
        }

        var tracker = new MemoryTracker();
        if (trackUninitialized == null && !isTrackingUninitializedMemory || Boolean.FALSE.equals(trackUninitialized)) {
            tracker.disableUninitializedMemoryTracking();
        }
        activeScopedMemoryTrackers.add(tracker);

        data = new ScopedData(tracker);
        threadLocalData.set(data);

        try {
            runnable.run();

            // Only check for last error if runnable did not throw any error or exception, and only
            // if error action is `AgentErrorAction.THROW`
            var error = getLastErrorRefImpl().getAndSet(null);
            if (errorAction == AgentErrorAction.THROW && error != null) {
                throw new IllegalStateException("Unhandled bad memory access error", error);
            }
        } finally {
            threadLocalData.remove();

            if (tracker.hasAllocations()) {
                staleScopedMemoryTrackers.add(tracker);
            }
            // Remove afterwards to avoid situation where tracker is in neither of the sets
            activeScopedMemoryTrackers.remove(tracker);
        }
    }

    // These methods return `true` if the method should be executed, and `false` if it should be skipped

    public static boolean verifyCanReallocate(long address) {
        var memoryTracker = getMemoryTracker();
        return memoryTracker == null
            || memoryTracker.verifyCanReallocate(address);
    }

    public static boolean verifyValidMemoryAddress(long address) {
        var memoryTracker = getMemoryTracker();
        // `Unsafe#putAddress(java.lang.Object, long, long)` says "or does not point *into* a block";
        // the "into" sounds like an address representing an 'exclusive end address' (= offset + size) is not valid
        boolean isZeroSized = false;
        return memoryTracker == null
            || memoryTracker.verifyValidAddress(address, isZeroSized);
    }

    public static boolean verifyValidAllocationBytesCount(long bytesCount) {
        return MemoryTracker.verifyValidBytesCount(bytesCount);
    }

    private static boolean onAllocatedMemory(long address, long bytesCount, boolean trackUninitialized, boolean isDirectBuffer) {
        var memoryTracker = getMemoryTracker();
        return memoryTracker == null
            || memoryTracker.onAllocatedMemory(address, bytesCount, trackUninitialized, isDirectBuffer);
    }

    public static boolean onAllocatedMemory(long address, long bytesCount, boolean trackUninitialized) {
        return onAllocatedMemory(address, bytesCount, trackUninitialized, false);
    }

    public static boolean onAllocatedDirectBuffer(long address, long bytesCount) {
        // `ByteBuffer.allocateDirect` creates fully initialized memory; no need to track it
        boolean trackUninitialized = false;
        return onAllocatedMemory(address, bytesCount, trackUninitialized, true);
    }

    public static boolean onReallocatedMemory(long oldAddress, long newAddress, long newBytesCount) {
        var memoryTracker = getMemoryTracker();
        return memoryTracker == null
            || memoryTracker.onReallocatedMemory(oldAddress, newAddress, newBytesCount);
    }

    private static boolean freeMemory(long address, boolean isDirectBufferCleaner) {
        // The following allows freeing memory of other scopes if there is no current scope; this covers the
        // case where a cleaner thread frees the memory after garbage collection

        var globalMemoryTracker = UnsafeSanitizerImpl.memoryTracker;
        var memoryTracker = getMemoryTracker();
        if (memoryTracker != null) {
            if (memoryTracker.tryFreeMemory(address, isDirectBufferCleaner)) {
                return true;
            }
            // If there is a tracker for the current scope (which is not the global tracker), it must have been
            // able to free the memory; cannot free memory of other scope
            else if (memoryTracker != globalMemoryTracker) {
                return reportError("Cannot free at address " + address);
            }
        }

        // The following handles freeing locally scoped memory from the global scope

        for (MemoryTracker tracker : activeScopedMemoryTrackers) {
            if (tracker.tryFreeMemory(address, isDirectBufferCleaner)) {
                return true;
            }
        }

        Iterator<MemoryTracker> staleTrackers = staleScopedMemoryTrackers.iterator();
        while (staleTrackers.hasNext()) {
            var tracker = staleTrackers.next();

            if (tracker.tryFreeMemory(address, isDirectBufferCleaner)) {
                // If tracker has no more allocations remove it from stale trackers set
                if (!tracker.hasAllocations()) {
                    staleTrackers.remove();
                }
                return true;
            }
        }

        if (globalMemoryTracker == null) {
            // Assume that allocation had not been tracked
            return true;
        } else if (isDirectBufferCleaner) {
            // If call is by direct ByteBuffer cleaner but freeing failed, assume that buffer had been allocated
            // before sanitizer was installed (e.g. by JDK)
            // This will likely occur because interceptor sees cleaner calls for both JDK- and user-allocated
            // direct ByteBuffers (unlike for Unsafe usage where only the 'public' Unsafe class is intercepted, but
            // not the JDK-internal one)
            return true;
        } else {
            return reportError("Cannot free at address " + address);
        }
    }

    public static boolean freeMemory(long address) {
        return freeMemory(address, false);
    }

    public static boolean freeDirectBufferMemory(long address) {
        return freeMemory(address, true);
    }

    public static boolean onReadAccess(@Nullable Object obj, long address, MemorySize size) {
        return onAccess(obj, address, size, true, null);
    }

    public static boolean onWriteAccess(@Nullable Object obj, long address, MemorySize size, @Nullable Object writtenObject) {
        return onAccess(obj, address, size, false, writtenObject);
    }

    private static boolean onAccess(@Nullable Object obj, long address, MemorySize size, boolean isRead, @Nullable Object writtenObject) {
        // `writtenObject` may only be non-null if this is a write of an Object value
        assert writtenObject == null || (!isRead && size == MemorySize.OBJECT);

        if (obj == null) {
            var memoryTracker = getMemoryTracker();
            // Always get bytes count, even if memory tracker is not set, to fail when reading or writing Object to
            // native memory
            int bytesCount = size.getBytesCount();

            return AlignmentChecker.checkAlignment(obj, address, size)
                && (memoryTracker == null || memoryTracker.onAccess(address, bytesCount, isRead));
        } else if (obj.getClass().isArray()) {
            return ArrayAccessSanitizer.onAccess(obj, address, size, writtenObject);
        } else {
            return fieldAccessSanitizer.checkAccess(obj, address, size, writtenObject);
        }
    }

    public static boolean onReadAccess(@Nullable Object obj, long address, long bytesCount) {
        return onAccess(obj, address, bytesCount, true);
    }

    public static boolean onWriteAccess(@Nullable Object obj, long address, long bytesCount) {
        return onAccess(obj, address, bytesCount, false);
    }

    private static boolean onAccess(@Nullable Object obj, long address, long bytesCount, boolean isRead) {
        if (obj == null) {
            // If `bytesCount` is 0, allow address 0; this assumes that user code might first call `Unsafe#allocateMemory`
            // and then uses the returned address as argument to `copyMemory` or `setMemory`;
            // if the bytes count is 0, then `allocateMemory` will return 0 as address
            if (bytesCount == 0 && address == 0) {
                return true;
            }

            var memoryTracker = getMemoryTracker();
            return memoryTracker == null
                || memoryTracker.onAccess(address, bytesCount, isRead);
        } else if (obj.getClass().isArray() && obj.getClass().getComponentType().isPrimitive()) {
            return ArrayAccessSanitizer.onAccess(obj, address, bytesCount);
        } else {
            // `jdk.internal.misc.Unsafe` requires for `setMemory` and `copyMemory` that obj is primitive array
            return reportError("Unsupported class " + obj.getClass().getTypeName());
        }
    }

    public static boolean onCopy(@Nullable Object srcObj, long srcAddress, @Nullable Object destObj, long destAddress, long bytesCount) {
        // If `isRequireInitializedOnCopy` perform normal access checks further below, requiring that source
        // is fully initialized
        if (!isRequireInitializedOnCopy && srcObj == null && destObj == null) {
            // Native to native memory copy; don't require that source is fully initialized, will just copy
            // uninitialized sections to destination
            var memoryTracker = getMemoryTracker();
            return memoryTracker == null
                || memoryTracker.onCopy(srcAddress, destAddress, bytesCount);
        }
        // For native to non-native require that source is fully initialized, and for non-native to native assume
        // that destination will be fully initialized afterwards
        return onReadAccess(srcObj, srcAddress, bytesCount) && onWriteAccess(destObj, destAddress, bytesCount);
    }

    /**
     * @param forgetMemorySections
     *      whether to 'forget' all known memory sections after checking; this only clears the sections from the
     *      map, it does not try to free the memory
     */
    public static void checkAllNativeMemoryFreed(boolean forgetMemorySections) throws IllegalStateException {
        var memoryTracker = getMemoryTracker();
        if (memoryTracker == null) {
            throw new IllegalStateException("Native memory sanitizer is not enabled");
        } else {
            memoryTracker.checkEverythingFreed(forgetMemorySections);
        }
    }
}
