package tcc.aves.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tcc.aves.dto.CursorPage;
import tcc.aves.dto.DashboardPeriod;
import tcc.aves.dto.DashboardResponse;
import tcc.aves.dto.SightingResponse;
import tcc.aves.dto.SpeciesResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    private SightingService sightingService;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        sightingService = mock(SightingService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);
        dashboardService = new DashboardService(sightingService, clock);
    }

    @Test
    void aggregatesIdentifiedSpeciesAndKeepsTheIdentifiedPartOfMixedSightings() {
        when(sightingService.query(any(), any(), any(), any(), any(), eq(true), any())).thenReturn(
                new CursorPage<>(List.of(
                        sighting("arara-1", LocalDate.of(2026, 10, 14), LocalTime.of(9, 0), arara()),
                        sighting("mixed", LocalDate.of(2026, 10, 13), LocalTime.of(9, 20), arara(), unidentified()),
                        sighting("tucano-1", LocalDate.of(2026, 10, 10), LocalTime.of(15, 0), tucano()),
                        sighting("unknown", LocalDate.of(2026, 10, 9), LocalTime.of(11, 0), unidentified())
                ), null, false));

        DashboardResponse response = dashboardService.getDashboard(DashboardPeriod.LAST_7_DAYS, null);

        assertEquals(3, response.totalSightings());
        assertEquals(2, response.uniqueSpecies());
        assertEquals(List.of("Arara"), response.leadingSpecies().stream().map(item -> item.name()).toList());
        assertEquals(List.of("Arara", "Tucano"), response.species().stream().map(item -> item.name()).toList());
        assertEquals(List.of(2, 1), response.species().stream().map(item -> item.count()).toList());
        assertEquals(2, pointValue(response, "09h"));
        assertEquals(1, pointValue(response, "15h"));
        assertEquals(0, pointValue(response, "11h"));
        assertEquals(7, response.trend().size());
        verify(sightingService).query(
                null, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 14),
                null, 50, true, "newest");
    }

    @Test
    void filtersTheHourlyFrequencyByTheSelectedSpecies() {
        when(sightingService.query(any(), any(), any(), any(), any(), eq(true), any())).thenReturn(
                new CursorPage<>(List.of(
                        sighting("arara-1", LocalDate.of(2026, 10, 14), LocalTime.of(9, 0), arara()),
                        sighting("tucano-1", LocalDate.of(2026, 10, 13), LocalTime.of(15, 0), tucano())
                ), null, false));

        DashboardResponse response = dashboardService.getDashboard(DashboardPeriod.LAST_7_DAYS, "sp-tucano");

        assertEquals(0, pointValue(response, "09h"));
        assertEquals(1, pointValue(response, "15h"));
    }

    @Test
    void followsAllCursorsBeforeCalculatingMetrics() {
        when(sightingService.query(null, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 14),
                null, 50, true, "newest"))
                .thenReturn(new CursorPage<>(List.of(
                        sighting("first", LocalDate.of(2026, 10, 14), LocalTime.NOON, arara())
                ), "cursor-2", true));
        when(sightingService.query(null, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 14),
                "cursor-2", 50, true, "newest"))
                .thenReturn(new CursorPage<>(List.of(
                        sighting("second", LocalDate.of(2026, 9, 15), LocalTime.NOON, tucano())
                ), null, false));

        DashboardResponse response = dashboardService.getDashboard(DashboardPeriod.LAST_30_DAYS, null);

        assertEquals(2, response.totalSightings());
        verify(sightingService, times(2)).query(any(), any(), any(), any(), any(), eq(true), any());
    }

    @Test
    void listsAllTiedLeadingSpeciesAlphabeticallyAndUsesUnboundedQueryForAllHistory() {
        when(sightingService.query(null, null, null, null, 50, true, "newest")).thenReturn(
                new CursorPage<>(List.of(
                        sighting("tucano", LocalDate.of(2025, 1, 1), LocalTime.NOON, tucano()),
                        sighting("arara", LocalDate.of(2025, 1, 2), LocalTime.NOON, arara())
                ), null, false));

        DashboardResponse response = dashboardService.getDashboard(DashboardPeriod.ALL_HISTORY, null);

        assertEquals(List.of("Arara", "Tucano"), response.leadingSpecies().stream().map(item -> item.name()).toList());
        assertTrue(response.trend().size() <= 12);
        verify(sightingService).query(null, null, null, null, 50, true, "newest");
    }

    private static int pointValue(DashboardResponse response, String label) {
        return response.hourly().stream()
                .filter(point -> point.label().equals(label))
                .findFirst()
                .orElseThrow()
                .value();
    }

    private static SightingResponse sighting(String id, LocalDate date, LocalTime time, SpeciesResponse... species) {
        Instant createdAt = Instant.parse("2026-10-14T12:00:00Z");
        return new SightingResponse(id, List.of(species), date, time, null, createdAt, createdAt);
    }

    private static SpeciesResponse arara() {
        return species("sp-arara", "Arara");
    }

    private static SpeciesResponse tucano() {
        return species("sp-tucano", "Tucano");
    }

    private static SpeciesResponse unidentified() {
        return species("sp-other", "Ave não identificada");
    }

    private static SpeciesResponse species(String id, String name) {
        Instant createdAt = Instant.parse("2026-10-14T12:00:00Z");
        return new SpeciesResponse(id, name, "Avis exemplaris", null, null, null, createdAt, createdAt);
    }
}
