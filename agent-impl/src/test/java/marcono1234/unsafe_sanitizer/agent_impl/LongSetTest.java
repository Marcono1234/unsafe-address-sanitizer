package marcono1234.unsafe_sanitizer.agent_impl;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LongSetTest {

    @Test
    void add() {
        LongSet set = new LongSet();
        assertTrue(set.add(3));
        assertArrayEquals(new long[] {3}, set.getValues());

        assertTrue(set.add(1));
        assertArrayEquals(new long[] {1, 3}, set.getValues());

        assertTrue(set.add(2));
        assertArrayEquals(new long[] {1, 2, 3}, set.getValues());

        assertTrue(set.add(-1));
        assertArrayEquals(new long[] {-1, 1, 2, 3}, set.getValues());

        // Adding already existing value
        assertFalse(set.add(1));
        assertArrayEquals(new long[] {-1, 1, 2, 3}, set.getValues());

        assertTrue(set.add(Long.MIN_VALUE));
        assertArrayEquals(new long[] {Long.MIN_VALUE, -1, 1, 2, 3}, set.getValues());
        assertTrue(set.add(Long.MAX_VALUE));
        assertArrayEquals(new long[] {Long.MIN_VALUE, -1, 1, 2, 3, Long.MAX_VALUE}, set.getValues());
    }

    @Test
    void contains() {
        LongSet set = new LongSet();
        assertFalse(set.contains(1));

        set.add(1);
        assertTrue(set.contains(1));
        assertFalse(set.contains(2));

        set.add(-2);
        assertTrue(set.contains(-2));
        assertFalse(set.contains(-1));
        assertFalse(set.contains(2));

        assertFalse(set.contains(Long.MIN_VALUE));
        set.add(Long.MIN_VALUE);
        assertTrue(set.contains(Long.MIN_VALUE));

        assertFalse(set.contains(Long.MAX_VALUE));
        set.add(Long.MAX_VALUE);
        assertTrue(set.contains(Long.MAX_VALUE));
    }

    @Test
    void remove() {
        LongSet set = new LongSet();
        assertFalse(set.remove(1));
        assertArrayEquals(new long[] {}, set.getValues());

        set.add(1);
        assertArrayEquals(new long[] {1}, set.getValues());
        assertTrue(set.remove(1));
        assertArrayEquals(new long[] {}, set.getValues());
        assertFalse(set.remove(1));

        set.add(-2);
        assertArrayEquals(new long[] {-2}, set.getValues());
        assertTrue(set.remove(-2));
        assertArrayEquals(new long[] {}, set.getValues());
        assertFalse(set.remove(-2));

        assertFalse(set.remove(Long.MIN_VALUE));
        set.add(Long.MIN_VALUE);
        assertArrayEquals(new long[] {Long.MIN_VALUE}, set.getValues());
        assertTrue(set.remove(Long.MIN_VALUE));

        assertFalse(set.remove(Long.MAX_VALUE));
        set.add(Long.MAX_VALUE);
        assertArrayEquals(new long[] {Long.MAX_VALUE}, set.getValues());
        assertTrue(set.remove(Long.MAX_VALUE));
    }

    @Test
    void clear() {
        LongSet set = new LongSet();
        set.clear();
        assertArrayEquals(new long[] {}, set.getValues());

        set.add(1);
        assertArrayEquals(new long[] {1}, set.getValues());
        set.clear();
        assertFalse(set.contains(1));
        assertArrayEquals(new long[] {}, set.getValues());

        set.add(-2);
        set.add(5);
        assertArrayEquals(new long[] {-2, 5}, set.getValues());
        set.clear();
        assertFalse(set.contains(-2));
        assertFalse(set.contains(5));
        assertArrayEquals(new long[] {}, set.getValues());
    }

    @Test
    void addRemove_Many() {
        // TODO: Maybe make the seed of Random a parameter of this test method and then convert to `@ParameterizedTest`;
        //   this assumes that Gradle prints the parameter values, at least for test failures (is that actually the case?)
        Random random = new Random();
        SortedSet<Long> addedValues = new TreeSet<>();
        LongSet longSet = new LongSet();

        while (addedValues.size() < LongSet.INITIAL_CAPACITY * 5) {
            long value = random.nextLong();
            boolean wasAdded = addedValues.add(value);

            assertEquals(wasAdded, !longSet.contains(value));
            assertEquals(wasAdded, longSet.add(value));
            assertTrue(longSet.contains(value));

            assertArrayEquals(addedValues.stream().mapToLong(Long::longValue).toArray(), longSet.getValues());
        }

        List<Long> remainingValues = new LinkedList<>(addedValues);
        while (!remainingValues.isEmpty()) {
            int indexToRemove = random.nextInt(remainingValues.size());
            long valueToRemove = remainingValues.remove(indexToRemove);

            assertTrue(longSet.contains(valueToRemove));
            assertTrue(longSet.remove(valueToRemove));
            assertFalse(longSet.contains(valueToRemove));

            assertArrayEquals(remainingValues.stream().mapToLong(Long::longValue).toArray(), longSet.getValues());
        }
    }
}
