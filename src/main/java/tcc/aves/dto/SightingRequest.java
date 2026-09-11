package tcc.aves.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record SightingRequest(
        @NotEmpty(message = "A lista de espécies não pode ser vazia")
        @Size(max = 20, message = "Um avistamento pode referenciar no máximo 20 espécies")
        List<@NotBlank(message = "Id de espécie não pode ser vazio") String> speciesIds,

        @NotNull(message = "Data é obrigatória")
        LocalDate date,

        @NotNull(message = "Hora é obrigatória")
        LocalTime time,

        @Size(max = 2048, message = "URL da imagem deve ter no máximo 2048 caracteres")
        String imageUrl
) {
}
