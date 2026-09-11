package tcc.aves.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limites de paginação e de tamanho de lista para avistamentos. */
@ConfigurationProperties(prefix = "app.sightings")
public record SightingProperties(
        int defaultPageSize,
        int maxPageSize,
        int maxSpeciesPerSighting,
        int maxLookbackMonths
) {
}
