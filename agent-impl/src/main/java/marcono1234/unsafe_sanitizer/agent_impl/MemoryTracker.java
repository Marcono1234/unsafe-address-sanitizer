package marcono1234.unsafe_sanitizer.agent_impl;

import sun.misc.Unsafe;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static marcono1234.unsafe_sanitizer.agent_impl.BadMemoryAccessError.reportError;

/**
 * Tracker of native memory access.
 */
class MemoryTracker {
    // Implementation must be thread-safe

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    /** Known sections of active native memory */
    private final MemorySectionMap sectionsMap = new MemorySectionMap();
    /**
     * Known addresses of active direct {@link java.nio.ByteBuffer}s; only contains addresses of buffers whose
     * {@link java.nio.ByteBuffer#allocateDirect(int)} call was intercepted
     */
    // TODO: Maybe instead of this separate set, store inside `sectionsMap` whether section is from direct ByteBuffer?
    //   But that would make the API of MemorySectionMap more verbose, and its logic more complicated
    private final LongSet directBufferAddresses = new LongSet();

    public void enableUninitializedMemoryTracking(boolean enabled) {
        lock.writeLock().lock();
        try {
            sectionsMap.enableUninitializedMemoryTracking(enabled);
        } finally {
            lock.writeLock().unlock();
        }
    }

    static boolean verifyValidBytesCount(long bytesCount) {
        if (bytesCount < 0) {
            return reportError("Invalid bytes count: " + bytesCount);
        }

        if (Unsafe.ADDRESS_SIZE == 4) {
            // Only allow 32-bit (unsigned) byte count, see `jdk.internal.misc.Unsafe#checkSize`
            if (bytesCount >>> 32 != 0) {
                return reportError("Too large bytes count: " + bytesCount);
            }
        }
        return true;
    }

    /**
     * Informs the tracker that memory has been allocated.
     *
     * @param address
     *      address where the memory was allocated
     * @param bytesCount
     *      size of the allocation
     * @param trackUninitialized
     *      whether for this memory region it should be tracked if the memory is uninitialized
     *      (if {@linkplain #enableUninitializedMemoryTracking(boolean) enabled}); if {@code false} this memory section
     *      is always considered (and required) to be initialized
     * @param isDirectBuffer
     *      whether the memory has been allocated by {@link java.nio.ByteBuffer#allocateDirect(int)}
     *
     * @return
     *      {@code true} if the caller should assume the allocation was successful;
     *      {@code false} if the allocation was considered invalid (but the sanitizer is not
     *      {@linkplain UnsafeSanitizerImpl#setErrorAction(AgentErrorAction) configured} to throw on bad memory access)
     */
    public boolean onAllocatedMemory(long address, long bytesCount, boolean trackUninitialized, boolean isDirectBuffer) {
        // At least `Unsafe.reallocateMemory` says result address will be zero if the size is zero,
        // `Unsafe.unsafe.allocateMemory` says result will never be zero, but it actually seems to
        // behave the same way as `reallocateMemory`
        if (address == 0 && bytesCount == 0) {
            return true;
        }

        // TODO: Maybe these should throw AssertionError or similar, assuming that `Unsafe` never returns incorrect
        //   results for successful allocation
        //   Instead what could happen is that this sanitizer misses calls which free memory

        if (address <= 0) {
            return reportError("Invalid address: " + address);
        } else if (!verifyValidBytesCount(bytesCount)) {
            return false;
        }

        lock.writeLock().lock();
        try {
            sectionsMap.addSection(address, bytesCount, trackUninitialized);

            if (isDirectBuffer) {
                if (!directBufferAddresses.add(address)) {
                    // Probably impossible; `sectionsMap.addSection` call above would have failed already
                    throw new IllegalArgumentException("Direct byte buffer at address " + address + " already exists");
                }
            }
        } catch (IllegalArgumentException e) {
            return reportError(e);
        } finally {
            lock.writeLock().unlock();
        }
        return true;
    }

    /**
     * Verifies that a memory section starts at the address and can be reallocated.
     * Does not check if the memory is initialized.
     */
    public boolean verifyCanReallocate(long address) {
        if (address <= 0) {
            return reportError("Invalid address: " + address);
        }

        lock.readLock().lock();
        try {
            if (directBufferAddresses.contains(address)) {
                return reportError("Trying to reallocate memory of direct ByteBuffer at address " + address);
            }

            if (sectionsMap.hasSectionAt(address)) {
                return true;
            }
            return reportError("No memory section at address " + address);
        } catch (IllegalArgumentException e) {
            return reportError(e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Verifies that the address is inside a section of allocated memory.
     * Does not check if the memory is initialized.
     *
     * @param isZeroSized
     *      if {@code true} also allows the address right behind a section, i.e. an 'exclusive end address'
     */
    public boolean verifyValidAddress(long address, boolean isZeroSized) {
        if (address <= 0) {
            return reportError("Invalid address: " + address);
        }

        lock.readLock().lock();
        try {
            sectionsMap.checkIsInSection(address, isZeroSized);
        } catch (IllegalArgumentException e) {
            return reportError(e);
        } finally {
            lock.readLock().unlock();
        }
        return true;
    }

    public boolean onReallocatedMemory(long oldAddress, long newAddress, long newBytesCount) {
        // `Unsafe.reallocateMemory` says result address will be zero if the size is zero
        if (newAddress == 0 && newBytesCount == 0) {
            return tryFreeMemory(oldAddress, false) || reportError("Failed freeing memory at address " + oldAddress);
        }

        lock.writeLock().lock();
        try {
            sectionsMap.moveSection(oldAddress, newAddress, newBytesCount);
        } catch (IllegalArgumentException e) {
            return reportError(e);
        } finally {
            lock.writeLock().unlock();
        }
        return true;
    }

    /**
     * @param isDirectBufferCleaner
     *      whether this is called by the direct {@link java.nio.ByteBuffer} cleaner
     *
     * @return true if memory could be freed at the given address, false otherwise;
     *      does not throw an error if freeing memory failed
     */
    public boolean tryFreeMemory(long address, boolean isDirectBufferCleaner) {
        if (address == 0) {
            return true;
        }
        if (address < 0) {
            return false;
        }

        lock.writeLock().lock();
        try {
            if (!isDirectBufferCleaner && directBufferAddresses.contains(address)) {
                // Don't permit manually freeing memory of direct ByteBuffer because its cleaner would be
                // unaware of it, and would perform double free when executed on garbage collection
                return reportError("Trying to manually free memory of direct ByteBuffer at address " + address);
            }
            if (isDirectBufferCleaner && !directBufferAddresses.remove(address)) {
                // If call is by direct ByteBuffer cleaner but buffer address is unknown, then either it was not
                // tracked by this tracker, or buffer had been allocated before sanitizer was installed (e.g. by JDK);
                // no need to try removing section
                return false;
            }
            return sectionsMap.tryRemoveSection(address);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean onAccess(long address, long bytesCount, boolean isRead) {
        if (address <= 0) {
            return reportError("Invalid address: " + address);
        } else if (!verifyValidBytesCount(bytesCount)) {
            return false;
        }

        lock.readLock().lock();
        try {
            sectionsMap.performAccess(address, bytesCount, isRead);
        } catch (IllegalArgumentException e) {
            return reportError(e);
        } finally {
            lock.readLock().unlock();
        }
        return true;
    }

    public boolean onCopy(long srcAddress, long destAddress, long bytesCount) {
        if (bytesCount == 0) {
            // Allow address to be right behind section ('exclusive end address') since copy size is 0
            boolean isZeroSized = true;

            // Allow address 0; this assumes that user code might first call `Unsafe#allocateMemory` and then uses
            // the returned address as argument to `copyMemory`;
            // if the bytes count is 0, then `allocateMemory` will return 0 as address
            return (srcAddress == 0 || verifyValidAddress(srcAddress, isZeroSized))
                && (destAddress == 0 || verifyValidAddress(destAddress, isZeroSized));
        }

        if (srcAddress <= 0) {
            return reportError("Invalid srcAddress: " + srcAddress);
        } else if (destAddress <= 0) {
            return reportError("Invalid destAddress: " + destAddress);
        } else if (!verifyValidBytesCount(bytesCount)) {
            return false;
        }

        lock.writeLock().lock();
        try {
            sectionsMap.performCopyAccess(srcAddress, destAddress, bytesCount);
        } catch (IllegalArgumentException e) {
            return reportError(e);
        } finally {
            lock.writeLock().unlock();
        }
        return true;
    }

    public boolean hasAllocations() {
        lock.readLock().lock();
        try {
            return !sectionsMap.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * @param clearSections
     *      whether to clear all sections after checking; this only clears the sections from the map, it does
     *      not try to free the memory
     */
    public void checkEverythingFreed(boolean clearSections) {
        lock.readLock().lock();
        try {
            var sections = sectionsMap.getAllSections();
            if (clearSections) {
                sectionsMap.clearAllSections();
                directBufferAddresses.clear();
            }
            if (!sections.isEmpty()) {
                throw new IllegalStateException("Still contains the following sections: " + sections);
            }
        } finally {
            lock.readLock().unlock();
        }
    }
}
