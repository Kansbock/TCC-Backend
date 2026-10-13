package tcc.aves.repository;

import java.util.Locale;

/** Supported global orderings for the sightings timeline. */
public enum SightingSort {
    NEWEST,
    OLDEST,
    SPECIES_ASC,
    SPECIES_DESC;

    public static SightingSort fromRequest(String value) {
        if (value == null || value.isBlank()) {
            return NEWEST;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "newest" -> NEWEST;
            case "oldest" -> OLDEST;
            case "species_asc" -> SPECIES_ASC;
            case "species_desc" -> SPECIES_DESC;
            default -> throw new IllegalArgumentException("Ordenação de avistamentos inválida");
        };
    }

    public boolean chronological() {
        return this == NEWEST || this == OLDEST;
    }

    public boolean ascending() {
        return this == OLDEST || this == SPECIES_ASC;
    }
}
