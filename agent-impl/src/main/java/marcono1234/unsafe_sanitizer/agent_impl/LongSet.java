package marcono1234.unsafe_sanitizer.agent_impl;

import org.jetbrains.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * Set of {@code long} values.
 */
class LongSet {
    @VisibleForTesting
    static final int INITIAL_CAPACITY = 64;

    private long[] values;
    private int count;

    public LongSet() {
        values = new long[INITIAL_CAPACITY];
        count = 0;
    }

    private int getIndex(long value) {
        return Arrays.binarySearch(values, 0, count, value);
    }

    /**
     * @return {@code true} if the value was added, {@code false} is the set already contained the value
     */
    public boolean add(long value) {
        int index = getIndex(value);
        if (index >= 0) {
            return false;
        }
        index = -(index + 1);

        // Increase capacity if necessary
        if (count >= values.length) {
            int newCapacity = count * 2;
            values = Arrays.copyOf(values, newCapacity);
        }

        // Can avoid shifting if value is added at the end (`index == count`)
        if (index < count) {
            int copyCount = count - index;
            // Shift subsequent values
            System.arraycopy(values, index, values, index + 1, copyCount);
        }

        // Insert new value
        values[index] = value;
        count++;
        return true;
    }

    public boolean contains(long value) {
        return getIndex(value) >= 0;
    }

    /**
     * @return whether the value existed in the set and was removed
     */
    public boolean remove(long value) {
        int index = getIndex(value);
        if (index < 0) {
            return false;
        }

        // Can avoid shifting if value is the last (or only) one (`index == count - 1`);
        // will be implicitly removed by decrementing `count`
        if (index < count - 1) {
            int copyCount = count - index - 1;
            // Remove value by shifting subsequent entries
            System.arraycopy(values, index + 1, values, index, copyCount);
        }
        count--;

        // Check if arrays should be shrunken
        int newCapacity = values.length / 2;
        if (count * 4 < values.length && newCapacity >= INITIAL_CAPACITY) {
            values = Arrays.copyOf(values, newCapacity);
        }
        return true;
    }

    public void clear() {
        values = new long[INITIAL_CAPACITY];
        count = 0;
    }

    @VisibleForTesting
    long[] getValues() {
        return Arrays.copyOf(values, count);
    }
}
