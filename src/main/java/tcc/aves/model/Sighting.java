package tcc.aves.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sightings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Sighting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToMany(mappedBy = "sighting", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SightingSpecies> species = new ArrayList<>();

    @NotNull(message = "Data é obrigatória")
    @Column(nullable = false)
    private LocalDate date;

    @NotNull(message = "Hora é obrigatória")
    @Column(nullable = false)
    private LocalTime time;

    @Column(name = "image_url")
    private String imageUrl;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
