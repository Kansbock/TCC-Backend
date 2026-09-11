package tcc.aves.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record SightingResponse(
        String id,
        List<SpeciesResponse> species,
        LocalDate date,
        LocalTime time,
        String imageUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
