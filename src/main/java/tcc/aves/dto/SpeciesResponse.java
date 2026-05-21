package tcc.aves.dto;

import java.time.LocalDateTime;

public record SpeciesResponse(
        Long id,
        String name,
        String scientificName,
        String description,
        String tips,
        String imageUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
