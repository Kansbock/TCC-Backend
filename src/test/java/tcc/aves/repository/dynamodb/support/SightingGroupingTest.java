package tcc.aves.repository.dynamodb.support;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SightingGroupingTest {
    @Test
    void keyUsesExactUnorderedSetAndCalendarMinute() {
        LocalDate day = LocalDate.of(2026, 9, 10);
        String key = SightingGrouping.key(List.of("b", "a"), day, LocalTime.of(10, 15, 1, 100));
        assertEquals("SIGHTING_GROUP#v1#" + SightingGrouping.hash("v1\n2026-09-10\n10:15\n1:a\n1:b\n"), key);
        assertEquals(key, SightingGrouping.key(List.of("a", "b"), day, LocalTime.of(10, 15, 59, 999_999_999)));
        assertNotEquals(key, SightingGrouping.key(List.of("a"), day, LocalTime.of(10, 15)));
        assertNotEquals(key, SightingGrouping.key(List.of("a", "b"), day, LocalTime.of(10, 16)));
        assertNotEquals(key, SightingGrouping.key(List.of("a", "b"), day.plusDays(1), LocalTime.of(10, 15)));
        assertEquals(LocalTime.of(10, 15), SightingGrouping.minute(LocalTime.of(10, 15, 59, 123)));
    }

    @Test
    void lengthPrefixSeparatesIdentifiersAndMemberKeysSortNumerically() {
        LocalDate day = LocalDate.of(2026, 9, 10);
        assertNotEquals(SightingGrouping.key(List.of("a|b", "c"), day, LocalTime.NOON),
                SightingGrouping.key(List.of("a", "b|c"), day, LocalTime.NOON));
        assertTrue(SightingGrouping.member(9).compareTo(SightingGrouping.member(10)) < 0);
        assertEquals("PHOTO#" + SightingGrouping.hash("https://synthetic.test/a"), SightingGrouping.photo("https://synthetic.test/a"));
    }
}
