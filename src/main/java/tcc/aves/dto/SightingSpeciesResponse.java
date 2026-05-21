package tcc.aves.dto;

import tcc.aves.model.Gender;

public record SightingSpeciesResponse(
        Long id,
        SpeciesResponse species,
        Integer quantity,
        Gender gender
) {
}
