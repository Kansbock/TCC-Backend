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
        Instant updatedAt,
        List<String> imageUrls
) {
    public SightingRecord(String id, List<String> speciesIds, LocalDate date, LocalTime time,
                          String imageUrl, Instant createdAt, Instant updatedAt) {
        this(id, speciesIds, date, time, imageUrl, createdAt, updatedAt,
                imageUrl == null ? List.of() : List.of(imageUrl));
    }
}
