package tcc.aves.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tcc.aves.dto.CursorPage;
import tcc.aves.dto.DashboardChartPoint;
import tcc.aves.dto.DashboardPeriod;
import tcc.aves.dto.DashboardResponse;
import tcc.aves.dto.DashboardSpeciesCount;
import tcc.aves.dto.SightingResponse;
import tcc.aves.dto.SpeciesResponse;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DashboardService {

    private static final int PAGE_SIZE = 50;
    private static final Set<String> UNIDENTIFIED_SPECIES_NAMES = Set.of(
            "outros", "ave nao identificada", "especie nao identificada");

    private final SightingService sightingService;
    private final Clock clock;

    @Autowired
    public DashboardService(SightingService sightingService) {
        this(sightingService, Clock.systemDefaultZone());
    }

    DashboardService(SightingService sightingService, Clock clock) {
        this.sightingService = sightingService;
        this.clock = clock;
    }

    public DashboardResponse getDashboard(DashboardPeriod period, String speciesId) {
        LocalDate today = LocalDate.now(clock);
        List<IdentifiedSighting> sightings = findSightings(period, today);
        List<DashboardSpeciesCount> species = countSpecies(sightings);
        List<DashboardSpeciesCount> leadingSpecies = leadingSpecies(species);
        LocalDate trendStart = resolveTrendStart(period, today, sightings);

        return new DashboardResponse(
                sightings.size(),
                species.size(),
                leadingSpecies,
                trend(period, trendStart, today, sightings),
                species,
                hourly(speciesId, sightings)
        );
    }

    private List<IdentifiedSighting> findSightings(DashboardPeriod period, LocalDate today) {
        LocalDate from = period.startDate(today);
        String cursor = null;
        List<IdentifiedSighting> sightings = new ArrayList<>();

        do {
            CursorPage<SightingResponse> page = sightingService.query(
                    null, from, from == null ? null : today, cursor, PAGE_SIZE, true, "newest");
            for (SightingResponse sighting : page.items()) {
                List<SpeciesResponse> identifiedSpecies = sighting.species().stream()
                        .filter(species -> !isUnidentified(species))
                        .toList();
                if (!identifiedSpecies.isEmpty()) {
                    sightings.add(new IdentifiedSighting(sighting.date(), sighting.time(), identifiedSpecies));
                }
            }
            if (page.hasMore() && (page.nextCursor() == null || page.nextCursor().isBlank())) {
                throw new IllegalStateException("Cursor ausente em página adicional de avistamentos");
            }
            cursor = page.nextCursor();
            if (!page.hasMore()) {
                break;
            }
        } while (true);

        return sightings;
    }

    private boolean isUnidentified(SpeciesResponse species) {
        return UNIDENTIFIED_SPECIES_NAMES.contains(normalizeSpeciesName(species.name()));
    }

    private String normalizeSpeciesName(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private List<DashboardSpeciesCount> countSpecies(List<IdentifiedSighting> sightings) {
        Map<String, SpeciesCounter> counters = new HashMap<>();
        for (IdentifiedSighting sighting : sightings) {
            for (SpeciesResponse species : sighting.species()) {
                counters.compute(species.id(), (ignored, current) -> current == null
                        ? new SpeciesCounter(species.id(), species.name(), 1)
                        : current.increment());
            }
        }

        return counters.values().stream()
                .map(SpeciesCounter::toResponse)
                .sorted(speciesComparator())
                .toList();
    }

    private Comparator<DashboardSpeciesCount> speciesComparator() {
        return Comparator.comparingInt(DashboardSpeciesCount::count).reversed()
                .thenComparing(DashboardSpeciesCount::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DashboardSpeciesCount::id);
    }

    private List<DashboardSpeciesCount> leadingSpecies(List<DashboardSpeciesCount> species) {
        if (species.isEmpty()) {
            return List.of();
        }
        int topCount = species.getFirst().count();
        return species.stream()
                .filter(item -> item.count() == topCount)
                .sorted(Comparator.comparing(DashboardSpeciesCount::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(DashboardSpeciesCount::id))
                .toList();
    }

    private LocalDate resolveTrendStart(DashboardPeriod period, LocalDate today,
                                        List<IdentifiedSighting> sightings) {
        if (period != DashboardPeriod.ALL_HISTORY) {
            return period.startDate(today);
        }
        return sightings.stream()
                .map(IdentifiedSighting::date)
                .min(Comparator.naturalOrder())
                .orElse(today);
    }

    private List<DashboardChartPoint> trend(DashboardPeriod period, LocalDate from, LocalDate to,
                                             List<IdentifiedSighting> sightings) {
        if (sightings.isEmpty()) {
            return List.of();
        }

        List<DateRange> ranges = switch (period) {
            case LAST_7_DAYS -> dailyRanges(from, to);
            case LAST_30_DAYS -> proportionalRanges(from, to, 5);
            case LAST_90_DAYS, LAST_180_DAYS, LAST_365_DAYS -> monthlyRanges(from, to);
            case ALL_HISTORY -> proportionalRanges(from, to, 12);
        };

        return ranges.stream()
                .map(range -> new DashboardChartPoint(range.label(), countSightingsInRange(sightings, range)))
                .toList();
    }

    private List<DateRange> dailyRanges(LocalDate from, LocalDate to) {
        List<DateRange> ranges = new ArrayList<>();
        for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(1)) {
            ranges.add(new DateRange(start, start, rangeLabel(start, start)));
        }
        return ranges;
    }

    private List<DateRange> monthlyRanges(LocalDate from, LocalDate to) {
        List<DateRange> ranges = new ArrayList<>();
        for (LocalDate start = from; !start.isAfter(to); ) {
            LocalDate end = start.plusMonths(1).minusDays(1);
            if (end.isAfter(to)) {
                end = to;
            }
            ranges.add(new DateRange(start, end, rangeLabel(start, end)));
            start = end.plusDays(1);
        }
        return ranges;
    }

    private List<DateRange> proportionalRanges(LocalDate from, LocalDate to, int maximumBuckets) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        int buckets = (int) Math.min(days, maximumBuckets);
        List<DateRange> ranges = new ArrayList<>(buckets);
        for (int index = 0; index < buckets; index++) {
            LocalDate start = from.plusDays(days * index / buckets);
            LocalDate end = from.plusDays(days * (index + 1) / buckets - 1);
            ranges.add(new DateRange(start, end, rangeLabel(start, end)));
        }
        return ranges;
    }

    private String rangeLabel(LocalDate start, LocalDate end) {
        if (start.equals(end)) {
            return dayAndMonth(start);
        }
        if (start.getYear() == end.getYear()) {
            return dayAndMonth(start) + "–" + dayAndMonth(end);
        }
        return dayAndMonthAndYear(start) + "–" + dayAndMonthAndYear(end);
    }

    private String dayAndMonth(LocalDate date) {
        return "%02d/%02d".formatted(date.getDayOfMonth(), date.getMonthValue());
    }

    private String dayAndMonthAndYear(LocalDate date) {
        return "%02d/%02d/%02d".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear() % 100);
    }

    private int countSightingsInRange(List<IdentifiedSighting> sightings, DateRange range) {
        return (int) sightings.stream()
                .filter(sighting -> !sighting.date().isBefore(range.from())
                        && !sighting.date().isAfter(range.to()))
                .count();
    }

    private List<DashboardChartPoint> hourly(String speciesId, List<IdentifiedSighting> sightings) {
        int[] values = new int[24];
        for (IdentifiedSighting sighting : sightings) {
            if (speciesId != null && sighting.species().stream().noneMatch(species -> speciesId.equals(species.id()))) {
                continue;
            }
            values[sighting.time().getHour()]++;
        }

        List<DashboardChartPoint> hourly = new ArrayList<>(values.length);
        for (int hour = 0; hour < values.length; hour++) {
            hourly.add(new DashboardChartPoint("%02dh".formatted(hour), values[hour]));
        }
        return hourly;
    }

    private record IdentifiedSighting(
            LocalDate date,
            LocalTime time,
            List<SpeciesResponse> species
    ) {
    }

    private record DateRange(
            LocalDate from,
            LocalDate to,
            String label
    ) {
    }

    private record SpeciesCounter(
            String id,
            String name,
            int count
    ) {
        SpeciesCounter increment() {
            return new SpeciesCounter(id, name, count + 1);
        }

        DashboardSpeciesCount toResponse() {
            return new DashboardSpeciesCount(id, name, count);
        }
    }
}
