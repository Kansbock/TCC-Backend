package tcc.aves.repository;

import java.time.Instant;

public record SpeciesRecord(
        String id,
        String name,
        String scientificName,
        String description,
        String tips,
        String imageUrl,
        long referenceCount,
        Instant createdAt,
        Instant updatedAt
) {
}
