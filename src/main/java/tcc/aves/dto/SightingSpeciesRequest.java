package tcc.aves.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tcc.aves.model.Gender;

public record SightingSpeciesRequest(
        @NotNull(message = "ID da espécie é obrigatório")
        Long speciesId,

        @NotNull(message = "Quantidade é obrigatória")
        @Min(value = 1, message = "Quantidade deve ser no mínimo 1")
        Integer quantity,

        @NotNull(message = "Gênero é obrigatório")
        Gender gender
) {
}
