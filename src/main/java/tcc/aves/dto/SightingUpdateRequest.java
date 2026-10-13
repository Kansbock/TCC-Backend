package tcc.aves.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record SightingUpdateRequest(
        @NotEmpty @Size(max = 20) List<@NotBlank String> speciesIds,
        @NotNull LocalDate date,
        @NotNull LocalTime time,
        @Size(max = 2048) String imageUrl,
        @Size(max = 4) List<@NotBlank @Size(max = 2048) String> imageUrls
) {
}
