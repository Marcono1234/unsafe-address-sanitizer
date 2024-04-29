package marcono1234.unsafe_sanitizer.agent_impl;

import marcono1234.unsafe_sanitizer.agent_impl.UninitializedMemoryTracker.Section;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UninitializedMemoryTrackerTest {
    @Test
    void setInitialized() {
        var tracker = new UninitializedMemoryTracker();
        assertEquals(List.of(), tracker.getAllInitializedSections());

        tracker.setInitialized(1, 1);
        assertEquals(List.of(new Section(1, 1)), tracker.getAllInitializedSections());

        tracker.setInitialized(6, 10);
        assertEquals(
            List.of(
                new Section(1, 1),
                new Section(6, 10)
            ),
            tracker.getAllInitializedSections()
        );

        // Marking already initialized should have no effect
        tracker.setInitialized(6, 10);
        assertEquals(
            List.of(
                new Section(1, 1),
                new Section(6, 10)
            ),
            tracker.getAllInitializedSections()
        );

        // Marking subsection of already initialized should have no effect
        tracker.setInitialized(7, 3);
        assertEquals(
            List.of(
                new Section(1, 1),
                new Section(6, 10)
            ),
            tracker.getAllInitializedSections()
        );

        tracker.setInitialized(3, 2);
        assertEquals(
            List.of(
                new Section(1, 1),
                new Section(3, 2),
                new Section(6, 10)
            ),
            tracker.getAllInitializedSections()
        );
    }

    @Test
    void setInitialized_Enlarge() {
        var tracker = new UninitializedMemoryTracker();
        tracker.setInitialized(1, 2);
        tracker.setInitialized(1, 5);
        assertEquals(List.of(new Section(1, 5)), tracker.getAllInitializedSections());
    }

    @Test
    void setInitialized_Merge() {
        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(1, 2);
            tracker.setInitialized(4, 2);
            tracker.setInitialized(7, 2);

            // Merge sections
            tracker.setInitialized(2, 6);
            assertEquals(List.of(new Section(1, 8)), tracker.getAllInitializedSections());
        }

        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(1, 8);
            tracker.setInitialized(10, 2);

            // Merge sections
            tracker.setInitialized(0, 11);
            assertEquals(List.of(new Section(0, 12)), tracker.getAllInitializedSections());
        }

        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(1, 3);
            tracker.setInitialized(5, 2);

            // Replace sections
            tracker.setInitialized(0, 10);
            assertEquals(List.of(new Section(0, 10)), tracker.getAllInitializedSections());
        }

        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(1, 3);
            tracker.setInitialized(5, 2);
            tracker.setInitialized(20, 2);

            // Replace sections, but leave subsequent section untouched
            tracker.setInitialized(0, 10);
            assertEquals(
                List.of(
                    new Section(0, 10),
                    new Section(20, 2)
                ),
                tracker.getAllInitializedSections()
            );
        }
    }

    @Test
    void setInitialized_MergeAdjacent() {
        var tracker = new UninitializedMemoryTracker();

        tracker.setInitialized(1, 2);
        tracker.setInitialized(4, 2);
        tracker.setInitialized(7, 2);

        // Merge sections
        tracker.setInitialized(3, 4); // 3 to 7 (exclusive)
        assertEquals(List.of(new Section(1, 8)), tracker.getAllInitializedSections());
    }

    // Note: This test is slightly slower than the others, but the performance problem seems to be caused by
    // creating the `expectedSections` and comparing it; not by the tested code itself
    @Test
    void setInitialized_Many() {
        var tracker = new UninitializedMemoryTracker();

        List<Section> sectionsToAdd = new ArrayList<>();
        for (int i = 0; i < UninitializedMemoryTracker.INITIAL_CAPACITY * 5; i++) {
            // `* 2` to avoid merging adjacent sections
            sectionsToAdd.add(new Section(i * 2, 1));
        }
        // TODO: Maybe make the seed of Random a parameter of this test method and then convert to `@ParameterizedTest`;
        //   this assumes that Gradle prints the parameter values, at least for test failures (is that actually the case?)
        Random random = new Random();
        Set<Section> expectedSections = new TreeSet<>(Comparator.comparingLong(Section::address));

        while (!sectionsToAdd.isEmpty()) {
            int i = random.nextInt(sectionsToAdd.size());
            Section section = sectionsToAdd.remove(i);
            expectedSections.add(section);

            tracker.setInitialized(section.address(), section.bytesCount());
            assertEquals(new ArrayList<>(expectedSections), tracker.getAllInitializedSections());
        }
    }

    @Test
    void setInitialized_ManyMerge() {
        var tracker = new UninitializedMemoryTracker();

        List<Section> sectionsToAdd = new ArrayList<>();
        for (int i = 0; i < UninitializedMemoryTracker.INITIAL_CAPACITY * 5; i++) {
            // `bytesCount = 2` to cover adjacent and overlapping merging
            sectionsToAdd.add(new Section(i, 2));
        }
        // TODO: Maybe make the seed of Random a parameter of this test method and then convert to `@ParameterizedTest`;
        //   this assumes that Gradle prints the parameter values, at least for test failures (is that actually the case?)
        Random random = new Random();
        Collections.shuffle(sectionsToAdd, random);

        for (Section section : sectionsToAdd) {
            tracker.setInitialized(section.address(), section.bytesCount());
            // Cannot easily verify intermediate results; just verify that there are sections
            assertFalse(tracker.getAllInitializedSections().isEmpty());
        }
        // Verify that in the end a single merged section should exist
        assertEquals(List.of(new Section(0, sectionsToAdd.size() + 1)), tracker.getAllInitializedSections());
    }

    @Test
    void clearInitialized() {
        var tracker = new UninitializedMemoryTracker();
        tracker.clearInitialized(1, 10);
        assertEquals(List.of(), tracker.getAllInitializedSections());

        tracker.setInitialized(1, 10);
        tracker.clearInitialized(1, 10);
        assertEquals(List.of(), tracker.getAllInitializedSections());

        tracker.setInitialized(1, 5);
        // Adjacent clear should have no effect
        tracker.clearInitialized(0, 1);
        assertEquals(List.of(new Section(1, 5)), tracker.getAllInitializedSections());
        tracker.clearInitialized(6, 10);
        assertEquals(List.of(new Section(1, 5)), tracker.getAllInitializedSections());
    }

    @Test
    void clearInitialized_TruncateStart() {
        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(1, 10);
            tracker.clearInitialized(1, 8);
            assertEquals(List.of(new Section(9, 2)), tracker.getAllInitializedSections());
        }

        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(1, 10);
            // Should also work when cleared section starts in front
            tracker.clearInitialized(0, 8);
            assertEquals(List.of(new Section(8, 3)), tracker.getAllInitializedSections());
        }
    }

    @Test
    void clearInitialized_TruncateEnd() {
        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(1, 10);
            tracker.clearInitialized(3, 8);
            assertEquals(List.of(new Section(1, 2)), tracker.getAllInitializedSections());
        }

        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(1, 10);
            // Should also work when cleared section ends behind
            tracker.clearInitialized(3, 20);
            assertEquals(List.of(new Section(1, 2)), tracker.getAllInitializedSections());
        }
    }

    @Test
    void clearInitialized_Cut() {
        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(1, 10);
            tracker.clearInitialized(3, 4);
            assertEquals(
                List.of(
                    new Section(1, 2),
                    new Section(7, 4)
                ),
                tracker.getAllInitializedSections()
            );
        }

        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(1, 2);
            tracker.setInitialized(4, 2);
            tracker.setInitialized(7, 2);
            tracker.setInitialized(10, 2);
            tracker.clearInitialized(2, 9);
            assertEquals(
                List.of(
                    new Section(1, 1),
                    new Section(11, 1)
                ),
                tracker.getAllInitializedSections()
            );
        }
    }

    @Test
    void removeSection_Many() {
        var tracker = new UninitializedMemoryTracker();

        List<Section> allSections = new ArrayList<>();
        for (int i = 0; i < UninitializedMemoryTracker.INITIAL_CAPACITY * 5; i++) {
            // `* 2` to avoid merging adjacent sections
            allSections.add(new Section(i * 2, 1));
            tracker.setInitialized(i * 2, 1);
        }

        // TODO: Maybe make the seed of Random a parameter of this test method and then convert to `@ParameterizedTest`;
        //   this assumes that Gradle prints the parameter values, at least for test failures (is that actually the case?)
        Random random = new Random();
        while (!allSections.isEmpty()) {
            int i = random.nextInt(allSections.size());
            Section section = allSections.remove(i);
            tracker.clearInitialized(section.address(), section.bytesCount());

            assertEquals(allSections, tracker.getAllInitializedSections());
        }
    }

    @Test
    void removeSection_ManySplit() {
        var tracker = new UninitializedMemoryTracker();

        // `bytesCount = 4` to cover removing multiple sections
        int bytesCountPerRemove = 4;
        List<Section> sectionsToRemove = new ArrayList<>();
        for (int i = 0; i < UninitializedMemoryTracker.INITIAL_CAPACITY * 5; i++) {
            sectionsToRemove.add(new Section(i, bytesCountPerRemove));
        }

        // TODO: Maybe make the seed of Random a parameter of this test method and then convert to `@ParameterizedTest`;
        //   this assumes that Gradle prints the parameter values, at least for test failures (is that actually the case?)
        Random random = new Random();
        Collections.shuffle(sectionsToRemove, random);

        // First fully initialize sections
        tracker.setInitialized(0, sectionsToRemove.size());
        // Then one by one remove sections
        for (int i = 0; i < sectionsToRemove.size(); i++) {
            // Verify that for the first `clear` calls sections are not immediately empty afterwards
            // Consider `bytesCountPerRemove` in case randomly shuffled removals already cleared all sections
            // before all removals have been performed
            if (i < sectionsToRemove.size() / (bytesCountPerRemove + 1)) {
               assertFalse(tracker.getAllInitializedSections().isEmpty());
            }

            Section section = sectionsToRemove.get(i);
            tracker.clearInitialized(section.address(), section.bytesCount());
            // Cannot easily verify intermediate results
        }
        // Verify that in the end all sections should have been removed
        assertEquals(List.of(), tracker.getAllInitializedSections());
    }

    @Test
    void copyInitialized() {
        var tracker = new UninitializedMemoryTracker();
        tracker.copyInitialized(1, 10, 5);
        assertEquals(List.of(), tracker.getAllInitializedSections());

        tracker.setInitialized(2, 3);
        tracker.copyInitialized(2, 6, 3);
        assertEquals(
            List.of(
                new Section(2, 3),
                new Section(6, 3)
            ),
            tracker.getAllInitializedSections()
        );

        // Copy partially from two sections
        tracker.copyInitialized(3, 10, 5);
        assertEquals(
            List.of(
                new Section(2, 3),
                new Section(6, 3),
                new Section(10, 2),
                new Section(13, 2)
            ),
            tracker.getAllInitializedSections()
        );

        // Merge sections due to copy
        tracker.copyInitialized(4, 5, 1);
        assertEquals(
            List.of(
                new Section(2, 7),
                new Section(10, 2),
                new Section(13, 2)
            ),
            tracker.getAllInitializedSections()
        );
    }

    @Test
    void copyInitialized_Cut() {
        var tracker = new UninitializedMemoryTracker();
        tracker.setInitialized(2, 5);
        tracker.setInitialized(10, 1);

        tracker.copyInitialized(10, 3, 2);
        assertEquals(
            List.of(
                new Section(2, 2),
                new Section(5, 2),
                new Section(10, 1)
            ),
            tracker.getAllInitializedSections()
        );

        tracker.copyInitialized(20, 3, 15);
        assertEquals(List.of(new Section(2, 1)), tracker.getAllInitializedSections());
    }

    @Test
    void copyInitialized_Enlarge() {
        var tracker = new UninitializedMemoryTracker();
        tracker.setInitialized(2, 3);
        tracker.setInitialized(10, 1);

        tracker.copyInitialized(3, 10, 2);
        assertEquals(
            List.of(
                new Section(2, 3),
                new Section(10, 2)
            ),
            tracker.getAllInitializedSections()
        );

        tracker.copyInitialized(2, 11, 3);
        assertEquals(
            List.of(
                new Section(2, 3),
                new Section(10, 4)
            ),
            tracker.getAllInitializedSections()
        );
    }

    @Test
    void copyInitialized_Overlap() {
        var tracker = new UninitializedMemoryTracker();
        tracker.setInitialized(2, 2);
        tracker.setInitialized(6, 2);

        tracker.copyInitialized(2, 3, 6);
        assertEquals(
            List.of(
                new Section(2, 3),
                new Section(7, 2)
            ),
            tracker.getAllInitializedSections()
        );
    }

    @Test
    void moveInitialized() {
        var tracker = new UninitializedMemoryTracker();
        tracker.moveInitialized(1, 4, 10, 4);
        assertEquals(List.of(), tracker.getAllInitializedSections());

        tracker.setInitialized(2, 4);
        tracker.moveInitialized(2, 4, 6, 4);
        assertEquals(List.of(new Section(6, 4)), tracker.getAllInitializedSections());

        // Splits source
        tracker.moveInitialized(7, 2, 12, 2);
        assertEquals(
            List.of(
                new Section(6, 1),
                new Section(9, 1),
                new Section(12, 2)
            ),
            tracker.getAllInitializedSections()
        );

        // Splits source and merges at destination
        tracker.moveInitialized(7, 6, 0, 6);
        assertEquals(
            List.of(
                new Section(2, 1),
                new Section(5, 2),
                new Section(13, 1)
            ),
            tracker.getAllInitializedSections()
        );
    }

    @Test
    void moveInitialized_Shrink() {
        var tracker = new UninitializedMemoryTracker();
        tracker.setInitialized(0, 4);
        tracker.moveInitialized(0, 4, 6, 2);
        assertEquals(List.of(new Section(6, 2)), tracker.getAllInitializedSections());
    }

    @Test
    void moveInitialized_Enlarge() {
        var tracker = new UninitializedMemoryTracker();
        tracker.setInitialized(0, 2);
        tracker.setInitialized(6, 4);

        tracker.moveInitialized(0, 2, 3, 10);
        assertEquals(
            List.of(
                new Section(3, 2),
                // Existing section should have remained unmodified
                // (this is not actually possible for `Unsafe.reallocateMemory` because destination should not
                // overlap with existing section, but this is only validated by `MemorySectionMap` and not here)
                new Section(6, 4)
            ),
            tracker.getAllInitializedSections()
        );
    }

    @Test
    void moveInitialized_Overlap() {
        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(0, 4);
            // Move forward
            tracker.moveInitialized(0, 4, 2, 4);
            assertEquals(List.of(new Section(2, 4)), tracker.getAllInitializedSections());
        }

        {
            var tracker = new UninitializedMemoryTracker();
            tracker.setInitialized(4, 4);
            // Move backward
            tracker.moveInitialized(4, 4, 2, 4);
            assertEquals(List.of(new Section(2, 4)), tracker.getAllInitializedSections());
        }
    }

    @Test
    void isInitialized() {
        var tracker = new UninitializedMemoryTracker();
        tracker.setInitialized(1, 3);
        tracker.setInitialized(5, 2);

        assertTrue(tracker.isInitialized(1, 1));
        assertTrue(tracker.isInitialized(1, 3));
        assertTrue(tracker.isInitialized(2, 1));
        assertTrue(tracker.isInitialized(5, 2));

        assertFalse(tracker.isInitialized(0, 1));
        // Only partially initialized
        assertFalse(tracker.isInitialized(0, 2));
        assertFalse(tracker.isInitialized(3, 2));
        // Start and end are in initialized sections, but middle is not
        assertFalse(tracker.isInitialized(3, 4));
    }
}