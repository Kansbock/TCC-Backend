package tcc.aves.dto;

import java.time.Instant;

public record SpeciesResponse(
        String id,
        String name,
        String scientificName,
        String description,
        String tips,
        String imageUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
