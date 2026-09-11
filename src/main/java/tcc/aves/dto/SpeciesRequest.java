package tcc.aves.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SpeciesRequest(
        @NotBlank(message = "Nome é obrigatório")
        @Size(max = 120, message = "Nome deve ter no máximo 120 caracteres")
        String name,

        @NotBlank(message = "Nome científico é obrigatório")
        @Size(max = 160, message = "Nome científico deve ter no máximo 160 caracteres")
        String scientificName,

        @Size(max = 2000, message = "Descrição deve ter no máximo 2000 caracteres")
        String description,

        @Size(max = 2000, message = "Dicas devem ter no máximo 2000 caracteres")
        String tips,

        @Size(max = 2048, message = "URL da imagem deve ter no máximo 2048 caracteres")
        String imageUrl
) {
}
