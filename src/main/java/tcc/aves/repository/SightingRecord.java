package tcc.aves.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record SightingRecord(
        String id,
        List<String> speciesIds,
        LocalDate date,
        LocalTime time,
        String imageUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
