package marcono1234.unsafe_sanitizer.agent_impl;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Map for memory sections which consist of an {@code address} and a {@code size}.
 */
class MemorySectionMap {
    /*
     * Implementation note:
     * This is basically a navigable long-to-long map, implemented using two `long[]` arrays
     * (one storing keys, the other values) and binary search.
     *
     * Might not be super efficient, but avoids pulling in multiple MB large third-party libraries,
     * and also for example fastutil has no navigable map for primitive types yet, see
     * https://github.com/vigna/fastutil/issues/33.
     */

    @VisibleForTesting
    static final int INITIAL_CAPACITY = 1024;

    /**
     * Acts as 'key' array with the first {@link #count} entries being keys, and the entries at
     * the same index in {@link #sizes} being their values.
     * This array is sorted.
     */
    private long[] addresses;
    private long[] sizes;

    /**
     * Whether the memory sections at these indices are assumed and required to be always initialized.
     * If {@code false} the {@link #uninitializedMemoryTracker} is used to track which parts of the section
     * (if any) are initialized.
     */
    private boolean[] isAlwaysInitialized;

    private int count;

    // TODO: Maybe also track `StackWalker.StackFrame` of method which performed allocation (to make troubleshooting
    //   easier?)

    @Nullable("if disabled")
    private UninitializedMemoryTracker uninitializedMemoryTracker;

    public MemorySectionMap() {
        addresses = new long[INITIAL_CAPACITY];
        sizes = new long[INITIAL_CAPACITY];
        isAlwaysInitialized = new boolean[INITIAL_CAPACITY];
        count = 0;

        uninitializedMemoryTracker = new UninitializedMemoryTracker();
    }

    public void enableUninitializedMemoryTracking(boolean enabled) {
        if (enabled) {
            if (uninitializedMemoryTracker == null) {
                uninitializedMemoryTracker = new UninitializedMemoryTracker();
            }
        } else {
            uninitializedMemoryTracker = null;
        }
    }

    private static void checkAddress(long address) {
        if (address < 0) {
            throw new IllegalArgumentException("Invalid address: " + address);
        }
    }

    private static void checkAddSize(long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Invalid size: " + size);
        }
    }

    public void addSection(long address, long size, boolean alwaysInitialized) throws IllegalArgumentException {
        checkAddress(address);
        checkAddSize(size);

        int index = Arrays.binarySearch(addresses, 0, count, address);
        if (index >= 0) {
            throw new IllegalArgumentException("Overwriting existing section at address " + address);
        }
        index = -(index + 1);
        // Check for collision with previous section
        if (index > 0) {
            long previousAddress = addresses[index - 1];
            long previousSize = sizes[index - 1];
            // Overflow-safe variant of `previousAddress + previousSize > address`
            if (previousAddress > address - previousSize) {
                throw new IllegalArgumentException("Section at address " + address + " collides with previous section at address " + previousAddress);
            }
        }
        // Check for collision with next section
        if (index < count) {
            long nextAddress = addresses[index];
            // Overflow-safe variant of `address + size > nextAddress`
            if (address > nextAddress - size) {
                throw new IllegalArgumentException("Section at address " + address + " collides with next section at address " + nextAddress);
            }
        }

        // Increase capacity if necessary
        if (count >= addresses.length) {
            int newCapacity = count * 2;
            addresses = Arrays.copyOf(addresses, newCapacity);
            sizes = Arrays.copyOf(sizes, newCapacity);
            isAlwaysInitialized = Arrays.copyOf(isAlwaysInitialized, newCapacity);
        }

        // Can avoid shifting if entry is added at the end (`index == count`)
        if (index < count) {
            int copyCount = count - index;
            // Shift subsequent entries
            System.arraycopy(addresses, index, addresses, index + 1, copyCount);
            System.arraycopy(sizes, index, sizes, index + 1, copyCount);
            System.arraycopy(isAlwaysInitialized, index, isAlwaysInitialized, index + 1, copyCount);
        }

        // Insert new entry
        addresses[index] = address;
        sizes[index] = size;
        // Note: Instead of having a separate array for this, could also consider calling `uninitializedMemoryTracker.setInitialized`,
        // but that might decrease performance of `uninitializedMemoryTracker`
        isAlwaysInitialized[index] = alwaysInitialized;

        count++;
    }

    public boolean hasSectionAt(long address) {
        checkAddress(address);
        return Arrays.binarySearch(addresses, 0, count, address) >= 0;
    }

    /**
     * @param isZeroSized
     *      if {@code true} also allows the address right behind a section, i.e. an 'exclusive end address'
     */
    public void checkIsInSection(long address, boolean isZeroSized) throws IllegalArgumentException {
        checkAccessImpl(address, isZeroSized ? 0 : 1);
    }

    public boolean tryRemoveSection(long address) {
        var removeResult = tryRemoveSectionImpl(address);
        if (removeResult == null) {
            return false;
        }

        if (uninitializedMemoryTracker != null && !removeResult.wasAlwaysInitialized) {
            // Treat memory as uninitialized; even if subsequent allocation happens to obtain same memory region,
            // it should not make any assumptions about the previous content
            uninitializedMemoryTracker.clearInitialized(address, removeResult.size);
        }
        return true;
    }

    public void removeSection(long address) throws IllegalArgumentException {
        if (!tryRemoveSection(address)) {
            throw new IllegalArgumentException("No section at address " + address);
        }
    }

    /**
     * Moves a memory section to a different (potentially overlapping) address, and shrinking or enlarging
     * the section. Acts like a {@code reallocateMemory}.
     *
     * <p>There must not be an existing section at the destination address.
     */
    public void moveSection(long srcAddress, long destAddress, long destSize) {
        checkAddress(srcAddress);
        checkAddress(destAddress);
        checkAddSize(destSize);

        var removeResult = tryRemoveSectionImpl(srcAddress);
        if (removeResult == null) {
            throw new IllegalArgumentException("No section at address " + srcAddress);
        }
        boolean alwaysInitialized = removeResult.wasAlwaysInitialized;
        addSection(destAddress, destSize, alwaysInitialized);
        if (uninitializedMemoryTracker != null && !alwaysInitialized) {
            uninitializedMemoryTracker.moveInitialized(srcAddress, removeResult.size, destAddress, destSize);
        }
    }

    private record RemoveResult(long size, boolean wasAlwaysInitialized) {}

    @Nullable
    private RemoveResult tryRemoveSectionImpl(long address) throws IllegalArgumentException {
        checkAddress(address);

        int index = Arrays.binarySearch(addresses, 0, count, address);
        if (index < 0) {
            return null;
        }
        long size = sizes[index];
        boolean wasAlwaysInitialized = isAlwaysInitialized[index];

        // Can avoid shifting if entry is the last (or only) one (`index == count - 1`);
        // will be implicitly removed by decrementing `count`
        if (index < count - 1) {
            int copyCount = count - index - 1;
            // Remove entry by shifting subsequent entries
            System.arraycopy(addresses, index + 1, addresses, index, copyCount);
            System.arraycopy(sizes, index + 1, sizes, index, copyCount);
            System.arraycopy(isAlwaysInitialized, index + 1, isAlwaysInitialized, index, copyCount);
        }
        count--;

        // Check if arrays should be shrunken
        int newCapacity = addresses.length / 2;
        if (count * 4 < addresses.length && newCapacity >= INITIAL_CAPACITY) {
            addresses = Arrays.copyOf(addresses, newCapacity);
            sizes = Arrays.copyOf(sizes, newCapacity);
            isAlwaysInitialized = Arrays.copyOf(isAlwaysInitialized, newCapacity);
        }

        return new RemoveResult(size, wasAlwaysInitialized);
    }

    public void performAccess(long address, long size, boolean isRead) throws IllegalArgumentException {
        int sectionIndex = checkAccessImpl(address, size);
        if (size == 0) {
            // For size 0 only validate the address with the check above
            return;
        }

        if (uninitializedMemoryTracker != null) {
            if (isRead) {
                if (!isAlwaysInitialized[sectionIndex] && !uninitializedMemoryTracker.isInitialized(address, size)) {
                    throw new IllegalArgumentException("Trying to read uninitialized data at address " + address + ", size " + size);
                }
            } else {
                if (!isAlwaysInitialized[sectionIndex]) {
                    uninitializedMemoryTracker.setInitialized(address, size);
                }
            }
        }
    }

    public void performCopyAccess(long srcAddress, long destAddress, long size) throws IllegalArgumentException {
        // Check access without checking if memory is initialized
        int srcSectionIndex = checkAccessImpl(srcAddress, size);
        int destSectionIndex = checkAccessImpl(destAddress, size);
        if (size == 0) {
            // For size 0 only validate the addresses with the checks above
            return;
        }

        if (uninitializedMemoryTracker != null) {
            if (isAlwaysInitialized[destSectionIndex]) {
                // If destination is assumed to be always initialized, then require that source is fully initialized
                if (!isAlwaysInitialized[srcSectionIndex] && !uninitializedMemoryTracker.isInitialized(srcAddress, size)) {
                    throw new IllegalArgumentException("Trying to copy uninitialized data from address " + srcAddress + ", size " + size);
                }
            } else {
                if (isAlwaysInitialized[srcSectionIndex]) {
                    // src was always initialized, so dest is now fully initialized
                    uninitializedMemoryTracker.setInitialized(destAddress, size);
                } else {
                    uninitializedMemoryTracker.copyInitialized(srcAddress, destAddress, size);
                }
            }
        }
    }

    // TODO: Maybe just use return value instead of throwing? But cannot convey reason for invalid arguments then
    /**
     * @return the section index
     */
    private int checkAccessImpl(long address, long size) throws IllegalArgumentException {
        checkAddress(address);
        if (size < 0) {
            throw new IllegalArgumentException("Invalid size: " + size);
        }

        int index = Arrays.binarySearch(addresses, 0, count, address);
        // Section starts at same address, only need to compare sizes
        if (index >= 0) {
            long actualSize = sizes[index];
            if (size > actualSize) {
                throw new IllegalArgumentException("Size " + size + " exceeds actual size " + actualSize + " at address " + address);
            }
        }
        // Otherwise need to check size of section which starts before the address
        else {
            index = -(index + 1);
            index--; // check previous section
            if (index < 0) {
                throw new IllegalArgumentException("Access outside of section, at address " + address);
            }

            long previousAddress = addresses[index];
            long previousSize = sizes[index];
            // Overflow-safe variant of `previousAddress + previousSize < address + size`
            if (previousAddress - size < address - previousSize) {
                throw new IllegalArgumentException("Access outside of section at address " + address + ", size " + size + " (previous section: address " + previousAddress + ", size " + previousSize + ")");
            }
        }

        return index;
    }

    public record Section(long address, long bytesCount) {}

    public boolean isEmpty() {
        return count == 0;
    }

    public List<Section> getAllSections() {
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sections.add(new Section(addresses[i], sizes[i]));
        }
        return sections;
    }

    /**
     * Does not include sections which are assumed to be {@linkplain #isAlwaysInitialized always initialized}.
     */
    @VisibleForTesting
    List<UninitializedMemoryTracker.Section> getAllInitializedSections() {
        if (uninitializedMemoryTracker == null) {
            throw new IllegalStateException("Uninitialized memory tracking is disabled");
        }
        return uninitializedMemoryTracker.getAllInitializedSections();
    }

    public void clearAllSections() {
        // Note: This can be implemented more efficiently (but also more error-prone?), but this
        // implementation should be fine for now
        for (Section section : getAllSections()) {
            removeSection(section.address);
        }
    }

    @Override
    public String toString() {
        return getAllSections().toString();
    }
}
