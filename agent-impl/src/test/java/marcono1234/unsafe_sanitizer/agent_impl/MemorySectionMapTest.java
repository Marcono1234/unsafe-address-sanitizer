package marcono1234.unsafe_sanitizer.agent_impl;

import marcono1234.unsafe_sanitizer.agent_impl.MemorySectionMap.Section;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MemorySectionMapTest {
    private MemorySectionMap map;

    @BeforeEach
    void setUp() {
        map = new MemorySectionMap();
    }

    // For most tests here ignore uninitialized memory tracking
    private static final boolean ALWAYS_INITIALIZED = true;
    private static final boolean IS_READ = true;

    // TODO: Check exception messages?
    private static void assertThrows(Executable executable) {
        Assertions.assertThrows(IllegalArgumentException.class, executable);
    }

    @Test
    void addSection() {
        assertTrue(map.isEmpty());
        map.addSection(0, 1, ALWAYS_INITIALIZED);
        assertFalse(map.isEmpty());
        map.addSection(1, 2, ALWAYS_INITIALIZED);
        map.addSection(3, 1, ALWAYS_INITIALIZED);
        map.addSection(Long.MAX_VALUE, Long.MAX_VALUE, ALWAYS_INITIALIZED);
        map.addSection(10, 1, ALWAYS_INITIALIZED);
        map.addSection(9, 1, ALWAYS_INITIALIZED);

        assertEquals(
            List.of(
                new Section(0, 1),
                new Section(1, 2),
                new Section(3, 1),
                new Section(9, 1),
                new Section(10, 1),
                new Section(Long.MAX_VALUE, Long.MAX_VALUE)
            ),
            map.getAllSections()
        );
    }

    // Note: This test is slightly slower than the others, but the performance problem seems to be caused by
    // creating the `expectedSections` and comparing it; not by the tested code itself
    @Test
    void addSection_Many() {
        List<Section> sectionsToAdd = new ArrayList<>();
        for (int i = 0; i < MemorySectionMap.INITIAL_CAPACITY * 5; i++) {
            sectionsToAdd.add(new Section(i, 1));
        }
        // TODO: Maybe make the seed of Random a parameter of this test method and then convert to `@ParameterizedTest`;
        //   this assumes that Gradle prints the parameter values, at least for test failures (is that actually the case?)
        Random random = new Random();
        Set<Section> expectedSections = new TreeSet<>(Comparator.comparingLong(Section::address));

        while (!sectionsToAdd.isEmpty()) {
            int i = random.nextInt(sectionsToAdd.size());
            Section section = sectionsToAdd.remove(i);
            expectedSections.add(section);

            map.addSection(section.address(), section.bytesCount(), ALWAYS_INITIALIZED);
            assertEquals(new ArrayList<>(expectedSections), map.getAllSections());
        }
    }

    @Test
    void addSection_Error() {
        assertThrows(() -> map.addSection(-1, 1, ALWAYS_INITIALIZED));
        assertEquals(List.of(), map.getAllSections());

        assertThrows(() -> map.addSection(1, 0, ALWAYS_INITIALIZED));
        assertEquals(List.of(), map.getAllSections());

        assertThrows(() -> map.addSection(1, -1, ALWAYS_INITIALIZED));
        assertEquals(List.of(), map.getAllSections());

        map.addSection(1, 2, ALWAYS_INITIALIZED);
        // Should fail adding duplicate or overlapping sections
        assertThrows(() -> map.addSection(1, 2, ALWAYS_INITIALIZED));
        assertEquals(List.of(new Section(1, 2)), map.getAllSections());
        assertThrows(() -> map.addSection(0, 2, ALWAYS_INITIALIZED));
        assertEquals(List.of(new Section(1, 2)), map.getAllSections());
        assertThrows(() -> map.addSection(2, 4, ALWAYS_INITIALIZED));
        assertEquals(List.of(new Section(1, 2)), map.getAllSections());
    }

    @Test
    void removeSection() {
        map.addSection(0, 1, ALWAYS_INITIALIZED);
        map.addSection(1, 2, ALWAYS_INITIALIZED);
        map.addSection(3, 1, ALWAYS_INITIALIZED);

        map.removeSection(3);
        assertEquals(
            List.of(
                new Section(0, 1),
                new Section(1, 2)
            ),
            map.getAllSections()
        );

        map.removeSection(0);
        assertEquals(
            List.of(new Section(1, 2)),
            map.getAllSections()
        );

        assertFalse(map.isEmpty());
        map.removeSection(1);
        assertTrue(map.isEmpty());
        assertEquals(List.of(), map.getAllSections());
    }

    @Test
    void removeSection_Error() {
        assertThrows(() -> map.removeSection(0));

        map.addSection(1, 5, ALWAYS_INITIALIZED);
        // Cannot remove in the middle of a section
        assertThrows(() -> map.removeSection(2));

        assertThrows(() -> map.removeSection(0));
        assertThrows(() -> map.removeSection(6));
        assertEquals(List.of(new Section(1, 5)), map.getAllSections());
    }

    @Test
    void removeSection_Many() {
        List<Section> allSections = new ArrayList<>();
        for (int i = 0; i < MemorySectionMap.INITIAL_CAPACITY * 5; i++) {
            allSections.add(new Section(i, 1));
            map.addSection(i, 1, ALWAYS_INITIALIZED);
        }

        // TODO: Maybe make the seed of Random a parameter of this test method and then convert to `@ParameterizedTest`;
        //   this assumes that Gradle prints the parameter values, at least for test failures (is that actually the case?)
        Random random = new Random();
        while (!allSections.isEmpty()) {
            int i = random.nextInt(allSections.size());
            Section section = allSections.remove(i);
            map.removeSection(section.address());

            assertEquals(allSections, map.getAllSections());
        }
    }

    @Test
    void removeSection_Tracking() {
        map.addSection(1, 4, false);
        map.addSection(6, 4, false);
        assertEquals(List.of(), map.getAllInitializedSections());

        map.performAccess(2, 2, false);
        map.performAccess(6, 2, false);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(2, 2),
                new UninitializedMemoryTracker.Section(6, 2)
            ),
            map.getAllInitializedSections()
        );

        map.removeSection(1);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(6, 2)
            ),
            map.getAllInitializedSections()
        );

        map.removeSection(6);
        assertEquals(List.of(), map.getAllInitializedSections());


        map.addSection(1, 2, false);
        map.addSection(3, 2, false);
        map.performAccess(1, 2, false);
        map.performAccess(3, 2, false);
        // Current implementation merges adjacent initialized sections
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(1, 4)
            ),
            map.getAllInitializedSections()
        );
        // However, access spanning both sections should still not be permitted
        assertThrows(() -> map.performAccess(2, 3, true));

        map.removeSection(1);
        // Should have removed part of merged initialized section
        assertEquals(
            List.of(
                    new UninitializedMemoryTracker.Section(3, 2)
            ),
            map.getAllInitializedSections()
        );
    }

    @Test
    void moveSection() {
        map.addSection(1, 6, ALWAYS_INITIALIZED);

        // Same address, same size
        map.moveSection(1, 1, 6);
        assertEquals(List.of(new Section(1, 6)), map.getAllSections());

        map.moveSection(1, 8, 6);
        assertEquals(List.of(new Section(8, 6)), map.getAllSections());

        // Overlapping move
        map.moveSection(8, 9, 6);
        assertEquals(List.of(new Section(9, 6)), map.getAllSections());

        // Shrinking move (same address)
        map.moveSection(9, 9, 4);
        assertEquals(List.of(new Section(9, 4)), map.getAllSections());

        // Shrinking move
        map.moveSection(9, 3, 2);
        assertEquals(List.of(new Section(3, 2)), map.getAllSections());

        // Enlarging move (same address)
        map.moveSection(3, 3, 4);
        assertEquals(List.of(new Section(3, 4)), map.getAllSections());

        // Enlarging move
        map.moveSection(3, 7, 5);
        assertEquals(List.of(new Section(7, 5)), map.getAllSections());
    }

    @Test
    void moveSection_Error() {
        map.addSection(1, 4, ALWAYS_INITIALIZED);

        assertThrows(() -> map.moveSection(0, 10, 2));
        assertThrows(() -> map.moveSection(5, 10, 1));
        // Cannot move only part of section
        assertThrows(() -> map.moveSection(2, 10, 2));

        assertThrows(() -> map.moveSection(1, 10, -1));
        assertThrows(() -> map.moveSection(1, 10, 0));
        assertThrows(() -> map.moveSection(1, -1, 1));
    }

    @Test
    void moveSection_Tracking() {
        map.addSection(1, 4, false);
        assertEquals(List.of(), map.getAllInitializedSections());

        map.moveSection(1, 6, 4);
        assertEquals(List.of(), map.getAllInitializedSections());

        map.performAccess(6, 1, false);
        map.performAccess(8, 1, false);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(6, 1),
                new UninitializedMemoryTracker.Section(8, 1)
            ),
            map.getAllInitializedSections()
        );

        // Overlapping move
        map.moveSection(6, 7, 4);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(7, 1),
                new UninitializedMemoryTracker.Section(9, 1)
            ),
            map.getAllInitializedSections()
        );

        // Enlarging move
        map.moveSection(7, 2, 8);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(2, 1),
                new UninitializedMemoryTracker.Section(4, 1)
            ),
            map.getAllInitializedSections()
        );

        // Shrinking move
        map.moveSection(2, 10, 2);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(10, 1)
            ),
            map.getAllInitializedSections()
        );
    }

    @Test
    void performAccess() {
        map.addSection(0, 1, ALWAYS_INITIALIZED);
        map.addSection(5, 3, ALWAYS_INITIALIZED);

        map.performAccess(0, 1, IS_READ);
        map.performAccess(5, 1, IS_READ);
        map.performAccess(5, 3, IS_READ);
        map.performAccess(6, 2, IS_READ);
    }

    @Test
    void performAccess_Error() {
        assertThrows(() -> map.performAccess(-1, 1, IS_READ));
        assertThrows(() -> map.performAccess(0, -1, IS_READ));
        assertThrows(() -> map.performAccess(0, 0, IS_READ));

        map.addSection(1, 5, ALWAYS_INITIALIZED);
        map.addSection(7, 1, ALWAYS_INITIALIZED);
        map.addSection(8, 1, ALWAYS_INITIALIZED);

        assertThrows(() -> map.performAccess(0, 2, IS_READ));
        assertThrows(() -> map.performAccess(2, -1, IS_READ));
        assertThrows(() -> map.performAccess(2, 5, IS_READ));
        assertThrows(() -> map.performAccess(6, 1, IS_READ));
        // Cannot start in one section and end in another one
        assertThrows(() -> map.performAccess(7, 2, IS_READ));

        assertEquals(
            List.of(
                new Section(1, 5),
                new Section(7, 1),
                new Section(8, 1)
            ),
            map.getAllSections()
        );
    }

    @Test
    void performAccess_Tracking() {
        map.addSection(1, 4, false);

        // Reading uninitialized
        assertThrows(() -> map.performAccess(1, 4, true));

        map.performAccess(2, 2, false);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(2, 2)
            ),
            map.getAllInitializedSections()
        );

        // Reading initialized
        map.performAccess(2, 1, true);
        map.performAccess(2, 2, true);
        map.performAccess(3, 1, true);

        // Reading uninitialized
        assertThrows(() -> map.performAccess(1, 4, true));
        assertThrows(() -> map.performAccess(1, 2, true));
        assertThrows(() -> map.performAccess(4, 1, true));

        // Prepare split initialized with uninitialized gap
        map.removeSection(1);
        map.addSection(1, 4, false);
        map.performAccess(1, 1, false);
        map.performAccess(3, 1, false);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(1, 1),
                new UninitializedMemoryTracker.Section(3, 1)
            ),
            map.getAllInitializedSections()
        );
        // Reading section with uninitialized gap
        assertThrows(() -> map.performAccess(1, 3, true));
    }

    @Test
    void performCopyAccess() {
        map.addSection(1, 3, ALWAYS_INITIALIZED);
        map.addSection(5, 3, ALWAYS_INITIALIZED);

        map.performCopyAccess(1, 5, 3);
        map.performCopyAccess(2, 6, 2);
        // Overlapping copy
        map.performCopyAccess(1, 2, 2);

        // Copying does not create new sections
        assertEquals(
            List.of(
                new Section(1, 3),
                new Section(5, 3)
            ),
            map.getAllSections()
        );
    }

    @Test
    void performCopyAccess_Error() {
        map.addSection(1, 3, ALWAYS_INITIALIZED);
        map.addSection(5, 2, ALWAYS_INITIALIZED);

        assertThrows(() -> map.performCopyAccess(0, 5, 3));
        assertThrows(() -> map.performCopyAccess(1, 5, 3));
        assertThrows(() -> map.performCopyAccess(1, 6, 2));
        assertThrows(() -> map.performCopyAccess(1, 4, 2));
        assertThrows(() -> map.performCopyAccess(4, 5, 1));
    }

    @Test
    void performCopyAccess_TrackingTracking() {
        map.addSection(1, 4, false);
        map.addSection(6, 4, false);

        map.performAccess(2, 2, false);
        map.performCopyAccess(1, 7, 3);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(2, 2),
                new UninitializedMemoryTracker.Section(8, 2)
            ),
            map.getAllInitializedSections()
        );


        map.removeSection(1);
        map.removeSection(6);
        map.addSection(1, 4, false);
        map.performAccess(1, 1, false);
        map.performAccess(3, 1, false);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(1, 1),
                new UninitializedMemoryTracker.Section(3, 1)
            ),
            map.getAllInitializedSections()
        );
        // Overlapping copy
        map.performCopyAccess(1, 3, 2);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(1, 1),
                new UninitializedMemoryTracker.Section(3, 1)
            ),
            map.getAllInitializedSections()
        );
        // Overlapping copy
        map.performCopyAccess(1, 2, 3);
        // Uninitialized was copied from address 2 to 3
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(1, 2),
                new UninitializedMemoryTracker.Section(4, 1)
            ),
            map.getAllInitializedSections()
        );
    }

    @Test
    void performCopyAccess_TrackingNonTracking() {
        map.addSection(1, 4, false);
        map.addSection(6, 4, true);
        assertEquals(List.of(), map.getAllInitializedSections());

        // Copying uninitialized to non-tracking should fail
        assertThrows(() -> map.performCopyAccess(1, 6, 4));

        map.performAccess(1, 3, false);
        // Should also fail when only partially initialized
        assertThrows(() -> map.performCopyAccess(1, 6, 4));

        map.performCopyAccess(1, 6, 3);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(1, 3)
                // no entry for non-tracking
            ),
            map.getAllInitializedSections()
        );
    }

    @Test
    void performCopyAccess_NonTrackingTracking() {
        map.addSection(1, 4, true);
        map.addSection(6, 4, false);
        assertEquals(List.of(), map.getAllInitializedSections());

        // Copying non-tracking to uninitialized should mark destination as initialized
        map.performCopyAccess(1, 6, 4);
        assertEquals(
            List.of(
                new UninitializedMemoryTracker.Section(6, 4)
            ),
            map.getAllInitializedSections()
        );
    }

    @Test
    void checkIsInSection() {
        assertThrows(() -> map.checkIsInSection(1, true));
        assertThrows(() -> map.checkIsInSection(1, false));

        map.addSection(1, 10, false);
        map.checkIsInSection(1, false);
        map.checkIsInSection(5, false);
        map.checkIsInSection(10, false);
        map.checkIsInSection(11, true);

        assertThrows(() -> map.checkIsInSection(0, true));
        assertThrows(() -> map.checkIsInSection(11, false));
        assertThrows(() -> map.checkIsInSection(12, true));
    }

    @Test
    void hasSectionAt() {
        assertFalse(map.hasSectionAt(1));

        map.addSection(1, 10, false);
        assertTrue(map.hasSectionAt(1));

        assertFalse(map.hasSectionAt(0));
        // Only consider start of section, not if section includes address
        assertFalse(map.hasSectionAt(2));
    }

    @Test
    void disableUninitializedMemoryTracking() {
        map.enableUninitializedMemoryTracking(false);
        map.addSection(1, 10, false);

        // Should not throw an exception, even though this reads uninitialized memory
        map.performAccess(1, 10, true);
        map.performAccess(1, 1, false);
        map.performCopyAccess(1, 4, 2);
        map.removeSection(1);

        assertEquals(List.of(), map.getAllSections());
    }
}
