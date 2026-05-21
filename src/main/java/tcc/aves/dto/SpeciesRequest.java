package tcc.aves.dto;

import jakarta.validation.constraints.NotBlank;

public record SpeciesRequest(
        @NotBlank(message = "Nome é obrigatório")
        String name,

        @NotBlank(message = "Nome científico é obrigatório")
        String scientificName,

        String description,

        String tips,

        String imageUrl
) {
}
