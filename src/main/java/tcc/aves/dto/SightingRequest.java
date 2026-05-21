package tcc.aves.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record SightingRequest(
        @NotEmpty(message = "A lista de espécies não pode ser vazia")
        List<SightingSpeciesRequest> species,

        @NotNull(message = "Data é obrigatória")
        LocalDate date,

        @NotNull(message = "Hora é obrigatória")
        LocalTime time,

        String imageUrl
) {
}
