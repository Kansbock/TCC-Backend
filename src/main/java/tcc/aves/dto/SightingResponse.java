package tcc.aves.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record SightingResponse(
        Long id,
        List<SightingSpeciesResponse> species,
        LocalDate date,
        LocalTime time,
        String imageUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
