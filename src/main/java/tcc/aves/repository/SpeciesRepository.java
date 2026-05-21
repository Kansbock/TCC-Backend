package tcc.aves.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tcc.aves.model.Species;

import java.util.List;
import java.util.Optional;

public interface SpeciesRepository extends JpaRepository<Species, Long> {

    Optional<Species> findByScientificName(String scientificName);

    List<Species> findByNameContainingIgnoreCase(String name);

    boolean existsByScientificName(String scientificName);
}
