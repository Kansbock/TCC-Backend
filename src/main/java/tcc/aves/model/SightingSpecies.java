package tcc.aves.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Entity
@Table(name = "sighting_species")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SightingSpecies {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "sighting_id", nullable = false)
    private Sighting sighting;

    @NotNull(message = "Espécie é obrigatória")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "species_id", nullable = false)
    private Species species;

    @NotNull(message = "Quantidade é obrigatória")
    @Min(value = 1, message = "Quantidade deve ser no mínimo 1")
    @Column(nullable = false)
    private Integer quantity;

    @NotNull(message = "Gênero é obrigatório")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;
}
