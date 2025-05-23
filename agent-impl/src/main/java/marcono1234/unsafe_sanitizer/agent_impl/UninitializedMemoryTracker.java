package marcono1234.unsafe_sanitizer.agent_impl;

import org.jetbrains.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tracks whether memory allocated with {@link sun.misc.Unsafe#allocateMemory(long)} and
 * {@link sun.misc.Unsafe#reallocateMemory(long, long)} is read before data has been written there, and it
 * is therefore uninitialized, containing arbitrary data.
 *
 * <p>This does not track whether memory sections exist or where they end. Therefore it should only be used
 * after {@link MemorySectionMap} verified that the access is within an existing memory section.
 */
class UninitializedMemoryTracker {
    /*
     * Implementation note:
     * Because this class is intended to be used in combination with `MemorySectionMap` it does not have
     * to track boundaries of sections. Instead it merges adjacent and overlapping sections. This reduces
     * memory usage and allows initialized memory checks to be performed by only checking if there is one
     * initialized section large enough for the access, and not having to check multiple sections.
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
    private int count;

    public UninitializedMemoryTracker() {
        addresses = new long[INITIAL_CAPACITY];
        sizes = new long[INITIAL_CAPACITY];
        count = 0;
    }

    private static void checkAddress(long address) {
        if (address < 0) {
            throw new IllegalArgumentException("Invalid address: " + address);
        }
    }

    private static void checkBytesCount(long bytesCount) {
        if (bytesCount <= 0) {
            throw new IllegalArgumentException("Invalid bytes count: " + bytesCount);
        }
    }
    
    private void enlargeExistingSection(int index, long address, long newBytesCount) {
        // Simple case for enlarging last section
        if (index == count - 1) {
            sizes[index] = newBytesCount;
            return;
        }
        
        long endAddress = Math.addExact(address, newBytesCount);
        // End index (inclusive) of existing sections which should be merged with the new one
        int endIndex = Arrays.binarySearch(addresses, index + 1, count, endAddress);
        
        if (endIndex >= 0) {
            // Merge with adjacent section
            newBytesCount = Math.addExact(newBytesCount, sizes[endIndex]);
        } else {
            endIndex = -(endIndex + 1);
            endIndex--; // check the previous section
            if (endIndex > index) {
                long previousAddress = addresses[endIndex];
                long previousSize = sizes[endIndex];
                long previousEndAddress = Math.addExact(previousAddress, previousSize);
                
                // Check if new section has to be extended
                if (previousEndAddress > endAddress) {
                    newBytesCount = Math.addExact(newBytesCount, previousEndAddress - endAddress);
                }
            }
        }

        sizes[index] = newBytesCount;
        
        // Check if there is no need to merge or remove subsequent sections
        if (index == endIndex) {
            return;
        }
        
        // Remove merged sections by shifting subsequent sections
        int shiftCount = count - endIndex - 1;
        if (shiftCount > 0) {
            int shiftIndex = endIndex + 1;
            System.arraycopy(addresses, shiftIndex, addresses, index + 1, shiftCount);
            System.arraycopy(sizes, shiftIndex, sizes, index + 1, shiftCount);
        }
        count -= (endIndex - index);
        checkReduceCapacity();
    }
    
    private void checkIncreaseCapacity() {
        if (count >= addresses.length) {
            int newCapacity = count * 2;
            addresses = Arrays.copyOf(addresses, newCapacity);
            sizes = Arrays.copyOf(sizes, newCapacity);
        }
    }

    private void checkReduceCapacity() {
        // Check if arrays should be shrunken
        int newCapacity = addresses.length / 2;
        if (count * 4 < addresses.length && newCapacity >= INITIAL_CAPACITY) {
            addresses = Arrays.copyOf(addresses, newCapacity);
            sizes = Arrays.copyOf(sizes, newCapacity);
        }
    }

    /**
     * Prepend new section at {@code index}.
     */
    private void insertNewSection(int index, long address, long bytesCount) {
        // Simply case for appending section
        if (index == count) {
            checkIncreaseCapacity();
            addresses[index] = address;
            sizes[index] = bytesCount;
            count++;
            return;
        }
        
        long endAddress = Math.addExact(address, bytesCount);
        // End index (inclusive) of existing sections which should be merged with the new one
        int endIndex = Arrays.binarySearch(addresses, index, count, endAddress);

        if (endIndex >= 0) {
            // Merge with adjacent section
            bytesCount = Math.addExact(bytesCount, sizes[endIndex]);
        } else {
            endIndex = -(endIndex + 1);
            endIndex--; // check the previous section
            if (endIndex >= index) {
                long previousAddress = addresses[endIndex];
                long previousSize = sizes[endIndex];
                long previousEndAddress = Math.addExact(previousAddress, previousSize);

                // Check if new section has to be extended
                if (previousEndAddress > endAddress) {
                    bytesCount = Math.addExact(bytesCount, previousEndAddress - endAddress);
                }
            }
        }

        int removeCount = endIndex - index;
        assert removeCount >= -1;
        // Insert new section
        if (removeCount == -1) {
            checkIncreaseCapacity();
            
            // Shift subsequent sections
            int shiftCount = count - index;
            System.arraycopy(addresses, index, addresses, index + 1, shiftCount);
            System.arraycopy(sizes, index, sizes, index + 1, shiftCount);
        }
        // Remove merged sections by shifting subsequent sections
        else if (removeCount > 0) {
            int shiftCount = count - endIndex - 1;
            if (shiftCount > 0) {
                int shiftIndex = endIndex + 1;
                System.arraycopy(addresses, shiftIndex, addresses, index + 1, shiftCount);
                System.arraycopy(sizes, shiftIndex, sizes, index + 1, shiftCount);
            }
        }
        // Otherwise just overwrite existing section
        
        addresses[index] = address;
        sizes[index] = bytesCount;
        count -= removeCount;
        checkReduceCapacity();
    }

    public void setInitialized(long address, long bytesCount) {
        checkAddress(address);
        checkBytesCount(bytesCount);
        
        int startIndex = Arrays.binarySearch(addresses, 0, count, address);
        if (startIndex >= 0) {
            long size = sizes[startIndex];
            // Only enlarge existing section if it is smaller
            if (size < bytesCount) {
                enlargeExistingSection(startIndex, address, bytesCount);
            }
            return;
        }
        
        startIndex = -(startIndex + 1);
        if (startIndex == 0) {
            insertNewSection(startIndex, address, bytesCount);
            return;
        }
        
        int previousIndex = startIndex - 1;
        long previousAddress = addresses[previousIndex];
        long previousSize = sizes[previousIndex];
        // Check if previous section is adjacent or overlaps
        // Overflow-safe variant of `previousAddress + previousSize >= address`
        if (previousAddress >= address - previousSize) {
            // Enlarge the previous section
            long newSize = Math.addExact(address - previousAddress, bytesCount);
            if (newSize > previousSize) {
                enlargeExistingSection(previousIndex, previousAddress, newSize);
            }
        } else {
            insertNewSection(startIndex, address, bytesCount);
        }
    }

    public void copyInitialized(long srcAddress, long destAddress, long bytesCount) {
        checkAddress(srcAddress);
        checkAddress(destAddress);
        checkBytesCount(bytesCount);

        // First determine all new sections, then perform changes
        // This allows overlapping copies; otherwise would erroneously consider section initialized
        // which was just copied there during the copy (and same for uninitialized)
        // Side note: Unsafe#copyMemory does not officially support overlapping copies, but the implementation
        // apparently supports them, see also comment in UnsafeSanitizerImpl#onCopy
        List<Section> newSections = new ArrayList<>();
        
        long endAddress = Math.addExact(srcAddress, bytesCount);
        int startIndex = Arrays.binarySearch(addresses, 0, count, srcAddress);
        if (startIndex < 0) {
            startIndex = -(startIndex + 1);
            int previousIndex = startIndex - 1;
            if (previousIndex >= 0) {
                long previousEndAddress = Math.addExact(addresses[previousIndex], sizes[previousIndex]);
                long copyCount = previousEndAddress - srcAddress;

                if (copyCount >= bytesCount) {
                    // Previous section encloses complete copy source region; simply set destination as initialized
                    setInitialized(destAddress, bytesCount);
                    return;
                }
                // Check if remainder of previous section should be copied
                else if (copyCount > 0) {
                    newSections.add(new Section(destAddress, copyCount));
                }
            }
        }
        
        int endIndex = Arrays.binarySearch(addresses, startIndex, count, endAddress);
        if (endIndex < 0) {
            endIndex = -(endIndex + 1);

            int previousIndex = endIndex - 1;
            // Don't check previous in front of `startIndex`; that has already been checked above
            if (previousIndex >= startIndex) {
                long previousAddress = addresses[previousIndex];
                long previousEndAddress = Math.addExact(previousAddress, sizes[previousIndex]);
                if (previousEndAddress > endAddress) {
                    // Only copy beginning of last section
                    long copyDestAddress = Math.addExact(destAddress, previousAddress - srcAddress);
                    long copyCount = endAddress - previousAddress;
                    newSections.add(new Section(copyDestAddress, copyCount));
                    // Exclude last section
                    endIndex--;
                }
            }
        }

        for (int i = startIndex; i < endIndex; i++) {
            long copyDestAddress = Math.addExact(destAddress, addresses[i] - srcAddress);
            newSections.add(new Section(copyDestAddress, sizes[i]));
        }

        // Clear all initialized sections from destination because copying will also copy over uninitialized
        // sections (i.e. areas not in `newSections`); then afterwards set initialized areas from `newSections`
        clearInitialized(destAddress, bytesCount);

        for (Section newSection : newSections) {
            setInitialized(newSection.address, newSection.bytesCount);
        }
    }

    /**
     * Moves data from one region to another (potentially overlapping) region, which can be smaller or
     * larger than the original region.
     */
    public void moveInitialized(long srcAddress, long srcBytesCount, long destAddress, long destBytesCount) {
        checkAddress(srcAddress);
        checkBytesCount(srcBytesCount);
        checkAddress(destAddress);
        checkBytesCount(destBytesCount);

        long copyCount = Math.min(srcBytesCount, destBytesCount);
        copyInitialized(srcAddress, destAddress, copyCount);

        // Then clear areas of source region which are not inside of dest region
        long srcEndAddress = Math.addExact(srcAddress, srcBytesCount);
        long copyEndAddress = Math.addExact(destAddress, copyCount);

        if (srcEndAddress <= destAddress || copyEndAddress <= srcAddress) {
            // No overlap, clear complete source region
            clearInitialized(srcAddress, srcBytesCount);
        } else {
            long prefixCount = destAddress - srcAddress;
            if (prefixCount > 0) {
                clearInitialized(srcAddress, prefixCount);
            }

            long suffixCount = srcEndAddress - copyEndAddress;
            if (suffixCount > 0) {
                clearInitialized(copyEndAddress, suffixCount);
            }
        }
    }

    public void clearInitialized(long address, long bytesCount) {
        checkAddress(address);
        checkBytesCount(bytesCount);

        long endAddress = Math.addExact(address, bytesCount);
        
        int startIndex = Arrays.binarySearch(addresses, 0, count, address);
        if (startIndex < 0) {
            startIndex = -(startIndex + 1);
            
            int previousIndex = startIndex - 1;
            if (previousIndex >= 0) {
                long previousAddress = addresses[previousIndex];
                long previousSize = sizes[previousIndex];
                long previousEndAddress = Math.addExact(previousAddress, previousSize);

                // Shorten previous size
                if (previousEndAddress >= address) {
                    long leadingSectionSize = previousSize - (previousEndAddress - address);
                    sizes[previousIndex] = leadingSectionSize;
                }

                long trailingSectionSize = previousEndAddress - endAddress;

                if (trailingSectionSize <= 0) {
                    if (startIndex >= count) {
                        // There is no subsequent section
                        return;
                    }
                } else {
                    // Add remainder of previous section which is not cleared
                    setInitialized(endAddress, trailingSectionSize);
                    return;
                }
            }
        }

        // End index (inclusive) of existing sections which should be removed
        // Note: Begin at `startIndex` (instead of `startIndex + 1`) to check if section at `address` (if any) has to
        // be cut instead of being removed completely
        int endIndex = Arrays.binarySearch(addresses, startIndex, count, endAddress);
        if (endIndex >= 0) {
            // Don't remove section at `endIndex` which is adjacent; it will be unaffected by clearing
            endIndex--;
        } else {
            endIndex = -(endIndex + 1);
            endIndex--; // check previous section
            
            if (endIndex >= 0) {
                long previousAddress = addresses[endIndex];
                long previousSize = sizes[endIndex];
                long previousEndAddress = Math.addExact(previousAddress, previousSize);
                
                if (previousEndAddress > endAddress) {
                    // Shorten & shift previous section
                    addresses[endIndex] = endAddress;
                    sizes[endIndex] = previousEndAddress - endAddress;
                    // Don't remove section at `endIndex`
                    endIndex--;
                }
            }
        }
        
        int removeCount = endIndex - startIndex + 1;
        if (removeCount > 0) {
            // Remove sections by shifting subsequent sections
            int shiftCount = count - endIndex - 1;
            if (shiftCount > 0) {
                int shiftIndex = endIndex + 1;
                System.arraycopy(addresses, shiftIndex, addresses, startIndex, shiftCount);
                System.arraycopy(sizes, shiftIndex, sizes, startIndex, shiftCount);
            }
        }
        count -= removeCount;
        checkReduceCapacity();
    }

    public boolean isInitialized(long address, long bytesCount) {
        checkAddress(address);
        checkBytesCount(bytesCount);

        int index = Arrays.binarySearch(addresses, 0, count, address);
        // Section starts at same address, only need to compare sizes
        if (index >= 0) {
            long size = sizes[index];
            return size >= bytesCount;
        }
        // Otherwise need to check size of section which starts before the address
        else {
            index = -(index + 1);
            if (index == 0) {
                // Access occurred in front of first section (if any)
                return false;
            }

            long previousAddress = addresses[index - 1];
            long previousSize = sizes[index - 1];
            // Overflow-safe variant of `previousAddress + previousSize >= address + bytesCount`
            return previousAddress - bytesCount >= address - previousSize;
        }
    }

    public record Section(long address, long bytesCount) {}

    public List<Section> getAllInitializedSections() {
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sections.add(new Section(addresses[i], sizes[i]));
        }
        return sections;
    }

    @Override
    public String toString() {
        return getAllInitializedSections().toString();
    }
}
